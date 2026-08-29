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
import java.time.Instant;

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

    @Test
    void expiredRunningNameScanDoesNotStayRefreshingForever() {
        when(dynamoDbClient.getItem(org.mockito.ArgumentMatchers.any(GetItemRequest.class)))
                .thenReturn(nameScanState("RUNNING", Instant.now().minusSeconds(361)));

        Map<String, Object> state = new PlayerCacheService(dynamoDbClient)
                .getNameHistoryScanState("player-puuid");

        assertFalse((boolean) state.get("refreshing"));
        assertEquals("FAILED", state.get("scanStatus"));
    }

    @Test
    void recentRunningNameScanIsStillRefreshing() {
        when(dynamoDbClient.getItem(org.mockito.ArgumentMatchers.any(GetItemRequest.class)))
                .thenReturn(nameScanState("RUNNING", Instant.now()));

        Map<String, Object> state = new PlayerCacheService(dynamoDbClient)
                .getNameHistoryScanState("player-puuid");

        assertTrue((boolean) state.get("refreshing"));
        assertEquals("RUNNING", state.get("scanStatus"));
    }

    @Test
    void activeFirstNameScanDoesNotRequestAnotherBackfill() {
        when(dynamoDbClient.getItem(org.mockito.ArgumentMatchers.any(GetItemRequest.class)))
                .thenReturn(nameScanState("RUNNING", Instant.now()));

        assertFalse(new PlayerCacheService(dynamoDbClient)
                .shouldBackfillNameHistory("player-puuid"));
    }

    @Test
    void completedNameScanNeverRequestsAnAutomaticRescan() {
        when(dynamoDbClient.getItem(org.mockito.ArgumentMatchers.any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(Map.of(
                        "status", AttributeValue.fromS("COMPLETE"),
                        "lastBackfill", AttributeValue.fromN("1"),
                        "updatedAt", AttributeValue.fromS(Instant.EPOCH.toString())
                )).build());

        assertFalse(new PlayerCacheService(dynamoDbClient)
                .shouldBackfillNameHistory("player-puuid"));
    }

    @Test
    void failedNameScanCanBeRetriedAutomatically() {
        when(dynamoDbClient.getItem(org.mockito.ArgumentMatchers.any(GetItemRequest.class)))
                .thenReturn(nameScanState("FAILED", Instant.now()));

        assertTrue(new PlayerCacheService(dynamoDbClient)
                .shouldBackfillNameHistory("player-puuid"));
    }

    private GetItemResponse nameScanState(String status, Instant updatedAt) {
        return GetItemResponse.builder().item(Map.of(
                "status", AttributeValue.fromS(status),
                "updatedAt", AttributeValue.fromS(updatedAt.toString())
        )).build();
    }

    private UpdateItemRequest findProfileUpdate(List<UpdateItemRequest> requests) {
        return requests.stream()
                .filter(request -> "PROFILE".equals(request.key().get("SK").s()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected a player PROFILE update"));
    }
}
