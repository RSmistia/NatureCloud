package com.naturecloud.javassist.tools;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.sun.management.ThreadMXBean;

import javassist.CannotCompileException;
import javassist.CtBehavior;
import com.naturecloud.javassist.Parameters;
import com.naturecloud.javassist.mss.MSS;

public class ICount extends AbstractJavassistTool {

    private static final Map<Long, Metrics> map = new ConcurrentHashMap<>();

    private static final List<String> classesToMeasure = new ArrayList<>(Arrays.asList(
            "com.naturecloud.dna.Dna",
            "com.naturecloud.dna.Dna$Match",
            "com.naturecloud.grayscott.GrayScott",
            "com.naturecloud.fractals.JuliaFractal"));

    public ICount(List<String> packageNameList, String writeDestination) {
        super(packageNameList, writeDestination);
    }

    public static void incMetrics(int length, int count, long threadId) {
        Metrics metrics = map.get(threadId);
        metrics.instructions += length;
        metrics.memoryAccesses += count;
        map.put(threadId, metrics);
    }

    public static long recordAllocBefore() {
        ThreadMXBean tmx = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long threadId = Thread.currentThread().getId();
        return tmx.getThreadAllocatedBytes(threadId);
    }

    public static void recordAllocAfter(long threadId) {
        ThreadMXBean tmx = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long allocAfter = tmx.getThreadAllocatedBytes(threadId);
        long allocFinal = allocAfter - map.get(threadId).allocatedMemory;
        map.get(threadId).allocatedMemory = allocFinal;
        System.out.println("Allocated bytes: " + allocFinal);
    }

    public static void startMeasure(long threadId, String function, int width, int height, int maxIterations) {
        Parameters parameters = new Parameters(width, height, maxIterations);
        map.put(threadId, new Metrics(function, parameters, 0L, recordAllocBefore()));
    }

    public static void startMeasure(long threadId, String function, int size, int maxIterations, double f, double k,
            boolean extinction,
            String seedMode) {
        Parameters parameters = new Parameters(size, maxIterations, f, k, extinction, seedMode);
        map.put(threadId, new Metrics(function, parameters, 0L, recordAllocBefore()));
    }

    public static void startMeasure(long threadId, String function, String sequence1, String sequence2, int minLength,
            boolean stop) {
        Parameters parameters = new Parameters(sequence1, sequence2, minLength, stop);
        map.put(threadId, new Metrics(function, parameters, 0L, recordAllocBefore()));
    }

    public static void stopMeasuring(long threadId) {
        Metrics metrics = map.remove(threadId);
        persistMetrics(metrics);
    }

    @Override
    protected void transform(CtBehavior behavior) throws Exception {
        super.transform(behavior);

        if (behavior.getName().equals("handleWorkload")) {
            switch (behavior.getDeclaringClass().getName()) {
                case "com.naturecloud.dna.DnaHandler":
                    behavior.insertBefore(ICount.class.getName()
                            + ".startMeasure(Thread.currentThread().getId(), \"dna\", seq1Param, seq2Param, minLength, stopOnFirst);");
                    break;
                case "com.naturecloud.grayscott.GrayScottHandler":
                    behavior.insertBefore(ICount.class.getName()
                            + ".startMeasure(Thread.currentThread().getId(), \"grayScott\", size, maxIterations, F, K, stopOnExtinction, seedMode);");
                    break;
                case "com.naturecloud.fractals.FractalsHandler":
                    behavior.insertBefore(ICount.class.getName()
                            + ".startMeasure(Thread.currentThread().getId(), \"fractals\", width, height, iterations);");
                    break;
            }
            behavior.insertAfter(ICount.class.getName() + ".recordAllocAfter(Thread.currentThread().getId());");
            behavior.insertAfter(ICount.class.getName() + ".stopMeasuring(Thread.currentThread().getId());");
        }
    }

    @Override
    protected void transform(BasicBlock block) throws CannotCompileException {
        if (classesToMeasure.contains(block.behavior.getDeclaringClass().getName())) {
            block.behavior.insertAt(block.line, String.format("%s.incMetrics(%s, %s, Thread.currentThread().getId());",
                    ICount.class.getName(), block.getLength(), block.getMemoryCount()));
        }
    }

    private static void persistMetrics(Metrics metrics) {
        if (metrics == null) {
            return;
        }

        try {
            MSS.saveRequest(metrics);
        } catch (RuntimeException e) {
            System.err.println(e.getMessage());
        }
    }
}
