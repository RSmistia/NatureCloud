package com.naturecloud.javassist;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;

@DynamoDbBean
public class Parameters {
    private int width;
    private int height;
    private int maxIterations;

    public Parameters() {}

    public Parameters(int width, int height, int maxIterations) {
        this.width = width;
        this.height = height;
        this.maxIterations = maxIterations;
    }


    private int size;
    private double f,k;
    boolean extinction;
    String seedMode;

    public Parameters(int size, int maxIterations, double f, double k, boolean extinction, String seedMode) {
        this.size = size;
        this.maxIterations = maxIterations;
        this.f = f;
        this.k = k;
        this.extinction = extinction;
        this.seedMode = seedMode;
    }

    String sequence1;
    String sequence2;
    int minLength;
    boolean stop;
    public Parameters(String sequence1, String sequence2, int minLength, boolean stop) {
        this.sequence1 = sequence1;
        this.sequence2 = sequence2;
        this.minLength = minLength;
        this.stop = stop;
    }

    public String toString(String function) {
        switch (function) {
            case "fractals": return String.format("width=%s, height=%s, maxIterations=%s", width, height, maxIterations);
            case "grayScott": return String.format("size=%s, maxIterations=%s, f=%s, k=%s, stopOnExtinction=%s, seedMode=%s", size, maxIterations, f, k, extinction, seedMode);
            case "dna": return String.format("sequence1=%s, sequence2=%s, minLength=%s, stop=%s", sequence1, sequence2, minLength, stop);
        }
        return null;
    }

    public int getWidth() {
        return width;
    }

    public void setWidth(int width) {
        this.width = width;
    }

    public int getHeight() {
        return height;
    }

    public void setHeight(int height) {
        this.height = height;
    }

    public int getMaxIterations() {
        return maxIterations;
    }
    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }

    public double getF() {
        return f;
    }

    public void setF(double f) {
        this.f = f;
    }

    public double getK() {
        return k;
    }

    public void setK(double k) {
        this.k = k;
    }

    public boolean getExtinction() {
        return extinction;
    }

    public void setExtinction(boolean extinction) {
        this.extinction = extinction;
    }

    public String getSeedMode() {
        return seedMode;
    }

    public void setSeedMode(String seedMode) {
        this.seedMode = seedMode;
    }

    public String getSequence1() {
        return sequence1;
    }

    public void setSequence1(String sequence1) {
        this.sequence1 = sequence1;
    }

    public int getSequence1Length() {
        return sequence1 != null ? sequence1.length() : 0;
    }

    public String getSequence2() {
        return sequence2;
    }

    public void setSequence2(String sequence2) {
        this.sequence2 = sequence2;
    }

    public int getSequence2Length() {
        return sequence2 != null ? sequence2.length() : 0;
    }

    public int getMinLength() {
        return minLength;
    }

    public void setMinLength(int minLength) {
        this.minLength = minLength;
    }

    public boolean getStop() {
        return stop;
    }

    public void setStop(boolean stop) {
        this.stop = stop;
    }
}
