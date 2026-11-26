package com.naturecloud.loadbalancer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.naturecloud.javassist.MetricsRange;
import com.naturecloud.javassist.Parameters;
import com.naturecloud.javassist.mss.MSS;
import com.naturecloud.javassist.tools.Metrics;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Datapoint;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsRequest;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsResponse;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;
import software.amazon.awssdk.services.lambda.model.LogType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Main {

    private static final Logger LOG = Logger.getLogger(Main.class.getName());

    private static final String IMAGE_ID = getImageId();
    private static final String KEY_NAME = "uau";
    private static final String SECURITY_GROUP = "sg-07bf197db64b7bab0";
    private static final String IAM_ROLE = "naturecloud-worker-role";
    private static final int WORKER_PORT = 8000;

    private static final int LB_PORT = 8080;

    private static final long WORKER_LOAD_CAPACITY_UNITS = 1_000_000L;
    private static final long EC2_MEMORY_CAPACITY = 512L * 1024 * 1024;
    private static final double MEMORY_ADMISSION_UTIL_LIMIT = 0.65;
    private static final long EC2_MEMORY_ADMISSION_CAPACITY = (long) (EC2_MEMORY_CAPACITY * MEMORY_ADMISSION_UTIL_LIMIT);
    private static final double MEMORY_SAFETY_FACTOR = 1.25;
    private static final int MAX_CONCURRENT_PER_WORKER = 4;
    private static final double T3_MICRO_SLOWDOWN_FACTOR = 2.0;

    private static final double FRACTALS_WORKLOAD_CAPACITY = 455_771.4386;
    private static final double GRAYSCOTT_WORKLOAD_CAPACITY = 1_956_526.1329;
    private static final double DNA_WORKLOAD_CAPACITY = 399_527.0690;

    private static final long LOAD_RESERVE_WAIT_TIMEOUT_MS = 30_000;
    private static final long EXECUTION_SLOT_WAIT_TIMEOUT_MS = 90_000;

    private static final double HIGH_UTILIZATION = 0.85;
    private static final double LOW_UTILIZATION = 0.25;

    private static final long LAMBDA_COST_THRESHOLD = 500_000_000;
    private static final long LAMBDA_MEMORY_THRESHOLD = 200L * 1024 * 1024;

    private static final String LAMBDA_FRACTALS = "naturecloud-fractals";
    private static final String LAMBDA_GRAYSCOTT = "naturecloud-grayscott";
    private static final String LAMBDA_DNA = "naturecloud-dna";

    private static final LambdaClient LAMBDA_CLIENT = LambdaClient.builder().region(Region.US_EAST_1).build();

    private static final int MIN_WORKERS = 1;
    private static final int MAX_WORKERS = 5;
    private static final long SCALE_INTERVAL_MS = 15000;
    private static final long SCALE_OUT_COOLDOWN_MS = 90000;
    private static final long SCALE_IN_COOLDOWN_MS = 180000;
    private static final int SCALE_OUT_REQUIRED_CYCLES = 2;
    private static final int SCALE_IN_REQUIRED_CYCLES = 4;
    private static final double SCALE_OUT_AVG_THRESHOLD = 0.85;
    private static final double SCALE_OUT_MAX_THRESHOLD = 0.95;
    private static final double SCALE_IN_AVG_THRESHOLD = 0.25;
    private static final double SCALE_IN_MAX_THRESHOLD = 0.35;

    // CloudWatch-based CPU scaling (>85% mean CPU scales out, <25% scales in)
    private static final double CW_CPU_SCALE_OUT_THRESHOLD = 85.0;
    private static final double CW_CPU_SCALE_IN_THRESHOLD = 25.0;
    // CloudWatch window to read CPUUtilization over (must be >= one period; detailed monitoring = 60s).
    private static final long CW_OBS_WINDOW_MS = 5L * 60 * 1000;     // last 5 minutes
    private static final int CW_PERIOD_SECONDS = 60;

    private static final int MIN_REGION_SAMPLES = 3;

    enum SchedulingMode {
        SPREADING, PACKING
    }

    private static volatile SchedulingMode MODE = SchedulingMode.SPREADING;

    enum WorkerState {
        STARTING, ACTIVE, DRAINING, TERMINATED
    }

    private static final CopyOnWriteArrayList<Worker> WORKERS = new CopyOnWriteArrayList<>();

    private static final ConcurrentHashMap<String, CacheEntry> PREDICTION_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> REFRESH_IN_FLIGHT = new ConcurrentHashMap<>();
    private static final ExecutorService CACHE_REFRESH_POOL = Executors.newCachedThreadPool();

    private static final Comparator<Worker> WORKER_ORDER = Comparator.comparingLong((Worker w) -> w.outstandingLoad.get()).thenComparingLong(w -> w.outstandingMemory.get()).thenComparing(w -> w.instanceId);

    public static String getImageId() {

        Ec2Client ec2 = Ec2Client.builder().region(Region.US_EAST_1).build();

        DescribeImagesRequest request = DescribeImagesRequest.builder().owners("self").filters(Filter.builder().name("name").values("NatureCloud-Worker-Image").build()).build();

        DescribeImagesResponse response = ec2.describeImages(request);

        Image latestImage = response.images().stream().max(Comparator.comparing(Image::creationDate)).orElse(null);

        ec2.close();

        assert latestImage != null;
        return latestImage.imageId();
    }

    static class Worker {

        final String instanceId;
        volatile String address;
        private volatile WorkerState state;
        private final Object stateLock = new Object();

        final AtomicLong outstandingLoad = new AtomicLong(0);
        final AtomicLong outstandingMemory = new AtomicLong(0);
        final AtomicLong activeRequests = new AtomicLong(0);

        // Latest CPUUtilization (%) read from CloudWatch; -1 means "not yet available".
        volatile double cpuUtil = -1.0;

        Worker(String instanceId, String address, WorkerState state) {
            this.instanceId = instanceId;
            this.address = address;
            this.state = state;
        }

        WorkerState getState() {
            return state;
        }

        boolean compareAndSetState(WorkerState expect, WorkerState update) {
            synchronized (stateLock) {
                if(state == expect) {
                    state = update;
                    return true;
                }
                return false;
            }
        }

        boolean tryReserveLoad(long cost) {
            synchronized (stateLock) {
                if(state != WorkerState.ACTIVE) {
                    return false;
                }
                if(outstandingLoad.get() + cost > WORKER_LOAD_CAPACITY_UNITS) {
                    return false;
                }
                outstandingLoad.addAndGet(cost);
                return true;
            }
        }

        boolean tryAcquireExecution(long memory) {
            synchronized (stateLock) {
                if(state != WorkerState.ACTIVE) {
                    return false;
                }
                if(activeRequests.get() >= MAX_CONCURRENT_PER_WORKER) {
                    return false;
                }
                long nextMemory = outstandingMemory.get() + memory;
                if(nextMemory > EC2_MEMORY_ADMISSION_CAPACITY) {
                    return false;
                }
                outstandingMemory.addAndGet(memory);
                activeRequests.incrementAndGet();
                return true;
            }
        }

        void releaseLoad(long cost) {
            outstandingLoad.addAndGet(-cost);
        }

        void releaseExecution(long memory) {
            outstandingMemory.addAndGet(-memory);
            activeRequests.decrementAndGet();
        }

        String healthCheckUrl() {
            return "http://" + address + ":" + WORKER_PORT + "/test";
        }

        String forwardUrl(String path, String query) {
            if (!path.equals("/dna")) {
                return "http://" + address + ":" + WORKER_PORT + path + (query != null && !query.isEmpty() ? "?" + query : "");
            }

            return "http://" + address + ":" + WORKER_PORT + path + (!query.isEmpty() ? "?" + query : "");
        }

        boolean healthCheck() {
            try {
                URL url = new URL(healthCheckUrl());
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                conn.setRequestMethod("GET");
                int code = conn.getResponseCode();
                conn.disconnect();
                return code == 200;
            } catch (Exception e) {
                return false;
            }
        }
    }

    static final class CacheEntry {

        final long avgInstructions;
        final long avgMemoryAccesses;
        final long avgMemoryAllocated;
        final long sampleCount;
        final long lastRefresh;

        CacheEntry(long avgInstructions, long avgMemoryAccesses, long avgMemoryAllocated, long sampleCount) {
            this.avgInstructions = avgInstructions;
            this.avgMemoryAccesses = avgMemoryAccesses;
            this.avgMemoryAllocated = avgMemoryAllocated;
            this.sampleCount = sampleCount;
            this.lastRefresh = System.currentTimeMillis();
        }
    }

    static final class RequestPrediction {

        final long estimatedLoadUnits;
        final long estimatedInstructions;
        final long estimatedMemoryUsed;

        RequestPrediction(long loadUnits, long instructions, long memory) {
            this.estimatedLoadUnits = loadUnits;
            this.estimatedInstructions = instructions;
            this.estimatedMemoryUsed = memory;
        }
    }

    static RequestPrediction estimateRequest(String function, Parameters params) {
        long heuristicInstructions = heuristicInstructionEstimate(function, params);
        long heuristicMemAllocated = heuristicAllocatedMemory(function, params);
        long estimatedInstructions = heuristicInstructions;
        long estimatedMemAllocated = heuristicMemAllocated;

        long regionKey = Metrics.snapToRegion(function, params);
        String cacheKey = function + ":" + regionKey;

        CacheEntry stats = PREDICTION_CACHE.get(cacheKey);

        if (stats == null) {
            asyncRefreshRegionFromMSS(function, regionKey, cacheKey);
        } else if (stats.sampleCount >= MIN_REGION_SAMPLES) {
            double mssWeight = mssMetricWeight(stats.sampleCount);
            estimatedInstructions = blendMetricEstimate(heuristicInstructions, stats.avgInstructions, mssWeight);
            estimatedMemAllocated = blendMetricEstimate(heuristicMemAllocated, stats.avgMemoryAllocated, mssWeight);
        }

        long estimatedMemoryUsed = applyMemorySafetyFactor(memoryAllocatedToMemoryUsed(function, params, estimatedMemAllocated));
        double workloadCost = estimateWorkloadCost(function, params, estimatedInstructions);
        double loadFraction = workloadCost * T3_MICRO_SLOWDOWN_FACTOR / workloadCapacity(function);
        long loadUnits = Math.max(1L, (long) Math.ceil(loadFraction * WORKER_LOAD_CAPACITY_UNITS));

        return new RequestPrediction(loadUnits, estimatedInstructions, estimatedMemoryUsed);
    }

    static double mssMetricWeight(long sampleCount) {
        if (sampleCount < MIN_REGION_SAMPLES) {
            return 0.0;
        }
        if (sampleCount < 10) {
            return 0.50;
        }
        if (sampleCount < 30) {
            return 0.75;
        }
        return 0.90;
    }

    static long blendMetricEstimate(long heuristicValue, long mssAverage, double mssWeight) {
        double heuristicWeight = 1.0 - mssWeight;
        return Math.max(0L, Math.round(heuristicWeight * heuristicValue + mssWeight * mssAverage));
    }

    static void asyncRefreshRegionFromMSS(String function, long regionKey, String cacheKey) {
        if (REFRESH_IN_FLIGHT.putIfAbsent(cacheKey, Boolean.TRUE) != null) {
            return;
        }
        CACHE_REFRESH_POOL.submit(() -> {
            try {
                MetricsRange region = MSS.getRegion(function, regionKey);
                if (region != null) {
                    PREDICTION_CACHE.put(cacheKey, new CacheEntry(region.getAvgInstructions(), region.getAvgMemoryAccesses(), region.getAvgMemoryAllocated(), region.getTotalRequests()));
                }
            } catch (Exception e) {
                LOG.warning("Cache refresh failed for " + cacheKey + ": " + e.getMessage());
            } finally {
                REFRESH_IN_FLIGHT.remove(cacheKey);
            }
        });
    }

    static long heuristicInstructionEstimate(String function, Parameters p) {
        switch (function) {
            case "fractals": {
                long pixels = (long) p.getWidth() * p.getHeight();
                return pixels * Metrics.estimateFractalNEff(p.getMaxIterations());
            }
            case "grayScott": {
                long nEff = p.getExtinction() ? Metrics.estimateGrayScottNEff(p.getF(), p.getK(), p.getMaxIterations()) : p.getMaxIterations();
                return 273L * p.getSize() * p.getSize() * nEff;
            }
            case "dna": {
                int s1 = p.getSequence1Length();
                int s2 = p.getSequence2Length();
                int ml = p.getMinLength();
                long s1c = Math.max(0, s1 - ml + 1);
                long s2c = Math.max(0, s2 - ml + 1);
                long pairs = s1c * s2c;

                if (pairs == 0) {
                    return 200;
                }
                if (p.getStop() && ml <= 3) {
                    return 2_000;
                }
                if (p.getStop() && ml <= 25) {
                    return 155L * s2;
                }
                if (ml >= 100) {
                    return 200 + 17L * pairs;
                }

                double costPerPair;
                if (ml <= 3) {
                    costPerPair = 0.1;
                } else if (ml <= 25) {
                    costPerPair = 1.5;
                } else {
                    costPerPair = 8.0;
                }
                return (long) (costPerPair * pairs);
            }
            default:
                return 0L;
        }
    }

    static long heuristicMemoryAccessesEstimate(String function, Parameters p) {
        switch (function) {
            case "fractals": {
                long pixels = (long) p.getWidth() * p.getHeight();
                int iterations = p.getMaxIterations();
                int accesses_per_pixel = 0;
                if (iterations <= 1) {
                    accesses_per_pixel = 2;
                } else if (iterations <= 10) {
                    accesses_per_pixel = 6;
                } else if (iterations <= 25) {
                    accesses_per_pixel = 11;
                } else if (iterations <= 50) {
                    accesses_per_pixel = 16;
                } else if (iterations <= 100) {
                    accesses_per_pixel = 21;
                } else if (iterations <= 250) {
                    accesses_per_pixel = 25;
                } else {
                    accesses_per_pixel = 25;
                }
                return pixels * accesses_per_pixel;
            }
            case "grayScott": {
                return 26L * p.getSize() * p.getSize() * Metrics.estimateGrayScottNEff(p.getF(), p.getK(), p.getMaxIterations());
            }
            case "dna": {
                return 20L;  // This was the medium value we found in our tests, we detected that this function wasn't really memory accesses bounded.
            }
            default:
                return 0L;
        }
    }

    static long heuristicAllocatedMemory(String function, Parameters p) {
        switch (function) {
            case "fractals":
                return 7_600_000L + 28L * p.getWidth() * p.getHeight();
            case "grayScott":
                return 860_000L + 60L * p.getSize() * p.getSize();
            case "dna":
                return estimateDnaAllocatedMemory(p.getSequence1Length(), p.getSequence2Length(), p.getMinLength());
            default:
                return 0L;
        }
    }

    static long estimateDnaAllocatedMemory(int s1, int s2, int ml) {
        long s1c = Math.max(0, s1 - ml + 1);
        long s2c = Math.max(0, s2 - ml + 1);
        long pairs = s1c * s2c;
        return 2_000_000L + (long) (1.1 * ml * pairs);
    }

    static long memoryAllocatedToMemoryUsed(String function, Parameters p, long allocated) {
        switch (function) {
            case "fractals": {
                double allocMB = (double) allocated / 1000000;
                double usedMB = (allocMB + 7.645) / 7.0;
                return (long) (usedMB * 1000000);
            }
            case "dna": {
                int ml = p.getMinLength();
                double used = 71974.8236086406 + 7.049843718146969e-07 * allocated - 6.787910281059149e-19 * allocated * allocated - 3.164300911925921e-10 * (double) allocated * ml - 4.979366655531984e-13 * (double) ml * ml;
                return Math.max(0, (long) used);
            }
            case "grayScott": {
                long used = (long) ((allocated + 10_000_000.0) / 14.875);
                return Math.max(0, used);
            }
            default:
                return 0L;
        }
    }

    static long applyMemorySafetyFactor(long memoryUsed) {
        return Math.max(0L, (long) Math.ceil(memoryUsed * MEMORY_SAFETY_FACTOR));
    }

    static double estimateWorkloadCost(String function, Parameters p, long instructions) {
        double instructionsMillions = instructions / 1_000_000.0;
        switch (function) {
            case "fractals": {
                double pixelsMillions = ((double) p.getWidth() * p.getHeight()) / 1_000_000.0;
                return Math.max(25.0, -33.7459 + 0.1456643013 * instructionsMillions + 30.941477 * pixelsMillions);
            }
            case "grayScott":
                return instructionsMillions;
            case "dna": {
                double pairWorkMillions = dnaPairWorkMillions(p);
                return 146.4859784 + 0.2799395964 * instructionsMillions + 0.0673526939 * pairWorkMillions;
            }
            default:
                return 1.0;
        }
    }

    static double workloadCapacity(String function) {
        switch (function) {
            case "fractals":
                return FRACTALS_WORKLOAD_CAPACITY;
            case "grayScott":
                return GRAYSCOTT_WORKLOAD_CAPACITY;
            case "dna":
                return DNA_WORKLOAD_CAPACITY;
            default:
                return WORKER_LOAD_CAPACITY_UNITS;
        }
    }

    static long dnaCandidatePairs(int s1, int s2, int ml) {
        long s1c = Math.max(0, s1 - ml + 1);
        long s2c = Math.max(0, s2 - ml + 1);
        return s1c * s2c;
    }

    static double dnaPairWorkMillions(Parameters p) {
        return ((double) dnaCandidatePairs(p.getSequence1Length(), p.getSequence2Length(), p.getMinLength()) * p.getMinLength()) / 1_000_000.0;
    }

    static class ProxyHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {

            URI uri = exchange.getRequestURI();
            String path = uri.getPath();
            String query = uri.getRawQuery();
            String function = getFunction(path);

            Parameters params = parseParams(function, query);
            if (params == null) {
                sendError(exchange, 400, "Bad Params");
                return;
            }
            RequestPrediction prediction = estimateRequest(function, params);
            long requestCost = prediction.estimatedLoadUnits;
            long requestMemory = prediction.estimatedMemoryUsed;

            List<Worker> candidates = activeCandidates();

            long totalLoad = candidates.stream().mapToLong(w -> w.outstandingLoad.get()).sum();
            long clusterCap = (long) candidates.size() * WORKER_LOAD_CAPACITY_UNITS;
            double utilization = clusterCap > 0 ? (double) totalLoad / clusterCap : 0.0;

            if (utilization >= HIGH_UTILIZATION) {
                MODE = SchedulingMode.SPREADING;
            } else if (utilization <= LOW_UTILIZATION) {
                MODE = SchedulingMode.PACKING;
            }

            Worker reserved = reserveWorker(candidates, requestCost);

            if (reserved == null) {
                if (lambdaSuitable(prediction)) {
                    invokeLambdaAndRespond(exchange, path, query);
                    return;
                }

                long waitDeadline = System.currentTimeMillis() + LOAD_RESERVE_WAIT_TIMEOUT_MS;
                while (reserved == null && System.currentTimeMillis() < waitDeadline) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    List<Worker> fresh = activeCandidates();
                    reserved = reserveWorker(fresh, requestCost);
                }

                if (reserved == null) {
                    if (lambdaSuitable(prediction)) {
                        invokeLambdaAndRespond(exchange, path, query);
                    } else {
                        sendError(exchange, 503, "No worker available, please try again later.");
                    }
                    return;
                }
            }

            forwardWithRetry(exchange, path, query, reserved, requestCost, requestMemory, prediction);
        }

        private Worker reserveWorker(List<Worker> candidates, long cost){
            if(candidates.size() == 1){
                return candidates.get(0);
            }

            List<Worker> ordered = new ArrayList<>(candidates);
            ordered.sort(WORKER_ORDER);

            if(MODE == SchedulingMode.PACKING){
                for(int i = 1; i < candidates.size(); i++){
                    Worker w = ordered.get(i);
                    if(w.tryReserveLoad(cost)){
                        return w;
                    }
                }
            }
            else {
                for(Worker w : ordered){
                    if(w.tryReserveLoad(cost)){
                        return w;
                    }
                }
            }
            return null;
        }

        private void forwardWithRetry(HttpExchange exchange, String path, String query, Worker chosen, long cost, long memory, RequestPrediction prediction) throws IOException {
            boolean success = forwardAssignedRequest(exchange, path, query, chosen, cost, memory);
            if (success) {
                return;
            }

            Worker retry = null;
            List<Worker> ordered = new ArrayList<>(activeCandidates());
            ordered.sort(WORKER_ORDER);
            for(Worker w : ordered){
                if(w == chosen) {
                    continue;
                }
                if(w.tryReserveLoad(cost)){
                    retry = w;
                    break;
                }
            }

            if(retry != null) {
                if (forwardAssignedRequest(exchange, path, query, retry, cost, memory)) {
                    return;
                }
            }

            if (lambdaSuitable(prediction)) {
                invokeLambdaAndRespond(exchange, path, query);
            }
            else {
                sendError(exchange, 503, "All workers are busy, please try again later.");
            }
        }

        private boolean forwardAssignedRequest(HttpExchange exchange, String path, String query, Worker worker, long cost, long memory) {
            boolean executionReserved = false;
            try {
                executionReserved = waitForExecutionSlot(worker, memory);
                if (!executionReserved) {
                    return false;
                }

                boolean success = forwardRequest(exchange, worker, path, query);
                if (!success && worker.compareAndSetState(WorkerState.ACTIVE, WorkerState.DRAINING)) {
                    LOG.warning("Worker " + worker.instanceId + " failed, marking DRAINING");
                }
                return success;
            } finally {
                if (executionReserved) {
                    worker.releaseExecution(memory);
                }
                worker.releaseLoad(cost);
            }
        }

        private boolean waitForExecutionSlot(Worker worker, long memory) {
            long waitDeadline = System.currentTimeMillis() + EXECUTION_SLOT_WAIT_TIMEOUT_MS;
            while (System.currentTimeMillis() < waitDeadline) {
                if (worker.tryAcquireExecution(memory)) {
                    return true;
                }
                if (worker.getState() != WorkerState.ACTIVE) {
                    return false;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return false;
        }

        private boolean forwardRequest(HttpExchange exchange, Worker worker, String path, String query){
            try {
                URL url = new URL(worker.forwardUrl(path, query));
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(300000);
                conn.setReadTimeout(600000);
                conn.setRequestMethod(exchange.getRequestMethod());

                exchange.getRequestHeaders().forEach((k, vs) -> {
                    if (!k.equalsIgnoreCase("Host")) {
                        vs.forEach(v -> conn.addRequestProperty(k, v));
                    }
                });

                if ("POST".equalsIgnoreCase(exchange.getRequestMethod()) || "PUT".equalsIgnoreCase(exchange.getRequestMethod())) {
                    conn.setDoOutput(true);
                    try (OutputStream out = conn.getOutputStream()) {
                        exchange.getRequestBody().transferTo(out);
                    }
                }

                int status = conn.getResponseCode();

                conn.getHeaderFields().forEach((k, vs) -> {
                    if (k != null) {
                        exchange.getResponseHeaders().put(k, vs);
                    }
                });

                InputStream bodyStream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
                byte[] body = bodyStream != null ? bodyStream.readAllBytes() : new byte[0];

                exchange.sendResponseHeaders(status, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
                conn.disconnect();
                return true;

            } catch (Exception e) {
                LOG.log(Level.WARNING, "Forward failed to " + worker.address, e);
                return false;
            }
        }

        private void invokeLambdaAndRespond(HttpExchange exchange, String path, String query) throws IOException {
            String function = getFunction(path);
            String lambdaName;
            switch (function) {
                case "fractals":
                    lambdaName = LAMBDA_FRACTALS;
                    break;
                case "grayScott":
                    lambdaName = LAMBDA_GRAYSCOTT;
                    break;
                case "dna":
                    lambdaName = LAMBDA_DNA;
                    break;
                default:
                    sendError(exchange, 400, "Unknown function: " + function);
                    return;
            }

            String payloadJson = buildLambdaPayload(query);
            LOG.info("Invoking Lambda " + lambdaName + " payload=" + payloadJson);

            try {
                InvokeRequest invokeReq = InvokeRequest.builder().functionName(lambdaName).logType(LogType.NONE).payload(SdkBytes.fromUtf8String(payloadJson)).build();

                InvokeResponse invokeResp = LAMBDA_CLIENT.invoke(invokeReq);

                String functionError = invokeResp.functionError();
                if (functionError != null && !functionError.isEmpty()) {
                    LOG.warning("Lambda " + lambdaName + " returned error: " + functionError);
                    String errBody = invokeResp.payload() != null ? invokeResp.payload().asUtf8String() : "Lambda function error";
                    sendError(exchange, 502, errBody);
                    return;
                }
                exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");

                if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
                    exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type,Authorization");
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                String payload = invokeResp.payload().asUtf8String();
                String trimed = payload.substring(1, payload.length() - 1);
                byte[] body = trimed.replace("\\n", "\n").replace("\\\"", "\"").getBytes();
                exchange.sendResponseHeaders(invokeResp.sdkHttpResponse().statusCode(), body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }

            } catch (Exception e) {
                LOG.log(Level.WARNING, "Lambda invocation failed for " + lambdaName, e);
                sendError(exchange, 502, "Lambda invocation failed: " + e.getMessage());
            }
        }

        private String buildLambdaPayload(String query) {
            Map<String, String> params = new HashMap<>();
            if (query != null && !query.isEmpty()) {
                for (String kv : query.split("&")) {
                    String[] parts = kv.split("=", 2);
                    if (parts.length == 2) {
                        try {
                            params.put(parts[0], parts[1]);
                        } catch (Exception e) {
                            params.put(parts[0], parts[1]);
                        }
                    }
                }
            }
            // Build flat JSON object: {"key":"value",...}
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (!first) {
                    sb.append(",");
                }
                sb.append("\"").append(jsonEscape(entry.getKey())).append("\":");
                sb.append("\"").append(jsonEscape(entry.getValue())).append("\"");
                first = false;
            }
            sb.append("}");
            return sb.toString();

        }

        private String jsonEscape(String s) {
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }

        private void sendError(HttpExchange exchange, int code, String msg) throws IOException {
            byte[] body = msg.getBytes();
            exchange.sendResponseHeaders(code, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        }

        static List<Worker> activeCandidates() {
            List<Worker> result = new ArrayList<>();
            for (Worker w : WORKERS) {
                if (w.getState() == WorkerState.ACTIVE && w.address != null && !w.address.isEmpty()) {
                    result.add(w);
                }
            }
            return result;
        }

        static boolean lambdaSuitable(RequestPrediction p) {
            return p.estimatedInstructions <= LAMBDA_COST_THRESHOLD && p.estimatedMemoryUsed <= LAMBDA_MEMORY_THRESHOLD;
        }

        static String getFunction(String path) {
            if (path.startsWith("/fractals")) {
                return "fractals";
            }
            if (path.startsWith("/grayscott")) {
                return "grayScott";
            }
            if (path.startsWith("/dna")) {
                return "dna";
            }
            return "unknown";
        }

        static Parameters parseParams(String function, String query) {
            try {
                Map<String, String> params = new HashMap<>();
                if (query != null && !query.isEmpty()) {
                    for (String kv : query.split("&")) {
                        String[] parts = kv.split("=", 2);
                        if (parts.length == 2) {
                            params.put(urlDecode(parts[0]), urlDecode(parts[1]));
                        }
                    }
                }
                switch (function) {
                    case "fractals": {
                        int w = Integer.parseInt(params.get("w"));
                        int h = Integer.parseInt(params.get("h"));
                        int iter = Integer.parseInt(params.get("iterations"));
                        return new Parameters(w, h, iter);
                    }
                    case "grayScott": {
                        int size = Integer.parseInt(params.get("size"));
                        int iter = Integer.parseInt(params.get("maxIterations"));
                        double f = Double.parseDouble(params.get("f"));
                        double k = Double.parseDouble(params.get("k"));
                        boolean stop = Boolean.parseBoolean(params.get("stopOnExtinction"));
                        String seed = params.get("seedMode");
                        return new Parameters(size, iter, f, k, stop, seed);
                    }
                    case "dna": {
                        int minLength = Integer.parseInt(params.get("minLength"));
                        boolean stop = Boolean.parseBoolean(params.get("stopOnFirst"));
                        String seq1 = normalizeDnaSequence(params.get("seq1"));
                        String seq2 = normalizeDnaSequence(params.get("seq2"));
                        return new Parameters(seq1, seq2, minLength, stop);
                    }
                    default:
                        return null;
                }
            }
            catch (Exception e) {
                return null;
            }
        }

        static String urlDecode(String value) {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        }

        static String normalizeDnaSequence(String raw) {
            if (raw == null) {
                return "";
            }

            String value = raw;
            int colon = value.indexOf(':');
            if (colon >= 0) {
                value = value.substring(colon + 1);
            }

            StringBuilder builder = new StringBuilder();
            for (String line : value.split("\\R")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith(">") && !trimmed.startsWith(";")) {
                    builder.append(trimmed.toUpperCase(Locale.ROOT));
                }
            }
            return builder.toString();
        }
    }

    /*-------------------------------------------------------------------------
    / Auto-scaler
    / -------------------------------------------------------------------------*/
    static class AutoScaler implements Runnable {

        private final Ec2Client ec2;
        private final CloudWatchClient cloudWatch;
        private int highLoadCycles = 0;
        private int lowLoadCycles = 0;
        private long lastScaleOutTime = Long.MIN_VALUE / 2;
        private long lastScaleInTime = Long.MIN_VALUE / 2;

        AutoScaler() {
            this.ec2 = Ec2Client.builder().region(Region.US_EAST_1).build();
            this.cloudWatch = CloudWatchClient.builder().region(Region.US_EAST_1).build();
        }

        @Override
        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(SCALE_INTERVAL_MS);
                    tick();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "AutoScaler tick error", e);
                }
            }
        }

        private void tick() {
            for (Worker w : WORKERS) {
                if (w.getState() == WorkerState.STARTING) {
                    String addr = resolveAddress(w.instanceId);
                    if (addr != null && !addr.isEmpty()) {
                        w.address = addr;
                        if (w.healthCheck()) {
                            if(w.compareAndSetState(WorkerState.STARTING, WorkerState.ACTIVE)) {
                                LOG.info("Worker " + w.instanceId + " is now ACTIVE at " + addr);
                            }
                        }
                    }
                }
            }

            for (Worker w : new ArrayList<>(WORKERS)) {
                if (w.getState() == WorkerState.DRAINING && w.activeRequests.get() == 0) {
                    terminateInstance(w.instanceId);
                    if(w.compareAndSetState(WorkerState.DRAINING, WorkerState.TERMINATED)) {
                        WORKERS.remove(w);
                        LOG.info("Terminated worker " + w.instanceId);
                    }
                }
            }

            List<Worker> activeWorkers = new ArrayList<>();
            List<Worker> startingWorkers = new ArrayList<>();
            for (Worker w : WORKERS) {
                WorkerState wState = w.getState();
                if (wState == WorkerState.ACTIVE) {
                    activeWorkers.add(w);
                }
                else if (wState == WorkerState.STARTING) {
                    startingWorkers.add(w);
                }
            }

            if (activeWorkers.isEmpty() && startingWorkers.isEmpty()) {
                launchNewWorker();
                return;
            }
            if (activeWorkers.isEmpty()) {
                return;
            }

            double cpuSum = 0.0;
            double cpuMax = 0.0;
            int cpuCount = 0;

            for (Worker w : activeWorkers) {
                double cpu = readCpuUtilization(w.instanceId);

                if (cpu >= 0.0) {
                    w.cpuUtil = cpu;
                    cpuSum += cpu;
                    cpuMax = Math.max(cpuMax, cpu);
                    cpuCount++;
                }
            }

            double meanCpu = cpuCount > 0 ? cpuSum / cpuCount : -1.0;

            long totalLoad = activeWorkers.stream().mapToLong(w -> w.outstandingLoad.get()).sum();
            long maxLoad = activeWorkers.stream().mapToLong(w -> w.outstandingLoad.get()).max().orElse(0L);
            long totalMem = activeWorkers.stream().mapToLong(w -> w.outstandingMemory.get()).sum();
            long maxMem = activeWorkers.stream().mapToLong(w -> w.outstandingMemory.get()).max().orElse(0L);

            long clusterCap = (long) activeWorkers.size() * WORKER_LOAD_CAPACITY_UNITS;
            long memoryCap = (long) activeWorkers.size() * EC2_MEMORY_ADMISSION_CAPACITY;

            double avgUtil = clusterCap > 0 ? (double) totalLoad / clusterCap : 0.0;
            double maxUtil = (double) maxLoad / WORKER_LOAD_CAPACITY_UNITS;
            double avgMemU = memoryCap > 0 ? (double) totalMem / memoryCap : 0.0;
            double maxMemU = (double) maxMem / EC2_MEMORY_ADMISSION_CAPACITY;

            boolean predictedHigh = avgUtil > SCALE_OUT_AVG_THRESHOLD || maxUtil > SCALE_OUT_MAX_THRESHOLD
                    || avgMemU > SCALE_OUT_AVG_THRESHOLD || maxMemU > SCALE_OUT_MAX_THRESHOLD;
            boolean predictedLow = avgUtil < SCALE_IN_AVG_THRESHOLD && maxUtil < SCALE_IN_MAX_THRESHOLD
                    && avgMemU < SCALE_IN_AVG_THRESHOLD && maxMemU < SCALE_IN_MAX_THRESHOLD;

            boolean cpuHigh = false;
            boolean cpuLow = false;
            if (meanCpu >= 0.0) {
                cpuHigh = meanCpu > CW_CPU_SCALE_OUT_THRESHOLD;
                cpuLow = meanCpu < CW_CPU_SCALE_IN_THRESHOLD;
            }

            if (cpuHigh || predictedHigh) {
                highLoadCycles++;
            } else {
                highLoadCycles = 0;
            }

            if (cpuLow && predictedLow) {
                lowLoadCycles++;
            } else {
                lowLoadCycles = 0;
            }

            long now = System.currentTimeMillis();

            if (highLoadCycles >= SCALE_OUT_REQUIRED_CYCLES) {
                int total = activeWorkers.size() + startingWorkers.size();
                if (total < MAX_WORKERS && (now - lastScaleOutTime) >= SCALE_OUT_COOLDOWN_MS) {
                    launchNewWorker();
                    lastScaleOutTime = now;
                    highLoadCycles = 0;
                    LOG.info("Scale-out: launched new worker (total=" + (total + 1) + ")");
                }
            }

            if (lowLoadCycles >= SCALE_IN_REQUIRED_CYCLES) {
                if (activeWorkers.size() > MIN_WORKERS && (now - lastScaleInTime) >= SCALE_IN_COOLDOWN_MS) {
                    Worker victim = activeWorkers.stream().min(WORKER_ORDER).orElse(null);
                    if (victim != null && victim.compareAndSetState(WorkerState.ACTIVE, WorkerState.DRAINING)) {
                            lastScaleInTime = now;
                            lowLoadCycles = 0;
                            LOG.info("Scale-in: draining worker " + victim.instanceId);

                    }
                }
            }
        }

        private void launchNewWorker() {
            try {
                RunInstancesRequest request = RunInstancesRequest.builder().imageId(IMAGE_ID).instanceType(InstanceType.T3_MICRO).keyName(KEY_NAME).securityGroupIds(SECURITY_GROUP).iamInstanceProfile(IamInstanceProfileSpecification.builder().name(IAM_ROLE).build()).minCount(1).maxCount(1).monitoring(RunInstancesMonitoringEnabled.builder().enabled(true).build()).build();

                RunInstancesResponse resp = ec2.runInstances(request);
                String instanceId = resp.instances().get(0).instanceId();
                Worker w = new Worker(instanceId, null, WorkerState.STARTING);
                WORKERS.add(w);
                LOG.info("Launched new instance: " + instanceId);
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "Failed to launch instance", e);
            }
        }

        private void terminateInstance(String instanceId) {
            try {
                ec2.terminateInstances(TerminateInstancesRequest.builder().instanceIds(instanceId).build());
                LOG.info("Sent termination for " + instanceId);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to terminate " + instanceId, e);
            }
        }

        private String resolveAddress(String instanceId) {
            try {
                DescribeInstancesResponse resp = ec2.describeInstances(DescribeInstancesRequest.builder().instanceIds(instanceId).build());
                for (Reservation r : resp.reservations()) {
                    for (Instance i : r.instances()) {
                        String dns = i.publicDnsName();
                        if (dns != null && !dns.isEmpty()) {
                            return dns;
                        }
                        String ip = i.publicIpAddress();
                        if (ip != null && !ip.isEmpty()) {
                            return ip;
                        }
                    }
                }
            } catch (Exception e) {
                LOG.warning("Could not resolve address for " + instanceId + ": " + e.getMessage());
            }
            return null;
        }

        private double readCpuUtilization(String instanceId) {
            try {
                Dimension dim = Dimension.builder()
                        .name("InstanceId")
                        .value(instanceId)
                        .build();

                GetMetricStatisticsRequest request = GetMetricStatisticsRequest.builder()
                        .namespace("AWS/EC2")
                        .metricName("CPUUtilization")
                        .dimensions(dim)
                        .startTime(Instant.ofEpochMilli(System.currentTimeMillis() - CW_OBS_WINDOW_MS))
                        .endTime(Instant.now())
                        .period(CW_PERIOD_SECONDS)
                        .statistics(Statistic.AVERAGE)
                        .build();

                GetMetricStatisticsResponse response = cloudWatch.getMetricStatistics(request);

                Datapoint latest = null;
                for (Datapoint dp : response.datapoints()) {
                    if (latest == null || dp.timestamp().isAfter(latest.timestamp())) {
                        latest = dp;
                    }
                }

                if (latest != null && latest.average() != null) {
                    return latest.average();
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to read CloudWatch CPU for " + instanceId, e);
            }
            return -1.0;
        }
    }

    /* -------------------------------------------------------------------------
    / Main
    / --------------------------------------------------------------------------*/
    public static void main(String[] args) throws Exception {
        MSS.ensureTablesExist();

        HttpServer server = HttpServer.create(new InetSocketAddress(LB_PORT), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", new ProxyHandler());
        server.createContext("/test", new MyHandler());
        server.start();
        LOG.info("Load balancer listening on port " + LB_PORT);

        Thread scaler = new Thread(new AutoScaler(), "auto-scaler");
        scaler.setDaemon(true);
        scaler.start();
    }

    static class MyHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange t) throws IOException {
            String response = "This was the query: <" + t.getRequestURI().getQuery() + ">";
            t.sendResponseHeaders(200, response.length());
            OutputStream os = t.getResponseBody();
            os.write(response.getBytes());
            os.close();
        }
    }
}
