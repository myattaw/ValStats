package com.valstats.controller;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@MicronautTest
class CorsControllerTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Test
    void acceptsNestedOptionsRequest() {
        var response = client.toBlocking().exchange(
                HttpRequest.OPTIONS("/api/valorant/matches/na/player/tag/refresh"));

        assertEquals(HttpStatus.OK, response.status());
    }
}
