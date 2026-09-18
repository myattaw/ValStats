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
import software.amazon.awscdk.services.lambda.CfnFunction;
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
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Effect;
import software.constructs.Construct;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class ValStatsApplicationStack extends Stack {
    private static final double API_RATE_LIMIT_PER_SECOND = 50.0;
    private static final int API_BURST_LIMIT = 100;

    public ValStatsApplicationStack(
            Construct scope,
            String id,
            String environmentName,
            Environment awsEnvironment,
            ITable dataTable,
            ISecret henrikApiSecret,
            LambdaDeploymentMode deploymentMode,
            String apiArtifactPath,
            String syncArtifactPath
    ) {
        super(scope, id, StackProps.builder()
                .env(awsEnvironment)
                .description("ValStats refresh messaging resources (" + environmentName + ", "
                        + (deploymentMode.isNative() ? "GraalVM native" : "Java 21 JVM") + ")")
                .build());

        String deploymentName = environmentName + deploymentMode.resourceSuffix();

        Queue refreshDeadLetterQueue = deadLetterQueue("RefreshDeadLetterQueue", "valstats-" + deploymentName + "-refresh-dlq");
        Queue nameHistoryDeadLetterQueue = deadLetterQueue(
                "NameHistoryDeadLetterQueue",
                "valstats-" + deploymentName + "-name-history-dlq"
        );

        Queue refreshQueue = workerQueue(
                "RefreshQueue",
                "valstats-" + deploymentName + "-refresh",
                refreshDeadLetterQueue
        );
        Queue nameHistoryQueue = workerQueue(
                "NameHistoryQueue",
                "valstats-" + deploymentName + "-name-history",
                nameHistoryDeadLetterQueue
        );

        addQueueAlarms("Refresh", refreshQueue, refreshDeadLetterQueue);
        addQueueAlarms("NameHistory", nameHistoryQueue, nameHistoryDeadLetterQueue);

        Map<String, String> sharedEnvironment = Map.of(
                "DYNAMODB_TABLE_NAME", dataTable.getTableName(),
                "HENRIK_API_SECRET_ARN", henrikApiSecret.getSecretArn()
        );

        String apiFunctionName = "valstats-" + deploymentName + "-api";
        String syncFunctionName = "valstats-" + deploymentName + "-match-sync";
        String adminEmail = System.getenv().getOrDefault("ADMIN_EMAIL", "");
        String adminFromEmail = System.getenv().getOrDefault("ADMIN_FROM_EMAIL", "");
        LogGroup apiLogGroup = functionLogGroup("ApiLogGroup", apiFunctionName);
        LogGroup syncLogGroup = functionLogGroup("SyncLogGroup", syncFunctionName);

        Function apiFunction = Function.Builder.create(this, "ApiFunction")
                .functionName(apiFunctionName)
                .description("Cached ValStats HTTP API")
                .runtime(deploymentMode.runtime())
                .architecture(deploymentMode.architecture())
                .handler(deploymentMode.apiHandler())
                .code(Code.fromAsset(Path.of(apiArtifactPath).toAbsolutePath().normalize().toString()))
                .memorySize(1024)
                .timeout(Duration.seconds(30))
                .tracing(Tracing.ACTIVE)
                .logGroup(apiLogGroup)
                .environment(mergeEnvironment(sharedEnvironment, Map.of(
                        "REFRESH_QUEUE_URL", refreshQueue.getQueueUrl(),
                        "HISTORY_QUEUE_URL", nameHistoryQueue.getQueueUrl(),
                        "ADMIN_EMAIL", adminEmail,
                        "ADMIN_FROM_EMAIL", adminFromEmail)))
                .build();

        Function syncFunction = Function.Builder.create(this, "SyncFunction")
                .functionName(syncFunctionName)
                .description("Processes queued ValStats match refresh jobs")
                .runtime(deploymentMode.runtime())
                .architecture(deploymentMode.architecture())
                .handler(deploymentMode.syncHandler())
                .code(Code.fromAsset(Path.of(syncArtifactPath).toAbsolutePath().normalize().toString()))
                .memorySize(1536)
                .timeout(Duration.minutes(4))
                .tracing(Tracing.ACTIVE)
                .logGroup(syncLogGroup)
                .environment(mergeEnvironment(sharedEnvironment, Map.of(
                        "REFRESH_QUEUE_URL", refreshQueue.getQueueUrl(),
                        "HISTORY_QUEUE_URL", nameHistoryQueue.getQueueUrl())))
                .build();

        // Backfills intentionally continue by publishing a bounded next-page job
        // to SQS. AWS otherwise treats a history longer than ~16 worker hops as
        // an accidental recursive loop. Keep this opt-out scoped to the worker;
        // page limits, queue concurrency, retries, and DLQs remain the guardrails.
        CfnFunction syncCfnFunction = (CfnFunction) syncFunction.getNode().getDefaultChild();
        syncCfnFunction.setRecursiveLoop("Allow");

        dataTable.grantReadWriteData(apiFunction);
        dataTable.grantReadWriteData(syncFunction);
        refreshQueue.grantSendMessages(apiFunction);
        nameHistoryQueue.grantSendMessages(apiFunction);
        refreshQueue.grantConsumeMessages(apiFunction);
        nameHistoryQueue.grantConsumeMessages(apiFunction);
        apiFunction.addToRolePolicy(PolicyStatement.Builder.create()
                .effect(Effect.ALLOW)
                .actions(List.of("ses:SendEmail"))
                .resources(sesIdentityResources(adminFromEmail, adminEmail))
                .build());
        refreshQueue.grantSendMessages(syncFunction);
        nameHistoryQueue.grantSendMessages(syncFunction);
        henrikApiSecret.grantRead(apiFunction);
        henrikApiSecret.grantRead(syncFunction);
        syncFunction.addEventSource(SqsEventSource.Builder.create(refreshQueue)
                .batchSize(1)
                .maxConcurrency(2)
                .build());
        syncFunction.addEventSource(SqsEventSource.Builder.create(nameHistoryQueue)
                .batchSize(1)
                // CDK/SQS permits a minimum maximum-concurrency value of two.
                // Bulk pages sharply reduce Henrik calls even at this floor.
                .maxConcurrency(2)
                .build());

        CfnApi httpApi = CfnApi.Builder.create(this, "HttpApi")
                .name("valstats-" + deploymentName + "-api")
                .protocolType("HTTP")
                .corsConfiguration(CfnApi.CorsProperty.builder()
                        .allowHeaders(List.of("content-type"))
                        .allowMethods(List.of("GET", "POST", "OPTIONS"))
                        .allowOrigins(List.of(
                                "http://localhost:5173",
                                "http://localhost:3000",
                                "https://valstats.m8z4m892gf.workers.dev"))
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
                .defaultRouteSettings(CfnStage.RouteSettingsProperty.builder()
                        .throttlingRateLimit(API_RATE_LIMIT_PER_SECOND)
                        .throttlingBurstLimit(API_BURST_LIMIT)
                        .build())
                .build();

        CfnPermission.Builder.create(this, "ApiInvokePermission")
                .action("lambda:InvokeFunction")
                .functionName(apiFunction.getFunctionName())
                .principal("apigateway.amazonaws.com")
                .sourceArn("arn:" + getPartition() + ":execute-api:" + getRegion() + ":"
                        + getAccount() + ":" + httpApi.getRef() + "/*")
                .build();

        output("RefreshQueueUrl", refreshQueue.getQueueUrl(), "ValStats-" + deploymentName + "-RefreshQueueUrl");
        output("RefreshQueueArn", refreshQueue.getQueueArn(), "ValStats-" + deploymentName + "-RefreshQueueArn");
        output("NameHistoryQueueUrl", nameHistoryQueue.getQueueUrl(), "ValStats-" + deploymentName + "-NameHistoryQueueUrl");
        output("ApiUrl", "https://" + httpApi.getRef() + ".execute-api." + getRegion()
                + ".amazonaws.com", "ValStats-" + deploymentName + "-ApiUrl");
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

    private List<String> sesIdentityResources(String senderEmail, String recipientEmail) {
        if (senderEmail == null || senderEmail.isBlank()) return List.of("*");
        String identityArn = "arn:" + getPartition() + ":ses:" + getRegion() + ":" + getAccount()
                + ":identity/";
        java.util.LinkedHashSet<String> resources = new java.util.LinkedHashSet<>();
        resources.add(identityArn + senderEmail);
        int at = senderEmail.lastIndexOf('@');
        if (at > 0 && at < senderEmail.length() - 1) {
            resources.add(identityArn + senderEmail.substring(at + 1));
        }
        if (recipientEmail != null && !recipientEmail.isBlank()) {
            resources.add(identityArn + recipientEmail);
        }
        return List.copyOf(resources);
    }

    private LogGroup functionLogGroup(String id, String functionName) {
        return LogGroup.Builder.create(this, id)
                .logGroupName("/aws/lambda/" + functionName)
                .retention(RetentionDays.ONE_WEEK)
                .removalPolicy(RemovalPolicy.DESTROY)
                .build();
    }
}
