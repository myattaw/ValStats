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
                "ValStats-" + environmentName + "-Application",
                environmentName,
                awsEnvironment
        );
        application.addStackDependency(stateful);

        Tags.of(app).add("Application", "ValStats");
        Tags.of(app).add("Environment", environmentName);
        Tags.of(app).add("ManagedBy", "AWS-CDK");

        app.synth();
    }

    private static String contextValue(App app, String key, String defaultValue) {
        Object value = app.getNode().tryGetContext(key);
        return value == null || value.toString().isBlank() ? defaultValue : value.toString();
    }
}
