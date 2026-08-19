package com.valstats.infrastructure;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.cloudwatch.Alarm;
import software.amazon.awscdk.services.cloudwatch.ComparisonOperator;
import software.amazon.awscdk.services.cloudwatch.TreatMissingData;
import software.amazon.awscdk.services.apigatewayv2.CfnApi;
import software.amazon.awscdk.services.apigatewayv2.CfnIntegration;
import software.amazon.awscdk.services.apigatewayv2.CfnRoute;
import software.amazon.awscdk.services.apigatewayv2.CfnStage;
import software.amazon.awscdk.services.dynamodb.ITable;
import software.amazon.awscdk.services.lambda.Architecture;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.lambda.Tracing;
import software.amazon.awscdk.services.lambda.CfnPermission;
import software.amazon.awscdk.services.lambda.eventsources.SqsEventSource;
import software.amazon.awscdk.services.logs.RetentionDays;
import software.amazon.awscdk.services.logs.LogGroup;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.services.secretsmanager.ISecret;
import software.amazon.awscdk.services.sqs.DeadLetterQueue;
import software.amazon.awscdk.services.sqs.Queue;
import software.amazon.awscdk.services.sqs.QueueEncryption;
import software.constructs.Construct;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class ValStatsApplicationStack extends Stack {
    public ValStatsApplicationStack(
            Construct scope,
            String id,
            String environmentName,
            Environment awsEnvironment,
            ITable dataTable,
            ISecret henrikApiSecret,
            String apiArtifactPath,
            String syncArtifactPath
    ) {
        super(scope, id, StackProps.builder()
                .env(awsEnvironment)
                .description("ValStats refresh messaging resources (" + environmentName + ")")
                .build());

        Queue refreshDeadLetterQueue = deadLetterQueue("RefreshDeadLetterQueue", "valstats-" + environmentName + "-refresh-dlq");
        Queue nameHistoryDeadLetterQueue = deadLetterQueue(
                "NameHistoryDeadLetterQueue",
                "valstats-" + environmentName + "-name-history-dlq"
        );

        Queue refreshQueue = workerQueue(
                "RefreshQueue",
                "valstats-" + environmentName + "-refresh",
                refreshDeadLetterQueue
        );
        Queue nameHistoryQueue = workerQueue(
                "NameHistoryQueue",
                "valstats-" + environmentName + "-name-history",
                nameHistoryDeadLetterQueue
        );

        addQueueAlarms("Refresh", refreshQueue, refreshDeadLetterQueue);
        addQueueAlarms("NameHistory", nameHistoryQueue, nameHistoryDeadLetterQueue);

        Map<String, String> sharedEnvironment = Map.of(
                "DYNAMODB_TABLE_NAME", dataTable.getTableName(),
                "HENRIK_API_SECRET_ARN", henrikApiSecret.getSecretArn()
        );

        String apiFunctionName = "valstats-" + environmentName + "-api";
        String syncFunctionName = "valstats-" + environmentName + "-match-sync";
        LogGroup apiLogGroup = functionLogGroup("ApiLogGroup", apiFunctionName);
        LogGroup syncLogGroup = functionLogGroup("SyncLogGroup", syncFunctionName);

        Function apiFunction = Function.Builder.create(this, "ApiFunction")
                .functionName(apiFunctionName)
                .description("Cached ValStats HTTP API")
                .runtime(Runtime.JAVA_21)
                .architecture(Architecture.ARM_64)
                .handler("io.micronaut.function.aws.proxy.payload2.APIGatewayV2HTTPEventFunction")
                .code(Code.fromAsset(Path.of(apiArtifactPath).toAbsolutePath().normalize().toString()))
                .memorySize(1024)
                .timeout(Duration.seconds(30))
                .tracing(Tracing.ACTIVE)
                .logGroup(apiLogGroup)
                .environment(mergeEnvironment(sharedEnvironment, Map.of(
                        "REFRESH_QUEUE_URL", refreshQueue.getQueueUrl())))
                .build();

        Function syncFunction = Function.Builder.create(this, "SyncFunction")
                .functionName(syncFunctionName)
                .description("Processes queued ValStats match refresh jobs")
                .runtime(Runtime.JAVA_21)
                .architecture(Architecture.ARM_64)
                .handler("com.valstats.queue.RefreshQueueHandler")
                .code(Code.fromAsset(Path.of(syncArtifactPath).toAbsolutePath().normalize().toString()))
                .memorySize(1536)
                .timeout(Duration.minutes(4))
                .tracing(Tracing.ACTIVE)
                .logGroup(syncLogGroup)
                .environment(sharedEnvironment)
                .build();

        dataTable.grantReadWriteData(apiFunction);
        dataTable.grantReadWriteData(syncFunction);
        refreshQueue.grantSendMessages(apiFunction);
        henrikApiSecret.grantRead(apiFunction);
        henrikApiSecret.grantRead(syncFunction);
        syncFunction.addEventSource(SqsEventSource.Builder.create(refreshQueue)
                .batchSize(1)
                .maxConcurrency(2)
                .build());

        CfnApi httpApi = CfnApi.Builder.create(this, "HttpApi")
                .name("valstats-" + environmentName + "-api")
                .protocolType("HTTP")
                .corsConfiguration(CfnApi.CorsProperty.builder()
                        .allowHeaders(List.of("content-type"))
                        .allowMethods(List.of("GET", "POST", "OPTIONS"))
                        .allowOrigins(List.of("http://localhost:5173", "http://localhost:3000"))
                        .maxAge(86400)
                        .build())
                .build();

        CfnIntegration apiIntegration = CfnIntegration.Builder.create(this, "ApiIntegration")
                .apiId(httpApi.getRef())
                .integrationType("AWS_PROXY")
                .integrationUri(apiFunction.getFunctionArn())
                .payloadFormatVersion("2.0")
                .build();

        CfnRoute.Builder.create(this, "DefaultRoute")
                .apiId(httpApi.getRef())
                .routeKey("$default")
                .target("integrations/" + apiIntegration.getRef())
                .build();

        CfnStage.Builder.create(this, "DefaultStage")
                .apiId(httpApi.getRef())
                .stageName("$default")
                .autoDeploy(true)
                .build();

        CfnPermission.Builder.create(this, "ApiInvokePermission")
                .action("lambda:InvokeFunction")
                .functionName(apiFunction.getFunctionName())
                .principal("apigateway.amazonaws.com")
                .sourceArn("arn:" + getPartition() + ":execute-api:" + getRegion() + ":"
                        + getAccount() + ":" + httpApi.getRef() + "/*")
                .build();

        output("RefreshQueueUrl", refreshQueue.getQueueUrl(), "ValStats-" + environmentName + "-RefreshQueueUrl");
        output("RefreshQueueArn", refreshQueue.getQueueArn(), "ValStats-" + environmentName + "-RefreshQueueArn");
        output("NameHistoryQueueUrl", nameHistoryQueue.getQueueUrl(), "ValStats-" + environmentName + "-NameHistoryQueueUrl");
        output("ApiUrl", "https://" + httpApi.getRef() + ".execute-api." + getRegion()
                + ".amazonaws.com", "ValStats-" + environmentName + "-ApiUrl");
    }

    private Queue deadLetterQueue(String id, String queueName) {
        return Queue.Builder.create(this, id)
                .queueName(queueName)
                .encryption(QueueEncryption.SQS_MANAGED)
                .retentionPeriod(Duration.days(14))
                .build();
    }

    private Queue workerQueue(String id, String queueName, Queue deadLetterQueue) {
        return Queue.Builder.create(this, id)
                .queueName(queueName)
                .encryption(QueueEncryption.SQS_MANAGED)
                .retentionPeriod(Duration.days(4))
                .visibilityTimeout(Duration.minutes(5))
                .deadLetterQueue(DeadLetterQueue.builder()
                        .queue(deadLetterQueue)
                        .maxReceiveCount(3)
                        .build())
                .build();
    }

    private void addQueueAlarms(String prefix, Queue queue, Queue deadLetterQueue) {
        Alarm.Builder.create(this, prefix + "QueueAgeAlarm")
                .alarmDescription(prefix + " jobs have waited longer than five minutes")
                .metric(queue.metricApproximateAgeOfOldestMessage())
                .threshold(300)
                .evaluationPeriods(2)
                .comparisonOperator(ComparisonOperator.GREATER_THAN_THRESHOLD)
                .treatMissingData(TreatMissingData.NOT_BREACHING)
                .build();

        Alarm.Builder.create(this, prefix + "DeadLetterAlarm")
                .alarmDescription(prefix + " jobs are present in the dead-letter queue")
                .metric(deadLetterQueue.metricApproximateNumberOfMessagesVisible())
                .threshold(1)
                .evaluationPeriods(1)
                .comparisonOperator(ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD)
                .treatMissingData(TreatMissingData.NOT_BREACHING)
                .build();
    }

    private void output(String id, String value, String exportName) {
        CfnOutput.Builder.create(this, id)
                .value(value)
                .exportName(exportName)
                .build();
    }

    private Map<String, String> mergeEnvironment(Map<String, String> base, Map<String, String> additions) {
        java.util.HashMap<String, String> merged = new java.util.HashMap<>(base);
        merged.putAll(additions);
        return Map.copyOf(merged);
    }

    private LogGroup functionLogGroup(String id, String functionName) {
        return LogGroup.Builder.create(this, id)
                .logGroupName("/aws/lambda/" + functionName)
                .retention(RetentionDays.ONE_WEEK)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();
    }
}
