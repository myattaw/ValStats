package com.valstats.controller;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;

import java.util.Map;

@Controller("/health")
public class HealthController {

    @Get
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
