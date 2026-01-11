package com.valstats.config;


import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Factory
public class AwsConfig {

    @Singleton
    DynamoDbClient dynamoDbClient() {
        return DynamoDbClient.create();
    }

}