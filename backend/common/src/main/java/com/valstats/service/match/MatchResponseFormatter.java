package com.valstats.service.match;

import com.valstats.model.response.MatchResponses;
import com.valstats.model.stored.StoredMatchesResponse;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Handles formatting of match responses for the API.
 * Separates the formatting logic from the data retrieval logic.
 */
@Singleton
public class MatchResponseFormatter {

    /**
     * Format cached match data from DynamoDB
     */
    public MatchResponses.MatchHistoryResponse formatCachedMatches(
            List<Map<String, AttributeValue>> cachedMatches,
            List<Map<String, AttributeValue>> cachedMMR
    ) {
        // Create a map of MMR entries indexed by matchId for quick lookup
        Map<String, Map<String, AttributeValue>> mmrMap = cachedMMR.stream()
                .collect(Collectors.toMap(
                        m -> getString(m, "matchId"),
                        m -> m,
                        (a, b) -> a
                ));

        List<MatchResponses.MatchSummary> formattedMatches = cachedMatches.stream()
                .map(match -> formatMatchRow(match, mmrMap.get(getString(match, "matchId"))))
                .toList();

        return new MatchResponses.MatchHistoryResponse(200, true, formattedMatches, null);
    }

    /**
     * Format full cached match details
     */
    public MatchResponses.MatchDetailsResponse formatCachedMatchDetails(
            Map<String, AttributeValue> matchMetadata,
            List<Map<String, AttributeValue>> players
    ) {
        List<MatchResponses.MatchPlayer> allPlayers = players.stream()
                .map(p -> new MatchResponses.MatchPlayer(
                        getString(p, "puuid"),
                        getString(p, "name"),
                        getString(p, "team"),
                        getString(p, "agentName"),
                        new MatchResponses.MatchPlayerStats(
                                getInt(p, "kills"),
                                getInt(p, "deaths"),
                                getInt(p, "assists"),
                                getInt(p, "score"),
                                getInt(p, "headshots"),
                                getInt(p, "bodyshots"),
                                getInt(p, "legshots")
                        ),
                        getInt(p, "damage_made")
                ))
                .toList();

        MatchResponses.MatchMetadata metadata = new MatchResponses.MatchMetadata(
                getString(matchMetadata, "matchId"),
                getString(matchMetadata, "map"),
                getLong(matchMetadata, "gameStart"),
                getInt(matchMetadata, "redRoundsWon") + getInt(matchMetadata, "blueRoundsWon")
        );

        MatchResponses.MatchDetails details = new MatchResponses.MatchDetails(
                metadata,
                new MatchResponses.MatchPlayers(allPlayers)
        );

        return new MatchResponses.MatchDetailsResponse(200, true, details);
    }

    /**
     * Format a single cached match row
     */
    private MatchResponses.MatchSummary formatMatchRow(
            Map<String, AttributeValue> match,
            Map<String, AttributeValue> mmr
    ) {
        String matchId = getString(match, "matchId");
        int kills = getInt(match, "kills");
        int deaths = getInt(match, "deaths");
        int assists = getInt(match, "assists");
        String kda = kills + "/" + deaths + "/" + assists;

        int rr = mmr != null ? getInt(mmr, "rr") : 0;


        int rankingInTier = mmr != null ? getInt(mmr, "ranking_in_tier") : 0;

        int blueRoundsWon = getInt(match, "blueRoundsWon");
        int redRoundsWon = getInt(match, "redRoundsWon");
        String team = getString(match, "team").toLowerCase(Locale.ROOT);

        int score = "red".equals(team) ? redRoundsWon : blueRoundsWon;
        int enemyScore = "red".equals(team) ? blueRoundsWon : redRoundsWon;
        int roundsPlayed = getInt(match, "rounds_played");
        if (roundsPlayed <= 0) {
            roundsPlayed = redRoundsWon + blueRoundsWon;
        }
        long adr = Math.round(getDouble(match, "adr"));
        if (adr <= 0 && roundsPlayed > 0) {
            adr = Math.round((double) getInt(match, "damage_made") / roundsPlayed);
        }

        String agentName = getString(match, "agentName");
        String agentId = getString(match, "agentId");
        long ts = getLong(match, "gameStart");
        String timestamp = ts > 0 ? Instant.ofEpochSecond(ts).toString() : "";

        int rankTier = 0;

        // 1. Prefer match tier (most reliable)
        if (match.containsKey("tier")) {
            rankTier = getInt(match, "tier");
        }

        // 2. fallback to MMR if needed
        else if (mmr != null && mmr.containsKey("currenttier")) {
            rankTier = getInt(mmr, "currenttier");
        }

        // 3. last fallback
        else if (mmr != null && mmr.containsKey("mmr")) {
            rankTier = getInt(mmr, "mmr") / 100;
        }

        String rank = rankTier > 0 ? getRankName(rankTier) : "Unranked";

        MatchResponses.Teams teams = new MatchResponses.Teams(
                new MatchResponses.TeamRound(redRoundsWon, null),
                new MatchResponses.TeamRound(blueRoundsWon, null)
        );

        return new MatchResponses.MatchSummary(
                matchId,
                getString(match, "map"),
                getString(match, "mapId"),
                score > enemyScore ? "Victory" : "Defeat",
                score,
                enemyScore,
                kda,
                agentName,
                "https://media.valorant-api.com/agents/" + agentId + "/displayicon.png",
                roundsPlayed > 0 ? Math.round((float) getInt(match, "score") / roundsPlayed) : 0,
                timestamp,
                ts,
                getString(match, "server"),
                rank,
                rankTier,
                rankingInTier,
                rr,
                roundsPlayed,
                adr,
                teams,
                "",
                false
        );
    }

    // ====== Helper Methods ======

    private int getInt(Map<String, AttributeValue> map, String key) {
        return map.containsKey(key) ? Integer.parseInt(map.get(key).n()) : 0;
    }

    private double getDouble(Map<String, AttributeValue> map, String key) {
        return map.containsKey(key)
                ? Double.parseDouble(map.get(key).n())
                : 0.0;
    }

    private long getLong(Map<String, AttributeValue> map, String key) {
        return map.containsKey(key) ? Long.parseLong(map.get(key).n()) : 0L;
    }

    private String getString(Map<String, AttributeValue> map, String key) {
        return map.containsKey(key) ? map.get(key).s() : "";
    }

    private String getRankName(int tier) {
        String[] ranks = {
                "Iron 1", "Iron 2", "Iron 3",
                "Bronze 1", "Bronze 2", "Bronze 3",
                "Silver 1", "Silver 2", "Silver 3",
                "Gold 1", "Gold 2", "Gold 3",
                "Platinum 1", "Platinum 2", "Platinum 3",
                "Diamond 1", "Diamond 2", "Diamond 3",
                "Ascendant 1", "Ascendant 2", "Ascendant 3",
                "Immortal 1", "Immortal 2", "Immortal 3",
                "Radiant"
        };

        if (tier == 0) return "Unranked";

        int index = tier - 3;
        return (index >= 0 && index < ranks.length) ? ranks[index] : "Unknown";
    }

}

