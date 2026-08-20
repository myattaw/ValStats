package com.valstats.infrastructure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;

class ValStatsInfrastructureTest {
    @TempDir
    Path temporaryDirectory;
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
    void applicationStackDefinesQueuesLambdasAndHttpApi() throws Exception {
        App app = new App();
        ValStatsStatefulStack stateful = new ValStatsStatefulStack(app, "StatefulDependency", "test", TEST_ENVIRONMENT);
        Path apiArtifact = Files.createFile(temporaryDirectory.resolve("api.jar"));
        Path syncArtifact = Files.createFile(temporaryDirectory.resolve("sync.jar"));
        ValStatsApplicationStack stack = new ValStatsApplicationStack(
                app, "ApplicationTest", "test", TEST_ENVIRONMENT,
                stateful.getDataTable(), stateful.getHenrikApiSecret(),
                LambdaDeploymentMode.JVM,
                apiArtifact.toString(), syncArtifact.toString());
        Template template = Template.fromStack(stack);

        template.resourceCountIs("AWS::SQS::Queue", 4);
        template.resourceCountIs("AWS::CloudWatch::Alarm", 4);
        template.resourceCountIs("AWS::Lambda::Function", 2);
        template.resourceCountIs("AWS::ApiGatewayV2::Api", 1);
        template.resourceCountIs("AWS::Lambda::EventSourceMapping", 1);
        template.hasResourceProperties("AWS::SQS::Queue", Match.objectLike(Map.of(
                "QueueName", "valstats-test-refresh",
                "VisibilityTimeout", 300
        )));
        template.hasResourceProperties("AWS::Lambda::Function", Match.objectLike(Map.of(
                "Runtime", "java21",
                "Architectures", java.util.List.of("arm64")
        )));
    }

    @Test
    void nativeApplicationStackUsesCustomRuntimeWithoutReplacingJvmResources() throws Exception {
        App app = new App();
        ValStatsStatefulStack stateful = new ValStatsStatefulStack(
                app, "NativeStatefulDependency", "test", TEST_ENVIRONMENT);
        Path apiArtifact = Files.createFile(temporaryDirectory.resolve("api-native.zip"));
        Path syncArtifact = Files.createFile(temporaryDirectory.resolve("sync-native.zip"));
        ValStatsApplicationStack stack = new ValStatsApplicationStack(
                app, "NativeApplicationTest", "test", TEST_ENVIRONMENT,
                stateful.getDataTable(), stateful.getHenrikApiSecret(),
                LambdaDeploymentMode.NATIVE,
                apiArtifact.toString(), syncArtifact.toString());
        Template template = Template.fromStack(stack);

        template.resourceCountIs("AWS::Lambda::Function", 2);
        template.hasResourceProperties("AWS::Lambda::Function", Match.objectLike(Map.of(
                "Runtime", "provided.al2023",
                "Handler", "bootstrap",
                "Architectures", java.util.List.of("x86_64")
        )));
        template.hasResourceProperties("AWS::SQS::Queue", Match.objectLike(Map.of(
                "QueueName", "valstats-test-native-refresh",
                "VisibilityTimeout", 300
        )));
    }
}
