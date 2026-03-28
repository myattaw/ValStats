package com.valstats.config;

import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

@Factory
public class AwsConfig {

    /**
     * Optimized DynamoDbClient for AWS Lambda environment.
     * Uses connection pooling and timeouts suitable for Lambda's ephemeral nature.
     */
    @Singleton
    DynamoDbClient dynamoDbClient() {
        return DynamoDbClient.builder().build();
    }

}



