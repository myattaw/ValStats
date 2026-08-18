package com.valstats.service.match;

import com.valstats.model.stored.StoredMatchesResponse;
import com.valstats.service.player.PlayerNameRecorder;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Singleton
public class MatchProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(MatchProcessor.class);

    private final DynamoDbClient ddb;
    private final List<PlayerNameRecorder> playerNameRecorders;
    private final String tableName = "valstats";

    public MatchProcessor(DynamoDbClient ddb, List<PlayerNameRecorder> playerNameRecorders) {
        this.ddb = ddb;
        this.playerNameRecorders = playerNameRecorders;
    }

    public boolean processStoredMatchSummary(StoredMatchesResponse.StoredMatch match, String puuid) {
        if (match == null || match.meta() == null || match.stats() == null) {
            return false;
        }

        StoredMatchesResponse.Meta meta = match.meta();
        StoredMatchesResponse.Stats stats = match.stats();
        StoredMatchesResponse.Season season = meta.season();
        StoredMatchesResponse.Shots shots = stats.shots();
        StoredMatchesResponse.Damage damage = stats.damage();
        StoredMatchesResponse.MapInfo mapObj = meta.map();
        StoredMatchesResponse.Character character = stats.character();

        String matchId = str(meta.id());
        if (matchId.isBlank()) {
            return false;
        }

        String seasonId = season != null ? str(season.id()) : "unknown";
        if (seasonId.isBlank()) {
            seasonId = "unknown";
        }

        int redRounds = extractTeamRounds(match.teams(), "red");
        int blueRounds = extractTeamRounds(match.teams(), "blue");

        long damageMade = damage != null ? damage.dealt() : 0L;

        int tier = stats.tier();
        long gameStart = parseDateRaw(meta.startedAt());

        processPlayerMatch(
                puuid,
                seasonId,
                matchId,
                stats.kills(),
                stats.deaths(),
                shots != null ? shots.head() : 0,
                shots != null ? shots.body() : 0,
                shots != null ? shots.leg() : 0,
                stats.score(),
                stats.assists(),
                damageMade,
                gameStart,
                mapObj != null ? str(mapObj.name()) : "",
                mapObj != null ? str(mapObj.id()) : "",
                character != null ? str(character.name()) : "",
                character != null ? str(character.id()) : "",
                str(stats.team()),
                redRounds,
                blueRounds,
                tier
        );

        recordPlayerNames(match, gameStart);

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

    private void recordPlayerNames(StoredMatchesResponse.StoredMatch match, long observedAt) {
        for (PlayerNameRecorder recorder : playerNameRecorders) {
            recorder.record(
                    match.stats().puuid(),
                    match.stats().name(),
                    match.stats().tag(),
                    observedAt
            );
        }

        if (match.players() == null || match.players().isEmpty()) {
            return;
        }

        for (StoredMatchesResponse.Player player : match.players()) {
            if (player == null) {
                continue;
            }
            for (PlayerNameRecorder recorder : playerNameRecorders) {
                recorder.record(player.puuid(), player.name(), player.tag(), observedAt);
            }
        }
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

    private String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private int num(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private int extractTeamRounds(StoredMatchesResponse.Teams teams, String teamName) {
        if (teams == null) return 0;
        return "red".equals(teamName) ? teams.red() : teams.blue();
    }

    private long parseDateRaw(String startedAt) {
        if (startedAt == null || startedAt.isBlank()) {
            return 0L;
        }

        try {
            return Instant.parse(startedAt).getEpochSecond();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}

