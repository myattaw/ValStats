package com.valstats.infrastructure;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.cloudwatch.Alarm;
import software.amazon.awscdk.services.cloudwatch.ComparisonOperator;
import software.amazon.awscdk.services.cloudwatch.TreatMissingData;
import software.amazon.awscdk.services.sqs.DeadLetterQueue;
import software.amazon.awscdk.services.sqs.Queue;
import software.amazon.awscdk.services.sqs.QueueEncryption;
import software.constructs.Construct;

public final class ValStatsApplicationStack extends Stack {
    public ValStatsApplicationStack(
            Construct scope,
            String id,
            String environmentName,
            Environment awsEnvironment
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

        output("RefreshQueueUrl", refreshQueue.getQueueUrl(), "ValStats-" + environmentName + "-RefreshQueueUrl");
        output("RefreshQueueArn", refreshQueue.getQueueArn(), "ValStats-" + environmentName + "-RefreshQueueArn");
        output("NameHistoryQueueUrl", nameHistoryQueue.getQueueUrl(), "ValStats-" + environmentName + "-NameHistoryQueueUrl");
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
}
