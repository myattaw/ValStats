package com.valstats.client;

import com.valstats.model.MatchResponse;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.client.annotation.Client;

import java.util.Map;

@Client("https://api.henrikdev.xyz")
public interface ValorantApiClient {

    @Get("/valorant/v3/matches/{region}/{name}/{tag}")
    MatchResponse getRecentMatches(
            @PathVariable String region,
            @PathVariable String name,
            @PathVariable String tag,
            @QueryValue(value = "size", defaultValue = "10") Integer size, // <- Micronaut query param
            @QueryValue(value = "start", defaultValue = "1") Integer start,
            @Header("Authorization") String authorization
    );

    @Get("/valorant/v1/stored-matches/{region}/{name}/{tag}")
    Map<String, Object> getStoredMatches(
            @PathVariable String region,
            @PathVariable String name,
            @PathVariable String tag,
            @QueryValue(value = "size", defaultValue = "10") Integer size,
            @QueryValue(value = "page", defaultValue = "1") Integer page,
            @QueryValue(value = "mode", defaultValue = "competitive") String filter,
            @Header("Authorization") String authorization
    );

    @Get("/valorant/v1/mmr-history/{region}/{name}/{tag}")
    Map<String, Object> getMMRHistory(
            @PathVariable String region,
            @PathVariable String name,
            @PathVariable String tag,
            @Header("Authorization") String authorization
    );

    @Get("/valorant/v1/account/{name}/{tag}")
    Map<String, Object> getAccount(
            @PathVariable String name,
            @PathVariable String tag,
            @Header("Authorization") String authorization
    );

}
