package com.valstats.service.match;

import com.valstats.model.match.Match;
import com.valstats.model.player.Player;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Singleton
public class MatchProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(MatchProcessor.class);

    private final DynamoDbClient ddb;
    private final String tableName = "valstats";

    public MatchProcessor(DynamoDbClient ddb) {
        this.ddb = ddb;
    }

    @SuppressWarnings("unchecked")
    public boolean processStoredMatchSummary(Map<String, Object> match, String puuid) {
        Map<String, Object> meta = asMap(match.get("meta"));
        Map<String, Object> stats = asMap(match.get("stats"));
        Map<String, Object> season = asMap(meta.get("season"));
        Map<String, Object> shots = asMap(stats.get("shots"));
        Map<String, Object> damage = asMap(stats.get("damage"));
        Map<String, Object> mapObj = asMap(meta.get("map"));
        Map<String, Object> character = asMap(stats.get("character"));

        String matchId = str(meta.get("id"));
        if (matchId.isBlank()) {
            return false;
        }

        String seasonId = str(season.getOrDefault("id", "unknown"));
        int redRounds = extractTeamRounds(match.get("teams"), "red");
        int blueRounds = extractTeamRounds(match.get("teams"), "blue");

        long damageMade =
                damage.containsKey("made") ? num(damage.get("made")) :
                        damage.containsKey("dealt") ? num(damage.get("dealt")) :
                                num(stats.get("damage_made"));

        int tier = num(stats.get("tier"));

        processPlayerMatch(
                puuid,
                seasonId,
                matchId,
                num(stats.get("kills")),
                num(stats.get("deaths")),
                num(shots.get("head")),
                num(shots.get("body")),
                num(shots.get("leg")),
                num(stats.get("score")),
                num(stats.get("assists")),
                damageMade,
                parseDateRaw(meta.get("started_at")),
                str(mapObj.get("name")),
                str(mapObj.get("id")),
                str(character.get("name")),
                str(character.get("id")),
                str(stats.get("team")),
                redRounds,
                blueRounds,
                tier
        );

        return true;
    }

    @SuppressWarnings("unchecked")
    public boolean processRecentMatchSummary(Map<String, Object> match, String puuid) {

        Map<String, Object> metadata = asMap(match.get("metadata"));
        Map<String, Object> players = asMap(match.get("players"));

        if (metadata.isEmpty() || players.isEmpty()) {
            LOG.warn("Invalid V3 match: {}", match);
            return false;
        }

        String matchId = str(metadata.get("matchid"));
        Object tsObj = metadata.get("game_start");

        if (matchId.isBlank() || tsObj == null) {
            LOG.warn("Invalid metadata: {}", metadata);
            return false;
        }

        long gameStart = ((Number) tsObj).longValue();

        // =========================
        // 🔥 FIND PLAYER
        // =========================
        List<Map<String, Object>> allPlayers =
                (List<Map<String, Object>>) players.getOrDefault("all_players", Collections.emptyList());

        Map<String, Object> player = null;

        for (Map<String, Object> p : allPlayers) {
            if (puuid.equals(p.get("puuid"))) {
                player = p;
                break;
            }
        }

        if (player == null) {
            LOG.warn("Player {} not found in match {}", puuid, matchId);
            return false;
        }

        // =========================
        // 🔥 PLAYER STATS
        // =========================
        Map<String, Object> stats = asMap(player.get("stats"));

        int kills = num(stats.get("kills"));
        int deaths = num(stats.get("deaths"));
        int assists = num(stats.get("assists"));
        int score = num(stats.get("score"));

        int headshots = num(stats.get("headshots"));
        int bodyshots = num(stats.get("bodyshots"));
        int legshots = num(stats.get("legshots"));

        long damage = num(player.get("damage_made"));

        String agent = str(player.get("character"));
        String team = str(player.get("team"));

        int tier = num(player.get("currenttier"));

        // =========================
        // 🔥 TEAM DATA
        // =========================
        Map<String, Object> teams = asMap(match.get("teams"));

        int redRounds = num(asMap(teams.get("red")).get("rounds_won"));
        int blueRounds = num(asMap(teams.get("blue")).get("rounds_won"));

        // =========================
        // 🔥 MAP + SEASON
        // =========================
        String map = str(metadata.get("map"));
        String seasonId = str(metadata.get("season_id"));

        // =========================
        // 🔥 STORE MATCH
        // =========================
        processPlayerMatch(
                puuid,
                seasonId,
                matchId,
                kills,
                deaths,
                headshots,
                bodyshots,
                legshots,
                score,
                assists,
                damage,
                gameStart,
                map,
                "",
                agent,
                "",
                team,
                redRounds,
                blueRounds,
                tier
        );

        LOG.debug("Processed V3 match {} with real stats", matchId);

        return true;
    }

    public void processPlayerMatch(
            String puuid,
            String seasonId,
            String matchId,
            long kills,
            long deaths,
            long headshots,
            long bodyshots,
            long legshots,
            long score,
            long assists,
            long damageMade,
            long gameStart,
            String map,
            String mapId,
            String agentName,
            String agentId,
            String team,
            long redRoundsWon,
            long blueRoundsWon,
            int tier
    ) {
        String pk = "PLAYER#" + puuid;
        String sk = String.format("SEASON#%s#MATCH#%013d#%s", seasonId, gameStart, matchId);
        String now = Instant.now().toString();
        long roundsPlayed = redRoundsWon + blueRoundsWon;
        double adr = roundsPlayed > 0 ? (double) damageMade / roundsPlayed : 0.0;

        // =========================
        // 1. CREATE MARKER (IDEMPOTENCY GUARD)
        // =========================
        Map<String, AttributeValue> marker = new HashMap<>();
        marker.put("PK", AttributeValue.fromS(pk));
        marker.put("SK", AttributeValue.fromS(sk));
        marker.put("matchId", AttributeValue.fromS(matchId));
        marker.put("gameStart", AttributeValue.fromN(String.valueOf(gameStart)));
        marker.put("seasonId", AttributeValue.fromS(seasonId));
        marker.put("map", AttributeValue.fromS(nullSafe(map)));
        marker.put("mapId", AttributeValue.fromS(nullSafe(mapId)));
        marker.put("agentName", AttributeValue.fromS(nullSafe(agentName)));
        marker.put("agentId", AttributeValue.fromS(nullSafe(agentId)));
        marker.put("team", AttributeValue.fromS(nullSafe(team)));
        marker.put("kills", AttributeValue.fromN(String.valueOf(kills)));
        marker.put("deaths", AttributeValue.fromN(String.valueOf(deaths)));
        marker.put("assists", AttributeValue.fromN(String.valueOf(assists)));
        marker.put("score", AttributeValue.fromN(String.valueOf(score)));
        marker.put("headshots", AttributeValue.fromN(String.valueOf(headshots)));
        marker.put("bodyshots", AttributeValue.fromN(String.valueOf(bodyshots)));
        marker.put("legshots", AttributeValue.fromN(String.valueOf(legshots)));
        marker.put("damage_made", AttributeValue.fromN(String.valueOf(damageMade)));
        marker.put("rounds_played", AttributeValue.fromN(String.valueOf(roundsPlayed)));
        marker.put("adr", AttributeValue.fromN(String.valueOf(adr)));
        marker.put("redRoundsWon", AttributeValue.fromN(String.valueOf(redRoundsWon)));
        marker.put("blueRoundsWon", AttributeValue.fromN(String.valueOf(blueRoundsWon)));
        marker.put("tier", AttributeValue.fromN(String.valueOf(tier)));
        marker.put("processed_at", AttributeValue.fromS(now));

        marker.put("GSI1PK", AttributeValue.fromS("PLAYER#" + puuid));
        marker.put("GSI1SK", AttributeValue.fromN(String.valueOf(gameStart)));

        try {
            ddb.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(marker)
                    .conditionExpression("attribute_not_exists(SK)") // ONLY SK matters
                    .build());

        } catch (ConditionalCheckFailedException e) {
            // ✅ Already processed → SAFE EXIT
            LOG.debug("Match {} already processed for {}", matchId, puuid);
            return;
        } catch (DynamoDbException e) {
            LOG.error("Failed to write marker for match {}", matchId, e);
            return; // do NOT continue if marker fails
        }

        // =========================
        // 2. UPDATE SEASON AGGREGATE
        // =========================
        updateAggregate(pk, "SEASON#" + seasonId,
                kills, deaths, assists, score,
                headshots, bodyshots, legshots,
                damageMade, roundsPlayed, matchId, now
        );

        // =========================
        // 3. UPDATE TOTAL AGGREGATE
        // =========================
        updateAggregate(pk, "TOTAL",
                kills, deaths, assists, score,
                headshots, bodyshots, legshots,
                damageMade, roundsPlayed, matchId, now
        );

        LOG.debug("Processed match {} for player {}", matchId, puuid);
    }

    private void updateAggregate(
            String pk,
            String sk,
            long kills,
            long deaths,
            long assists,
            long score,
            long headshots,
            long bodyshots,
            long legshots,
            long damage,
            long rounds,
            String matchId,
            String now
    ) {
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":zero", AttributeValue.fromN("0"));
        values.put(":one", AttributeValue.fromN("1"));
        values.put(":kills", AttributeValue.fromN(String.valueOf(kills)));
        values.put(":deaths", AttributeValue.fromN(String.valueOf(deaths)));
        values.put(":assists", AttributeValue.fromN(String.valueOf(assists)));
        values.put(":score", AttributeValue.fromN(String.valueOf(score)));
        values.put(":head", AttributeValue.fromN(String.valueOf(headshots)));
        values.put(":body", AttributeValue.fromN(String.valueOf(bodyshots)));
        values.put(":leg", AttributeValue.fromN(String.valueOf(legshots)));
        values.put(":damage", AttributeValue.fromN(String.valueOf(damage)));
        values.put(":rounds", AttributeValue.fromN(String.valueOf(rounds)));
        values.put(":matchId", AttributeValue.fromS(matchId));
        values.put(":now", AttributeValue.fromS(now));

        String updateExpr =
                "SET matches_played = if_not_exists(matches_played, :zero) + :one, " +
                        "total_kills = if_not_exists(total_kills, :zero) + :kills, " +
                        "total_deaths = if_not_exists(total_deaths, :zero) + :deaths, " +
                        "total_assists = if_not_exists(total_assists, :zero) + :assists, " +
                        "total_score = if_not_exists(total_score, :zero) + :score, " +
                        "total_headshots = if_not_exists(total_headshots, :zero) + :head, " +
                        "total_bodyshots = if_not_exists(total_bodyshots, :zero) + :body, " +
                        "total_legshots = if_not_exists(total_legshots, :zero) + :leg, " +
                        "total_damage = if_not_exists(total_damage, :zero) + :damage, " +
                        "total_rounds = if_not_exists(total_rounds, :zero) + :rounds, " +
                        "lastProcessedMatchId = :matchId, updatedAt = :now";

        try {
            ddb.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of(
                            "PK", AttributeValue.fromS(pk),
                            "SK", AttributeValue.fromS(sk)
                    ))
                    .updateExpression(updateExpr)
                    .expressionAttributeValues(values)
                    .build());

        } catch (DynamoDbException e) {
            LOG.error("Failed to update aggregate {} for {}", sk, pk, e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : new HashMap<>();
    }

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private int num(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private int extractTeamRounds(Object teamsObj, String teamName) {
        if (!(teamsObj instanceof Map<?, ?> teams)) {
            return 0;
        }

        Object teamObj = teams.get(teamName);
        if (teamObj instanceof Number n) {
            return n.intValue();
        }

        if (teamObj instanceof Map<?, ?> teamMap) {
            Object roundsWon = teamMap.get("rounds_won");
            if (roundsWon instanceof Number n) {
                return n.intValue();
            }
        }

        return 0;
    }

    private long parseDateRaw(Object startedAt) {
        if (startedAt == null) {
            return 0L;
        }

        try {
            return Instant.parse(String.valueOf(startedAt)).getEpochSecond();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}