package com.valstats.service;

import io.micronaut.context.event.StartupEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.Map;

@Singleton
public class DynamoDbService {

    private static final Logger LOG = LoggerFactory.getLogger(DynamoDbService.class);

    private final DynamoDbClient dbClient;
    private final String tableName = "valstats";

    public DynamoDbService(DynamoDbClient dbClient) {
        this.dbClient = dbClient;
    }

    /**
     * Runs once when the application starts.
     * Verifies DynamoDB connectivity, region, IAM permissions, and table existence.
     */
    @EventListener
    void onStartup(StartupEvent event) {
        try {
            dbClient.describeTable(
                    DescribeTableRequest.builder()
                            .tableName(tableName)
                            .build()
            );

            LOG.info("Successfully connected to DynamoDB table: {}", tableName);

        } catch (ResourceNotFoundException e) {
            LOG.error("DynamoDB table '{}' does not exist", tableName, e);
        } catch (DynamoDbException e) {
            LOG.error("Failed to connect to DynamoDB", e);
        }
    }

    public void putItem(Map<String, AttributeValue> item) {
        PutItemRequest request = PutItemRequest.builder()
                .tableName(tableName)
                .item(item)
                .build();

        dbClient.putItem(request);
    }

    public QueryResponse queryByPk(String pk) {
        return dbClient.query(QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("PK = :pk")
                .expressionAttributeValues(Map.of(
                        ":pk", AttributeValue.fromS(pk)
                ))
                .build());
    }

}
