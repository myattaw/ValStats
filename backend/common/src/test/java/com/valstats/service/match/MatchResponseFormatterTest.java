package com.valstats.service.match;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchResponseFormatterTest {

    private final MatchResponseFormatter formatter = new MatchResponseFormatter();

    @Test
    void formatsAnEmptyCachedMatchPage() {
        var response = formatter.formatCachedMatches(List.of(), List.of());

        assertEquals(200, response.status());
        assertTrue(response.cached());
        assertTrue(response.data().isEmpty());
    }

    @Test
    void formatsCachedMatchDetails() {
        Map<String, AttributeValue> metadata = Map.of(
                "matchId", AttributeValue.fromS("match-1"),
                "map", AttributeValue.fromS("Ascent"),
                "gameStart", AttributeValue.fromN("123"),
                "roundsPlayed", AttributeValue.fromN("20")
        );

        var response = formatter.formatCachedMatchDetails(metadata, List.of());

        assertEquals(200, response.status());
        assertTrue(response.cached());
        assertEquals("match-1", response.data().metadata().matchid());
        assertEquals("Ascent", response.data().metadata().map());
        assertTrue(response.data().players().allPlayers().isEmpty());
    }

    @Test
    void calculatesAdrForOlderRowsWithoutPrecomputedAdr() {
        Map<String, AttributeValue> match = Map.ofEntries(
                Map.entry("matchId", AttributeValue.fromS("match-1")),
                Map.entry("gameStart", AttributeValue.fromN("123")),
                Map.entry("damage_made", AttributeValue.fromN("3600")),
                Map.entry("rounds_played", AttributeValue.fromN("24")),
                Map.entry("redRoundsWon", AttributeValue.fromN("13")),
                Map.entry("blueRoundsWon", AttributeValue.fromN("11"))
        );

        var response = formatter.formatCachedMatches(List.of(match), List.of());

        assertEquals(150L, response.data().get(0).adr());
        assertEquals(24, response.data().get(0).roundsPlayed());
    }
}
