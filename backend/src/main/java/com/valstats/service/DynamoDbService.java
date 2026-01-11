package com.valstats.service;

import jakarta.inject.Singleton;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.util.Map;

@Singleton
public class DynamoDbService {

    private final DynamoDbClient dbClient;
    private final String tableName = "valstats";

    public DynamoDbService(DynamoDbClient dbClient) {
        this.dbClient = dbClient;
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
