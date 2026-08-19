package com.valstats.controller;

import com.valstats.service.ValorantService;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.annotation.MockBean;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@MicronautTest
class ValorantControllerTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    ValorantService valorantService;

    @Test
    void accountRouteDelegatesToServiceAndReturnsJson() {
        when(valorantService.getAccountDetails("player", "NA1"))
                .thenReturn(Map.of("name", "player", "tag", "NA1"));

        var response = client.toBlocking().exchange(
                HttpRequest.GET("/api/valorant/account/player/NA1"),
                Map.class
        );

        assertEquals(HttpStatus.OK, response.getStatus());
        assertEquals("player", response.body().get("name"));
        assertEquals("NA1", response.body().get("tag"));
        verify(valorantService).getAccountDetails("player", "NA1");
    }

    @Test
    void playerNameHistoryRouteDelegatesToServiceAndReturnsJson() {
        String puuid = "1f0dc104-bd89-5283-91b0-52a2f082a63d";
        when(valorantService.getPlayerNameHistory(puuid))
                .thenReturn(List.of(Map.of("name", "player", "tag", "NA1")));

        var response = client.toBlocking().exchange(
                HttpRequest.GET("/api/valorant/players/" + puuid + "/names"),
                Map.class
        );

        assertEquals(HttpStatus.OK, response.getStatus());
        assertEquals(200, response.body().get("status"));
        verify(valorantService).getPlayerNameHistory(puuid);
    }

    @MockBean(ValorantService.class)
    static ValorantService valorantService() {
        return mock(ValorantService.class);
    }

    @MockBean(DynamoDbClient.class)
    static DynamoDbClient dynamoDbClient() {
        return mock(DynamoDbClient.class);
    }
}
