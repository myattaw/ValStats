package com.valstats.infrastructure;

import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

import java.util.Map;

class ValStatsInfrastructureTest {
    private static final Environment TEST_ENVIRONMENT = Environment.builder()
            .account("123456789012")
            .region("us-east-1")
            .build();

    @Test
    void statefulStackDefinesProtectedOnDemandTableAndIndex() {
        App app = new App();
        ValStatsStatefulStack stack = new ValStatsStatefulStack(app, "StatefulTest", "test", TEST_ENVIRONMENT);
        Template template = Template.fromStack(stack);

        template.hasResourceProperties("AWS::DynamoDB::Table", Map.of(
                "BillingMode", "PAY_PER_REQUEST",
                "DeletionProtectionEnabled", true,
                "KeySchema", Match.arrayWith(java.util.List.of(
                        Map.of("AttributeName", "PK", "KeyType", "HASH"),
                        Map.of("AttributeName", "SK", "KeyType", "RANGE")
                )),
                "GlobalSecondaryIndexes", Match.arrayWith(java.util.List.of(
                        Match.objectLike(Map.of("IndexName", "GSI1"))
                ))
        ));
    }

    @Test
    void applicationStackDefinesTwoWorkerQueuesAndTwoDeadLetterQueues() {
        App app = new App();
        ValStatsApplicationStack stack = new ValStatsApplicationStack(app, "ApplicationTest", "test", TEST_ENVIRONMENT);
        Template template = Template.fromStack(stack);

        template.resourceCountIs("AWS::SQS::Queue", 4);
        template.resourceCountIs("AWS::CloudWatch::Alarm", 4);
        template.hasResourceProperties("AWS::SQS::Queue", Match.objectLike(Map.of(
                "QueueName", "valstats-test-refresh",
                "VisibilityTimeout", 300
        )));
    }
}
