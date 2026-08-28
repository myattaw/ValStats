package com.valstats.service.match;

import com.valstats.client.HenrikApiRequestQueue;
import com.valstats.client.ValorantApiClient;
import com.valstats.service.DynamoDbService;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class MatchDataServiceTest {

    @Test
    void cachedReadNeverCallsHenrik() {
        DynamoDbService dynamo = mock(DynamoDbService.class);
        ValorantApiClient api = mock(ValorantApiClient.class);
        HenrikApiRequestQueue queue = mock(HenrikApiRequestQueue.class);
        when(dynamo.getMatchesFromGSI(any(), anyInt(), any()))
                .thenReturn(QueryResponse.builder().items(List.of()).build());
        when(dynamo.getMMRHistory(any())).thenReturn(List.of());

        MatchDataService service = new MatchDataService(
                dynamo, api, new MatchResponseFormatter(), mock(MatchProcessor.class), queue);

        var response = service.getPlayerMatches(
                "puuid", "na", "Player", "Tag", 15, null, "all", "competitive");

        assertTrue(response.data().isEmpty());
        verifyNoInteractions(api, queue);
        verify(dynamo, never()).updatePlayerLastRecentMatchUpdate(any(), any(), any());
    }

    @Test
    void passesDecodedCursorToDynamoDb() {
        DynamoDbService dynamo = mock(DynamoDbService.class);
        when(dynamo.getMatchesFromGSI(any(), anyInt(), any()))
                .thenReturn(QueryResponse.builder().items(List.of()).build());
        when(dynamo.getMMRHistory(any())).thenReturn(List.of());

        MatchDataService service = new MatchDataService(
                dynamo, mock(ValorantApiClient.class), new MatchResponseFormatter(),
                mock(MatchProcessor.class), mock(HenrikApiRequestQueue.class));

        String cursor = "%7B%22PK%22%3A%22PLAYER%23p1%22%2C%22SK%22%3A%22MATCH%23m1%22%2C"
                + "%22GSI1PK%22%3A%22PLAYER%23p1%22%2C%22GSI1SK%22%3A123%7D";
        service.getPlayerMatches("p1", "na", "Player", "Tag", 15, cursor, "all", "competitive");

        @SuppressWarnings("unchecked")
        var keyCaptor = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(dynamo).getMatchesFromGSI(eq("p1"), eq(15), keyCaptor.capture());
        Map<String, AttributeValue> key = keyCaptor.getValue();
        assertEquals("PLAYER#p1", key.get("PK").s());
        assertEquals("MATCH#m1", key.get("SK").s());
        assertEquals("PLAYER#p1", key.get("GSI1PK").s());
        assertEquals("123", key.get("GSI1SK").n());
    }
}
