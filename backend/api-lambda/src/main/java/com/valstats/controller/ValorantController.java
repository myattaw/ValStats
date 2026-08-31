package com.valstats.controller;

import com.valstats.lambda.ApiGatewayPathCodec;
import com.valstats.service.ValorantService;
import io.micronaut.http.annotation.*;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
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
                decode(name),
                decode(tag),
                size,
                lastKey.orElse(null),
                act,
                mode
        );
    }

    @Post("/matches/{region}/{name}/{tag}/refresh")
    public HttpResponse<Map<String, Object>> refreshMatches(
            @PathVariable String region,
            @PathVariable String name,
            @PathVariable String tag) {
        Map<String, Object> response = valorantService.refreshMatches(region, decode(name), decode(tag));
        return Integer.valueOf(202).equals(response.get("status"))
                ? HttpResponse.<Map<String, Object>>status(HttpStatus.ACCEPTED).body(response)
                : HttpResponse.ok(response);
    }

    @Get("/matches/{region}/{name}/{tag}/refresh-status")
    public Map<String, Object> getMatchRefreshStatus(
            @PathVariable String region,
            @PathVariable String name,
            @PathVariable String tag) {
        return valorantService.getMatchRefreshStatus(region, decode(name), decode(tag));
    }

    @Post("/matches/{region}/{name}/{tag}/acts/{seasonId}/refresh")
    public HttpResponse<Map<String, Object>> refreshActMatches(
            @PathVariable String region,
            @PathVariable String name,
            @PathVariable String tag,
            @PathVariable String seasonId) {
        Map<String, Object> response = valorantService.refreshActMatches(region, decode(name), decode(tag), seasonId);
        return Integer.valueOf(202).equals(response.get("status"))
                ? HttpResponse.<Map<String, Object>>status(HttpStatus.ACCEPTED).body(response)
                : HttpResponse.ok(response);
    }

    @Get("/matches/{region}/{name}/{tag}/backfill-status")
    public Map<String, Object> getBackfillStatus(
            @PathVariable String region, @PathVariable String name, @PathVariable String tag,
            @QueryValue(defaultValue = "all") String seasonId) {
        return valorantService.getBackfillStatus(region, decode(name), decode(tag), seasonId);
    }

    @Get("/modes/{region}/{name}/{tag}")
    public Object getModes(@PathVariable String region, @PathVariable String name, @PathVariable String tag) {
        return valorantService.getAvailableModes(region, decode(name), decode(tag));
    }

    @Get("/account/{name}/{tag}")
    public Map<String, Object> getAccount(
            @PathVariable String name,
            @PathVariable String tag) {
        return valorantService.getAccountDetails(decode(name), decode(tag));
    }

    @Get("/summary/{region}/{name}/{tag}")
    public Map<String, Object> getPlayerSummary(
            @PathVariable String region,
            @PathVariable String name,
            @PathVariable String tag,
            @QueryValue(defaultValue = "10") Integer recentMatches) {
        return valorantService.getPlayerSummary(
                region, decode(name), decode(tag), recentMatches);
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
    public HttpResponse<Map<String, Object>> refreshPlayerNameHistory(
            @PathVariable String puuid,
            @QueryValue(defaultValue = "false") boolean force) {
        Map<String, Object> response = valorantService.refreshPlayerNameHistory(puuid, force);
        return Integer.valueOf(202).equals(response.get("status"))
                ? HttpResponse.<Map<String, Object>>status(HttpStatus.ACCEPTED).body(response)
                : HttpResponse.ok(response);
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
        return valorantService.getPlayerStats(region, decode(name), decode(tag), seasonId, mode);
    }

    @Get("/stats/{region}/{name}/{tag}/adr")
    public Map<String, Object> getPlayerAdr(
            @PathVariable String region,
            @PathVariable String name,
            @PathVariable String tag,
            @QueryValue(defaultValue = "all") String seasonId,
            @QueryValue(defaultValue = "competitive") String mode) {
        return valorantService.getPlayerAdr(region, decode(name), decode(tag), seasonId, mode);
    }

    @Get("/acts/{region}/{name}/{tag}")
    public Object getActs(
            @PathVariable String region,
            @PathVariable String name,
            @PathVariable String tag
    ) {
        return valorantService.getAvailableActs(region, decode(name), decode(tag));
    }

    @Get("/mmr/{region}/{name}/{tag}")
    public Map<String, Object> getPlayerMMR(
            @PathVariable String region,
            @PathVariable String name,
            @PathVariable String tag,
            @QueryValue(defaultValue = "all") String seasonId
    ) {
        return valorantService.getPlayerMMR(region, decode(name), decode(tag), seasonId);
    }

    @Get("/insights/{region}/{name}/{tag}")
    public Map<String, Object> getPlayerInsights(
            @PathVariable String region, @PathVariable String name, @PathVariable String tag) {
        return valorantService.getPlayerInsights(region, decode(name), decode(tag));
    }

    private String decode(String pathSegment) {
        return ApiGatewayPathCodec.decodeSegment(pathSegment);
    }

}
