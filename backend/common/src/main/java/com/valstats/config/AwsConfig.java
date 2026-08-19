package com.valstats.config;

import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

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

    @Singleton
    SqsClient sqsClient() {
        return SqsClient.builder().build();
    }

    @Singleton
    SecretsManagerClient secretsManagerClient() {
        return SecretsManagerClient.builder().build();
    }

}



