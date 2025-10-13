package com.valstats.controller;

import com.valstats.model.MatchResponse;
import com.valstats.service.ValorantService;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;

import java.util.Map;

@Controller("/api/valorant")
public class ValorantController {

    private final ValorantService valorantService;

    public ValorantController(ValorantService valorantService) {
        this.valorantService = valorantService;
    }

    @Get("/recent-matches/{region}/{name}/{tag}")
    public MatchResponse getMatches(
            @PathVariable String region,
            @PathVariable String name,
            @PathVariable String tag) {
        return valorantService.getRecentMatches(region, name, tag);
    }

    @Get("/stored-matches/{region}/{name}/{tag}")
    public Map<String, Object> getAllMatches(
            @PathVariable String region,
            @PathVariable String name,
            @PathVariable String tag) {
        return valorantService.getStoredMatches(region, name, tag);
    }

    @Get("/test")
    public MatchResponse test() {
        // This is the "hello world" endpoint that makes the specific request
        return valorantService.getRecentMatches("na", "yoru smurf", "rages");
    }

    @Get("/test2")
    public Map<String, Object> test2() {
        // This is the "hello world" endpoint that makes the specific request
        return valorantService.getMMRHistory("na", "yoru smurf", "rages");
    }

    @Get("/mmr-history/{region}/{name}/{tag}")
    public Map<String, Object> getMMRHistory(
            @PathVariable String region,
            @PathVariable String name,
            @PathVariable String tag) {
        return valorantService.getMMRHistory(region, name, tag);
    }

    @Get("/account/{name}/{tag}")
    public Map<String, Object> getAccount(
            @PathVariable String name,
            @PathVariable String tag) {
        return valorantService.getAccountDetails(name, tag);
    }

}
