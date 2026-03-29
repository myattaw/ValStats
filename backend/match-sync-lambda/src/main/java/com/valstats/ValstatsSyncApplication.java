package com.valstats;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.Micronaut;

/**
 * Entry point for the Match Sync Lambda function.
 * This Lambda periodically fetches match data from the Valorant API and writes to DynamoDB.
 * Triggered by EventBridge schedule.
 */
public class ValstatsSyncApplication {

    public static void main(String[] args) {
        ApplicationContext ctx = ApplicationContext.run();
        ctx.getBean(LocalRunner.class).run();
    }

}