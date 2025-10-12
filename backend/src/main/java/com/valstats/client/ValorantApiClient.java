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
    MatchResponse getMatches(
            @PathVariable String region,
            @PathVariable String name,
            @PathVariable String tag,
            @QueryValue(value = "size", defaultValue = "10") Integer size, // <- Micronaut query param
            @Header("Authorization") String authorization
    );

    @Get("/valorant/v1/mmr-history/{region}/{name}/{tag}")
    Map<String, Object> getMMRHistory(
            @PathVariable String region,
            @PathVariable String name,
            @PathVariable String tag,
            @Header("Authorization") String authorization
    );

}
