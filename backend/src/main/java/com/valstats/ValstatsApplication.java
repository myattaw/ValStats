package com.valstats;

import io.micronaut.runtime.Micronaut;

public class ValstatsApplication {

    public static void main(String[] args) {
        System.setProperty("micronaut.server.netty.enabled", "true");
        System.setProperty("micronaut.server.servlet.enabled", "false");
        System.setProperty("micronaut.server.port", "0"); // Use random port

        Micronaut.build(args)
                .mainClass(ValstatsApplication.class)
                .environments("local")
                .start();
    }
}
