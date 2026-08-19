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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

@MicronautTest
class HealthControllerTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Test
    void healthEndpointReportsUp() {
        var response = client.toBlocking().exchange(HttpRequest.GET("/health"), Map.class);

        assertEquals(HttpStatus.OK, response.getStatus());
        assertEquals("UP", response.body().get("status"));
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
