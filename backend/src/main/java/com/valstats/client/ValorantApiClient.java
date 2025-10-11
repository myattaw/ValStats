package com.valstats.client;

import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.core.annotation.Nullable;

import java.util.Map;

@Client("https://api.henrikdev.xyz")
public interface ValorantApiClient {

    @Get("/valorant/v3/matches/{region}/{name}/{tag}")
    Map<String, Object> getMatches(
            @PathVariable String region,
            @PathVariable String name,
            @PathVariable String tag,
            @Header("Authorization") String authorization);
}

