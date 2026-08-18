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
            @QueryValue(defaultValue = "all") String act,
            @QueryValue(defaultValue = "competitive") String mode
    ) {
        return valorantService.getUnifiedMatches(
                region,
                name,
                tag,
                size,
                lastKey.orElse(null),
                act,
                mode
        );
    }

    @Post("/matches/{region}/{name}/{tag}/refresh")
    public Map<String, Object> refreshMatches(
            @PathVariable String region,
            @PathVariable String name,
            @PathVariable String tag) {
        return valorantService.refreshMatches(region, name, tag);
    }

    @Get("/matches/{region}/{name}/{tag}/refresh-status")
    public Map<String, Object> getMatchRefreshStatus(
            @PathVariable String region,
            @PathVariable String name,
            @PathVariable String tag) {
        return valorantService.getMatchRefreshStatus(region, name, tag);
    }

    @Get("/modes/{region}/{name}/{tag}")
    public Object getModes(@PathVariable String region, @PathVariable String name, @PathVariable String tag) {
        return valorantService.getAvailableModes(region, name, tag);
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

    @Get("/players/{puuid}/names")
    public Map<String, Object> getPlayerNameHistory(@PathVariable String puuid) {
        return Map.of("status", 200, "data", valorantService.getPlayerNameHistory(puuid));
    }

    @Get("/players/{puuid}/names/refresh-status")
    public Map<String, Object> getPlayerNameHistoryRefreshStatus(@PathVariable String puuid) {
        return valorantService.getPlayerNameHistoryRefreshStatus(puuid);
    }

    @Post("/players/{puuid}/names/refresh")
    public Map<String, Object> refreshPlayerNameHistory(@PathVariable String puuid) {
        return valorantService.refreshPlayerNameHistory(puuid);
    }

    @Get("/players/{puuid}")
    public Map<String, Object> getPlayerIdentity(@PathVariable String puuid) {
        return valorantService.getPlayerIdentity(puuid);
    }

    @Get("/stats/{region}/{name}/{tag}")
    public Map<String, Object> getPlayerStats(
            @PathVariable String region,
            @PathVariable String name,
            @PathVariable String tag,
            @QueryValue(defaultValue = "all") String seasonId,
            @QueryValue(defaultValue = "competitive") String mode) {
        return valorantService.getPlayerStats(region, name, tag, seasonId, mode);
    }

    @Get("/stats/{region}/{name}/{tag}/adr")
    public Map<String, Object> getPlayerAdr(
            @PathVariable String region,
            @PathVariable String name,
            @PathVariable String tag,
            @QueryValue(defaultValue = "all") String seasonId,
            @QueryValue(defaultValue = "competitive") String mode) {
        return valorantService.getPlayerAdr(region, name, tag, seasonId, mode);
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
