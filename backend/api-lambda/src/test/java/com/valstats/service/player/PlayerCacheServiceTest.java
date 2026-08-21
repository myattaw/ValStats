package com.valstats.service.player;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlayerCacheServiceTest {

    @Mock
    private DynamoDbClient dynamoDbClient;

    @Test
    void storePlayerProfileAliasesReservedRegionAttribute() {
        PlayerCacheService service = new PlayerCacheService(dynamoDbClient);

        service.storePlayerProfile("player-puuid", "Rages", "Alt", "na");

        ArgumentCaptor<UpdateItemRequest> requests = ArgumentCaptor.forClass(UpdateItemRequest.class);
        verify(dynamoDbClient, atLeastOnce()).updateItem(requests.capture());

        UpdateItemRequest profileUpdate = findProfileUpdate(requests.getAllValues());
        assertEquals("region", profileUpdate.expressionAttributeNames().get("#r"));
        assertTrue(profileUpdate.updateExpression().contains("#r = :region"));
        assertFalse(profileUpdate.updateExpression().contains(" region ="));
        assertEquals("na", profileUpdate.expressionAttributeValues().get(":region").s());
    }

    @Test
    void minimalIdentityProfileIsNotACompleteAccountCacheHit() {
        when(dynamoDbClient.getItem(org.mockito.ArgumentMatchers.any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(Map.of(
                                "puuid", AttributeValue.fromS("player-puuid")))
                        .build())
                .thenReturn(GetItemResponse.builder().item(Map.of(
                                "puuid", AttributeValue.fromS("player-puuid"),
                                "name", AttributeValue.fromS("Player"),
                                "tag", AttributeValue.fromS("Tag"),
                                "region", AttributeValue.fromS("na")))
                        .build());

        PlayerCacheService service = new PlayerCacheService(dynamoDbClient);

        assertTrue(service.getCachedAccount("Player", "Tag").isEmpty());
    }

    private UpdateItemRequest findProfileUpdate(List<UpdateItemRequest> requests) {
        return requests.stream()
                .filter(request -> "PROFILE".equals(request.key().get("SK").s()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected a player PROFILE update"));
    }
}
