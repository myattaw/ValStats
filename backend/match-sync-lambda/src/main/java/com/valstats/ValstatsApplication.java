package com.valstats;

import io.micronaut.runtime.Micronaut;

/**
 * Entry point for the Match Sync Lambda function.
 * This Lambda periodically fetches match data from the Valorant API and writes to DynamoDB.
 * Triggered by EventBridge schedule.
 */
public class ValstatsApplication {

    public static void main(String[] args) {
        Micronaut.build(args)
                .mainClass(ValstatsApplication.class)
                .start();
    }

}