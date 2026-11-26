package com.naturecloud.javassist.mss;

import com.naturecloud.javassist.MetricsRange;
import com.naturecloud.javassist.tools.Metrics;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbAsyncTable;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedAsyncClient;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;

import java.util.concurrent.CompletionException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MSS {

    private static final Logger LOG = Logger.getLogger(MSS.class.getName());

    private static final String REQUESTS_TABLE = "naturecloud-requests";
    private static final String REGIONS_TABLE = "naturecloud-regions";

    private static final DynamoDbEnhancedAsyncClient ENHANCED = DynamoDbEnhancedAsyncClient.create();

    private static final DynamoDbAsyncTable<Metrics> REQUESTS_DDB = ENHANCED.table(REQUESTS_TABLE, TableSchema.fromBean(Metrics.class));

    private static final DynamoDbAsyncTable<MetricsRange> REGIONS_DDB = ENHANCED.table(REGIONS_TABLE, TableSchema.fromBean(MetricsRange.class));

    public static void ensureTablesExist() {
        createTableIfNotExists(REQUESTS_DDB, REQUESTS_TABLE);
        createTableIfNotExists(REGIONS_DDB, REGIONS_TABLE);
    }

    private static <T> void createTableIfNotExists(DynamoDbAsyncTable<T> table, String tableName) {
        try {
            table.createTable(b -> b.provisionedThroughput(pt -> pt.readCapacityUnits(5L).writeCapacityUnits(5L))).join();
            ENHANCED.dynamoDbAsyncClient().waiter()
                    .waitUntilTableExists(w -> w.tableName(tableName))
                    .join();

            LOG.info("Created table: " + tableName);
        } catch (CompletionException e) {
            if (e.getCause() instanceof ResourceInUseException) {
                LOG.info("Table already exists: " + tableName);
            } else {
                throw e;
            }
        }
    }

    public static void saveRequest(Metrics metrics) {
        REQUESTS_DDB.putItem(metrics);
        LOG.log(Level.INFO, "saveRequest: {0}", metrics);
        updateRegion(metrics);
    }

    private static void updateRegion(Metrics metrics) {
        MetricsRange range = getRegion(metrics.getFunction(), metrics.getRegionKey());
        System.out.println("Updating region: " + range);
        if(range == null) range = new MetricsRange(metrics.getFunction(), metrics.regionKey, 0, 0,0,0);
        long totalRequests = range.getTotalRequests();
        long avgInstructions = average(range.getAvgInstructions(), totalRequests, metrics.getInstructions());
        long avgMemoryAccesses = average(range.getAvgMemoryAccesses(), totalRequests, metrics.getMemoryAccesses());
        long avgMemoryAllocated = average(range.getAvgMemoryAllocated(), totalRequests, metrics.getAllocatedMemory());
        range = new MetricsRange(metrics.getFunction(), metrics.getRegionKey(), avgInstructions, avgMemoryAccesses, avgMemoryAllocated, totalRequests + 1);
        REGIONS_DDB.putItem(range);
    }

    public static MetricsRange getRegion(String function, long regionKey) {
        Key key = Key.builder().partitionValue(function).sortValue(regionKey).build();
        return REGIONS_DDB.getItem(r -> r.key(key)).join();
    }

    private static long average(long average, long totalRequests, long add) {
        long sum = average * totalRequests;
        sum += add;
        return sum / (totalRequests + 1);
    }
}