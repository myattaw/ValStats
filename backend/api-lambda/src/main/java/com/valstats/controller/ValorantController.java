package com.valstats.controller;

import com.valstats.service.ValorantService;
import io.micronaut.http.annotation.*;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

import java.util.Map;
import java.util.Optional;

@Controller("/api/valorant")
@ExecuteOn(TaskExecutors.BLOCKING)
public class ValorantController {

    private final ValorantService valorantService;

    public ValorantController(ValorantService valorantService) {
        this.valorantService = valorantService;
    }

    @Get("/matches/{region}/{name}/{tag}")
    public Object getMatches(
            @PathVariable String region,
            @PathVariable String name,
            @PathVariable String tag,
            @QueryValue(defaultValue = "10") Integer size,
            @QueryValue Optional<String> lastKey, // ✅ NEW
            @QueryValue(defaultValue = "all") String act
    ) {
        return valorantService.getUnifiedMatches(
                region,
                name,
                tag,
                size,
                lastKey.orElse(null),
                act
        );
    }

    @Get("/account/{name}/{tag}")
    public Map<String, Object> getAccount(
            @PathVariable String name,
            @PathVariable String tag) {
        return valorantService.getAccountDetails(name, tag);
    }

    @Get("/match/{matchid}")
    public Object getMatchById(@PathVariable String matchid) {
        return valorantService.getMatchById(matchid);
    }

    @Get("/stats/{region}/{name}/{tag}")
    public Map<String, Object> getPlayerStats(
            @PathVariable String region,
            @PathVariable String name,
            @PathVariable String tag,
            @QueryValue(defaultValue = "all") String seasonId) {
        return valorantService.getPlayerStats(region, name, tag, seasonId);
    }

    @Get("/stats/{region}/{name}/{tag}/adr")
    public Map<String, Object> getPlayerAdr(
            @PathVariable String region,
            @PathVariable String name,
            @PathVariable String tag,
            @QueryValue(defaultValue = "all") String seasonId) {
        return valorantService.getPlayerAdr(region, name, tag, seasonId);
    }

    @Get("/acts/{region}/{name}/{tag}")
    public Object getActs(
            @PathVariable String region,
            @PathVariable String name,
            @PathVariable String tag
    ) {
        return valorantService.getAvailableActs(region, name, tag);
    }

    @Get("/mmr/{region}/{name}/{tag}")
    public Map<String, Object> getPlayerMMR(
            @PathVariable String region,
            @PathVariable String name,
            @PathVariable String tag,
            @QueryValue(defaultValue = "all") String seasonId
    ) {
        return valorantService.getPlayerMMR(region, name, tag, seasonId);
    }

}