package com.valstats.controller;

import com.valstats.model.MatchResponse;
import com.valstats.service.ValorantService;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.QueryValue;

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
            @PathVariable String tag,
            @QueryValue(value = "size", defaultValue = "10") Integer size,
            @QueryValue(value = "start", defaultValue = "0") Integer start

    ) {
        return valorantService.getRecentMatches(region, name, tag, size, start);
    }

    @Get("/stored-matches/{region}/{name}/{tag}")
    public Map<String, Object> getAllMatches(
            @PathVariable String region,
            @PathVariable String name,
            @PathVariable String tag,
            @QueryValue(value = "size", defaultValue = "10") Integer size,
            @QueryValue(value = "page", defaultValue = "1") Integer page

    ) {
        return valorantService.getStoredMatches(region, name, tag, size, page);
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

    @Get("/match/{matchid}")
    public Map<String, Object> getMatchById(@PathVariable String matchid) {
        return valorantService.getMatchById(matchid);
    }

    // Player statistics endpoints

    @Get("/stats/{region}/{name}/{tag}")
    public Map<String, Object> getPlayerStats(
            @PathVariable String region,
            @PathVariable String name,
            @PathVariable String tag,
            @QueryValue(value = "season", defaultValue = "all") String seasonId) {
        return valorantService.getPlayerStats(region, name, tag, seasonId);
    }

    @Get("/kill-ratio/{region}/{name}/{tag}")
    public Map<String, Object> getKillRatio(
            @PathVariable String region,
            @PathVariable String name,
            @PathVariable String tag,
            @QueryValue(value = "season", defaultValue = "all") String seasonId) {
        return valorantService.getKillRatio(region, name, tag, seasonId);
    }

    @Get("/headshot-percent/{region}/{name}/{tag}")
    public Map<String, Object> getHeadshotPercent(
            @PathVariable String region,
            @PathVariable String name,
            @PathVariable String tag,
            @QueryValue(value = "season", defaultValue = "all") String seasonId) {
        return valorantService.getHeadshotPercent(region, name, tag, seasonId);
    }

    @Get("/avg-combat-score/{region}/{name}/{tag}")
    public Map<String, Object> getAvgCombatScore(
            @PathVariable String region,
            @PathVariable String name,
            @PathVariable String tag,
            @QueryValue(value = "season", defaultValue = "all") String seasonId) {
        return valorantService.getAvgCombatScore(region, name, tag, seasonId);
    }

    @Get("/kills-per-round/{region}/{name}/{tag}")
    public Map<String, Object> getKillsPerRound(
            @PathVariable String region,
            @PathVariable String name,
            @PathVariable String tag,
            @QueryValue(value = "season", defaultValue = "all") String seasonId) {
        return valorantService.getKillsPerRound(region, name, tag, seasonId);
    }

}
