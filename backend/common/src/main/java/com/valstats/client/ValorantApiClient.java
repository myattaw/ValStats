package com.valstats.client;

import com.valstats.model.stored.StoredMatchesResponse;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.client.annotation.Client;

import java.util.Map;

@Client("https://api.henrikdev.xyz")
@Header(name = "Authorization", value = "${HDEV_KEY}")
public interface ValorantApiClient {

    @Get("/valorant/v1/stored-matches/{region}/{name}/{tag}")
    StoredMatchesResponse getStoredMatches(
            @PathVariable String region,
            @PathVariable String name,
            @PathVariable String tag,
            @QueryValue(value = "size", defaultValue = "10") Integer size,
            @QueryValue(value = "page", defaultValue = "1") Integer page,
            @Nullable @QueryValue("mode") String filter
    );

    @Get("/valorant/v1/mmr-history/{region}/{name}/{tag}")
    Map<String, Object> getMMRHistory(
            @PathVariable String region,
            @PathVariable String name,
            @PathVariable String tag
    );

    @Get("/valorant/v1/account/{name}/{tag}")
    Map<String, Object> getAccount(
            @PathVariable String name,
            @PathVariable String tag
    );

    @Get("/valorant/v2/match/{matchid}")
    Map<String, Object> getMatchById(
            @PathVariable String matchid
    );

    @Get("/valorant/v2/mmr/{region}/{name}/{tag}")
    Map<String, Object> getMMR(
            @PathVariable String region,
            @PathVariable String name,
            @PathVariable String tag
    );
}
