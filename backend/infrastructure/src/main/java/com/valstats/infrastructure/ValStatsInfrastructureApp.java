package com.valstats.infrastructure;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.Tags;

public final class ValStatsInfrastructureApp {
    private ValStatsInfrastructureApp() {
    }

    public static void main(String[] args) {
        App app = new App();

        String environmentName = contextValue(app, "environment", "dev");
        String region = contextValue(app, "region", "us-east-1");
        LambdaDeploymentMode deploymentMode = LambdaDeploymentMode.fromContext(
                contextValue(app, "lambdaRuntime", "jvm"));
        Environment awsEnvironment = Environment.builder()
                .account(System.getenv("CDK_DEFAULT_ACCOUNT"))
                .region(region)
                .build();

        ValStatsStatefulStack stateful = new ValStatsStatefulStack(
                app,
                "ValStats-" + environmentName + "-Stateful",
                environmentName,
                awsEnvironment
        );

        ValStatsApplicationStack application = new ValStatsApplicationStack(
                app,
                "ValStats-" + environmentName
                        + (deploymentMode.isNative() ? "-Native" : "")
                        + "-Application",
                environmentName,
                awsEnvironment,
                stateful.getDataTable(),
                stateful.getHenrikApiSecret(),
                deploymentMode,
                artifactPath(app, "apiArtifact", deploymentMode.isNative()
                        ? "../api-lambda/target/native/lambda-native.zip"
                        : "../api-lambda/target/api-lambda-0.1.jar"),
                artifactPath(app, "syncArtifact", deploymentMode.isNative()
                        ? "../match-sync-lambda/target/native/lambda-native.zip"
                        : "../match-sync-lambda/target/match-sync-lambda-0.1.jar")
        );
        application.addStackDependency(stateful);

        Tags.of(app).add("Application", "ValStats");
        Tags.of(app).add("Environment", environmentName);
        Tags.of(app).add("ManagedBy", "AWS-CDK");
        Tags.of(application).add("LambdaRuntime", deploymentMode.isNative() ? "graalvm-native" : "java-21");

        app.synth();
    }

    private static String contextValue(App app, String key, String defaultValue) {
        Object value = app.getNode().tryGetContext(key);
        return value == null || value.toString().isBlank() ? defaultValue : value.toString();
    }

    private static String artifactPath(App app, String key, String defaultValue) {
        return contextValue(app, key, defaultValue);
    }
}
