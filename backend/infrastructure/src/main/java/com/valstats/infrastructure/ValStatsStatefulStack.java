package com.valstats.infrastructure;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.dynamodb.Attribute;
import software.amazon.awscdk.services.dynamodb.AttributeType;
import software.amazon.awscdk.services.dynamodb.BillingMode;
import software.amazon.awscdk.services.dynamodb.ProjectionType;
import software.amazon.awscdk.services.dynamodb.PointInTimeRecoverySpecification;
import software.amazon.awscdk.services.dynamodb.Table;
import software.amazon.awscdk.services.secretsmanager.Secret;
import software.constructs.Construct;

public final class ValStatsStatefulStack extends Stack {
    private final Table dataTable;
    private final Secret henrikApiSecret;

    public ValStatsStatefulStack(
            Construct scope,
            String id,
            String environmentName,
            Environment awsEnvironment
    ) {
        super(scope, id, StackProps.builder()
                .env(awsEnvironment)
                .terminationProtection(true)
                .description("ValStats stateful data resources (" + environmentName + ")")
                .build());

        dataTable = Table.Builder.create(this, "DataTable")
                .tableName("valstats-" + environmentName)
                .partitionKey(Attribute.builder().name("PK").type(AttributeType.STRING).build())
                .sortKey(Attribute.builder().name("SK").type(AttributeType.STRING).build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .pointInTimeRecoverySpecification(PointInTimeRecoverySpecification.builder()
                        .pointInTimeRecoveryEnabled(true)
                        .build())
                .deletionProtection(true)
                .removalPolicy(RemovalPolicy.RETAIN)
                .build();

        dataTable.addGlobalSecondaryIndex(software.amazon.awscdk.services.dynamodb.GlobalSecondaryIndexProps.builder()
                .indexName("GSI1")
                .partitionKey(Attribute.builder().name("GSI1PK").type(AttributeType.STRING).build())
                .sortKey(Attribute.builder().name("GSI1SK").type(AttributeType.NUMBER).build())
                .projectionType(ProjectionType.ALL)
                .build());

        henrikApiSecret = Secret.Builder.create(this, "HenrikApiSecret")
                .secretName("valstats/" + environmentName + "/henrik-api-key")
                .description("HenrikDev API key used by ValStats Lambda functions")
                .build();

        CfnOutput.Builder.create(this, "DataTableName")
                .value(dataTable.getTableName())
                .exportName("ValStats-" + environmentName + "-DataTableName")
                .build();
        CfnOutput.Builder.create(this, "DataTableArn")
                .value(dataTable.getTableArn())
                .exportName("ValStats-" + environmentName + "-DataTableArn")
                .build();
        CfnOutput.Builder.create(this, "HenrikApiSecretArn")
                .value(henrikApiSecret.getSecretArn())
                .exportName("ValStats-" + environmentName + "-HenrikApiSecretArn")
                .build();
    }

    public Table getDataTable() {
        return dataTable;
    }

    public Secret getHenrikApiSecret() {
        return henrikApiSecret;
    }
}
