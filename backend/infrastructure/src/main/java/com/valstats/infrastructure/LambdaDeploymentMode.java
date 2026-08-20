package com.valstats.infrastructure;

import software.amazon.awscdk.services.lambda.Architecture;
import software.amazon.awscdk.services.lambda.Runtime;

import java.util.Locale;

enum LambdaDeploymentMode {
    JVM(
            "jvm",
            "",
            Runtime.JAVA_21,
            Architecture.ARM_64,
            "io.micronaut.function.aws.proxy.payload2.APIGatewayV2HTTPEventFunction",
            "com.valstats.queue.RefreshQueueHandler"
    ),
    NATIVE(
            "native",
            "-native",
            Runtime.PROVIDED_AL2023,
            Architecture.X86_64,
            "bootstrap",
            "bootstrap"
    );

    private final String contextValue;
    private final String resourceSuffix;
    private final Runtime runtime;
    private final Architecture architecture;
    private final String apiHandler;
    private final String syncHandler;

    LambdaDeploymentMode(
            String contextValue,
            String resourceSuffix,
            Runtime runtime,
            Architecture architecture,
            String apiHandler,
            String syncHandler
    ) {
        this.contextValue = contextValue;
        this.resourceSuffix = resourceSuffix;
        this.runtime = runtime;
        this.architecture = architecture;
        this.apiHandler = apiHandler;
        this.syncHandler = syncHandler;
    }

    static LambdaDeploymentMode fromContext(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (LambdaDeploymentMode mode : values()) {
            if (mode.contextValue.equals(normalized)) {
                return mode;
            }
        }
        throw new IllegalArgumentException(
                "Unsupported lambdaRuntime '" + value + "'. Expected 'jvm' or 'native'.");
    }

    String resourceSuffix() {
        return resourceSuffix;
    }

    Runtime runtime() {
        return runtime;
    }

    Architecture architecture() {
        return architecture;
    }

    String apiHandler() {
        return apiHandler;
    }

    String syncHandler() {
        return syncHandler;
    }

    boolean isNative() {
        return this == NATIVE;
    }
}
