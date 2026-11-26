package com.naturecloud.javassist.tools;

import com.naturecloud.javassist.Parameters;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

import java.util.Map;
import java.util.UUID;

@DynamoDbBean
public final class Metrics {

    private static final Map<String, long[]> REGION_BOUNDARIES = Map.of(
            "fractals", new long[]{
                    0L, 1_000_000L, 2_500_000L, 5_000_000L, 10_000_000L, 20_000_000L, 40_000_000L, 80_000_000L,
                    160_000_000L, 320_000_000L, 640_000_000L, 1_000_000_000L, 1_500_000_000L, 2_000_000_000L,
                    3_000_000_000L, 4_500_000_000L, 6_000_000_000L, 8_000_000_000L, 10_000_000_000L,
                    15_000_000_000L, 25_000_000_000L, 40_000_000_000L, Long.MAX_VALUE
            },
            "grayScott", new long[]{
                    0L, 100_000L, 250_000L, 500_000L, 1_000_000L, 2_000_000L, 4_000_000L, 8_000_000L, 16_000_000L,
                    32_000_000L, 64_000_000L, 128_000_000L, 256_000_000L, 512_000_000L, 1_000_000_000L, 2_000_000_000L,
                    4_000_000_000L, 6_000_000_000L, 8_000_000_000L, Long.MAX_VALUE
            },
            "dna", new long[]{
                    0L, 10_000L, 50_000L, 200_000L, 1_000_000L, 5_000_000L, 20_000_000L, 50_000_000L, 100_000_000L, 250_000_000L,
                    500_000_000L, 1_000_000_000L, 2_000_000_000L, 4_000_000_000L, 8_000_000_000L, 15_000_000_000L, 25_000_000_000L,
                    40_000_000_000L, 60_000_000_000L, Long.MAX_VALUE
            }
    );


    public String id;
    public String function;
    public Parameters parameters;
    public long instructions;
    public long memoryAccesses;
    public long allocatedMemory;
    public long regionKey;

    public Metrics() {
    }

    public Metrics(String function, Parameters parameters, long instructions, long allocatedMemory) {
        this.id = UUID.randomUUID().toString();
        this.function = function;
        this.parameters = parameters;
        this.instructions = instructions;
        this.memoryAccesses = 0L;
        this.allocatedMemory = allocatedMemory;
        this.regionKey = buildKey(function, parameters);
    }

    @DynamoDbPartitionKey
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getFunction() {
        return function;
    }

    public void setFunction(String function) {
        this.function = function;
    }

    public Parameters getParameters() {
        return parameters;
    }

    public void setParameters(Parameters parameters) {
        this.parameters = parameters;
    }

    public long getInstructions() {
        return instructions;
    }

    public void setInstructions(long instructions) {
        this.instructions = instructions;
    }

    public long getMemoryAccesses() {
        return memoryAccesses;
    }

    public void setMemoryAccesses(long memoryAccesses) {
        this.memoryAccesses = memoryAccesses;
    }

    public long getAllocatedMemory() {
        return allocatedMemory;
    }

    public void setAllocatedMemory(long allocatedMemory) {
        this.allocatedMemory = allocatedMemory;
    }

    public long getRegionKey() {
        return regionKey;
    }

    public void setRegionKey(long regionKey) {
        this.regionKey = regionKey;
    }

    @Override
    public String toString() {
        return String.format("function=%s, parameters=%s, instructions=%s, memoryAccesses=%s", function, parameters.toString(function), instructions, memoryAccesses);
    }

    private static long buildKey(String function, Parameters params) {
        return snapToRegion(function, params);
    }

    public static long snapToRegion(String function, Parameters params) {
        long rawKey = buildRawKey(function, params);
        long[] boundaries = REGION_BOUNDARIES.get(function);
        if (boundaries == null) {
            throw new IllegalArgumentException("No region boundaries defined for function: " + function);
        }

        for (int i = boundaries.length - 1; i >= 0; i--) {
            if (rawKey >= boundaries[i]) {
                return boundaries[i];
            }
        }

        return boundaries[0];
    }

    public static long buildRawKey(String function, Parameters params) {
        switch (function) {
            case "fractals":
                // Time complexity: O(width * height * nEff)
                return (long) params.getWidth() * params.getHeight() * estimateFractalNEff(params.getMaxIterations());
            case "grayScott":
                // Time complexity: O(size^2 * nEff)
                return (long) params.getSize() * params.getSize() * estimateGrayScottNEff(params.getExtinction(), params.getF(), params.getK(), params.getMaxIterations());
            case "dna":
                // Time complexity: O(s1.length * s2.length * minLength)
                long base = (long) params.getSequence1Length() * params.getSequence2Length() * params.getMinLength();
                return params.getStop() ? base : base * 10;
            default:
                throw new IllegalArgumentException("Unsupported function: " + function);
        }
    }


    public static long estimateFractalNEff(int maxIterations) {
        if (maxIterations <= 1) {
            return 160;
        }
        if (maxIterations <= 10) {
            return 460;
        }
        if (maxIterations <= 25) {
            return 740;
        }
        if (maxIterations <= 50) {
            return 1090;
        }
        if (maxIterations <= 100) {
            return 1440;
        }
        if (maxIterations <= 250) {
            return 1650;
        }
        return 1660;
    }

    public static long estimateGrayScottNEff(boolean stopOnExtinction, double f, double k, int maxIterations) {
        if (!stopOnExtinction) {
            return maxIterations;
        }
        return estimateGrayScottNEff(f, k, maxIterations);
    }

    public static long estimateGrayScottNEff(double f, double k, int maxIterations) {

        if(k >= 0.07) {         // Very high kill rate usually leads to quick extinction.
            return Math.min(maxIterations, 150);
        }

        if( f >= 0.15) {          // Very high feed rate usually also dies quickly.
            return Math.min(maxIterations, 50);
        }

        if (f >= 0.10 && k >=0.04) {
            return Math.min(maxIterations, 80);
        }

        if (f >= 0.06 && k >=0.06) {        // High-ish feed and kill tends to be short/medium.
            return Math.min(maxIterations, 120);
        }

        return maxIterations;
    }

}
