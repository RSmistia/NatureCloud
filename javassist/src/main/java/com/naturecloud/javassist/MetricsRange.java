package com.naturecloud.javassist;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
public class MetricsRange {
    String function;
    long regionKey;
    long avgInstructions;
    long avgMemoryAccesses;
    long avgMemoryAllocated;
    long totalRequests;


    public MetricsRange() {
    }

    public MetricsRange(String function, long regionKey, long avgInstructions, long avgMemoryAccesses, long avgMemoryAllocated, long totalRequests) {
        this.function = function;
        this.regionKey = regionKey;
        this.avgInstructions = avgInstructions;
        this.avgMemoryAccesses = avgMemoryAccesses;
        this.avgMemoryAllocated = avgMemoryAllocated;
        this.totalRequests = totalRequests;
    }

    @DynamoDbPartitionKey
    public String getFunction() {
        return function;
    }

    public void setFunction(String function) {
        this.function = function;
    }

    @DynamoDbSortKey
    public long getRegionKey() {
        return regionKey;
    }

    public void setRegionKey(long regionKey) {
        this.regionKey = regionKey;
    }

    public long getAvgInstructions() {
        return avgInstructions;
    }

    public void setAvgInstructions(long avgInstructions) {
        this.avgInstructions = avgInstructions;
    }

    public long getAvgMemoryAccesses() {
        return avgMemoryAccesses;
    }

    public void setAvgMemoryAccesses(long avgMemoryAccesses) {
        this.avgMemoryAccesses = avgMemoryAccesses;
    }

    public long getAvgMemoryAllocated() {
        return avgMemoryAllocated;
    }

    public void setAvgMemoryAllocated(long avgMemoryAllocated) {
        this.avgMemoryAllocated = avgMemoryAllocated;
    }

    public long getTotalRequests() {
        return totalRequests;
    }

    public void setTotalRequests(long totalRequests) {
        this.totalRequests = totalRequests;
    }
}
