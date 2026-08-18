package com.valstats;

import io.micronaut.runtime.Micronaut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the API Lambda function.
 * This Lambda exposes REST endpoints for reading Valorant stats and match data from DynamoDB.
 */
public class ValstatsAPIApplication {

    private static final Logger LOG = LoggerFactory.getLogger(ValstatsAPIApplication.class);


    public static void main(String[] args) {

        LOG.info("Valstats API main entrypoint invoked");
        LOG.info("HDEV_KEY configured: {}", System.getenv("HDEV_KEY") != null);

        Micronaut.build(args)
                .mainClass(ValstatsAPIApplication.class)
                .start();
    }

}
