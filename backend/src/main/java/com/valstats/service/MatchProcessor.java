package com.valstats.service;

import com.valstats.model.match.Match;
import com.valstats.model.player.Player;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Singleton
public class MatchProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(MatchProcessor.class);

    private final DynamoDbClient ddb;
    private final String tableName = "valstats";

    public MatchProcessor(DynamoDbClient ddb) {
        this.ddb = ddb;
    }

    public boolean processMatch(Match match, String puuid) {
        String matchId = match.metadata().matchId();
        String seasonId = match.metadata().seasonId() != null ? match.metadata().seasonId() : "unknown";
        String now = Instant.now().toString();

        Player targetPlayer = match.players().all_players().stream()
                .filter(p -> p.puuid().equals(puuid))
                .findFirst()
                .orElse(null);

        if (targetPlayer == null) {
            LOG.warn("Player {} not found in match {}", puuid, matchId);
            return false;
        }

        if (!storeMatchData(match, now)) {
            return false;
        }

        int redRounds = match.teams() != null && match.teams().red() != null
                ? match.teams().red().rounds_won()
                : 0;

        int blueRounds = match.teams() != null && match.teams().blue() != null
                ? match.teams().blue().rounds_won()
                : 0;

        processPlayerMatch(
                puuid,
                seasonId,
                matchId,
                targetPlayer.stats().kills(),
                targetPlayer.stats().deaths(),
                targetPlayer.stats().headshots(),
                targetPlayer.stats().bodyshots(),
                targetPlayer.stats().legshots(),
                targetPlayer.stats().score(),
                targetPlayer.stats().assists(),
                targetPlayer.damage_made(),
                match.metadata().gameStart(),
                match.metadata().map(),
                "",
                targetPlayer.agent() != null ? targetPlayer.agent().name() : "",
                targetPlayer.agent() != null ? targetPlayer.agent().id() : "",
                targetPlayer.team(),
                redRounds,
                blueRounds
        );

        return true;
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
                blueRounds
        );

        return true;
    }

    private boolean storeMatchData(Match match, String now) {
        String matchId = match.metadata().matchId();
        String matchPk = "MATCH#" + matchId;

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("PK", AttributeValue.fromS(matchPk));
        item.put("SK", AttributeValue.fromS("METADATA"));
        item.put("matchId", AttributeValue.fromS(matchId));
        item.put("map", AttributeValue.fromS(nullSafe(match.metadata().map())));
        item.put("mode", AttributeValue.fromS(nullSafe(match.metadata().mode())));
        item.put("gameStart", AttributeValue.fromN(String.valueOf(match.metadata().gameStart())));
        item.put("gameLength", AttributeValue.fromN(String.valueOf(match.metadata().gameLength())));
        item.put("seasonId", AttributeValue.fromS(
                match.metadata().seasonId() != null ? match.metadata().seasonId() : "unknown"
        ));
        item.put("storedAt", AttributeValue.fromS(now));

        if (match.teams() != null) {
            if (match.teams().red() != null) {
                item.put("redRoundsWon", AttributeValue.fromN(String.valueOf(match.teams().red().rounds_won())));
                item.put("redRoundsLost", AttributeValue.fromN(String.valueOf(match.teams().red().rounds_lost())));
            }
            if (match.teams().blue() != null) {
                item.put("blueRoundsWon", AttributeValue.fromN(String.valueOf(match.teams().blue().rounds_won())));
                item.put("blueRoundsLost", AttributeValue.fromN(String.valueOf(match.teams().blue().rounds_lost())));
            }
        }

        try {
            ddb.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(item)
                    .conditionExpression("attribute_not_exists(PK) AND attribute_not_exists(SK)")
                    .build());

            storeMatchPlayers(match, now);
            return true;

        } catch (ConditionalCheckFailedException e) {
            LOG.debug("Match {} already exists in database", matchId);
            return false;
        } catch (DynamoDbException e) {
            LOG.error("Failed to store match metadata for {}", matchId, e);
            return false;
        }
    }

    private void storeMatchPlayers(Match match, String now) {
        String matchId = match.metadata().matchId();

        for (Player player : match.players().all_players()) {
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("PK", AttributeValue.fromS("MATCH#" + matchId));
            item.put("SK", AttributeValue.fromS("PLAYER#" + player.puuid()));
            item.put("puuid", AttributeValue.fromS(nullSafe(player.puuid())));
            item.put("name", AttributeValue.fromS(nullSafe(player.name())));
            item.put("tag", AttributeValue.fromS(nullSafe(player.tag())));
            item.put("team", AttributeValue.fromS(nullSafe(player.team())));

            item.put("agentName", AttributeValue.fromS(
                    player.agent() != null ? player.agent().name() : ""
            ));

            item.put("agentId", AttributeValue.fromS(
                    player.agent() != null ? player.agent().id() : ""
            ));

            item.put("currenttier", AttributeValue.fromN(String.valueOf(player.currenttier())));
            item.put("kills", AttributeValue.fromN(String.valueOf(player.stats().kills())));
            item.put("deaths", AttributeValue.fromN(String.valueOf(player.stats().deaths())));
            item.put("assists", AttributeValue.fromN(String.valueOf(player.stats().assists())));
            item.put("score", AttributeValue.fromN(String.valueOf(player.stats().score())));
            item.put("headshots", AttributeValue.fromN(String.valueOf(player.stats().headshots())));
            item.put("bodyshots", AttributeValue.fromN(String.valueOf(player.stats().bodyshots())));
            item.put("legshots", AttributeValue.fromN(String.valueOf(player.stats().legshots())));
            item.put("damage_made", AttributeValue.fromN(String.valueOf(player.damage_made())));
            item.put("storedAt", AttributeValue.fromS(now));

            try {
                ddb.putItem(PutItemRequest.builder()
                        .tableName(tableName)
                        .item(item)
                        .build());
            } catch (DynamoDbException e) {
                LOG.error("Failed to store player {} for match {}", player.puuid(), matchId, e);
            }
        }
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
            long blueRoundsWon
    ) {
        String aggregatePk = "PLAYER#" + puuid;
        String markerSk = String.format("MATCH#%013d#%s", gameStart, matchId);
        String seasonSk = "SEASON#" + seasonId;
        String totalSk = "TOTAL";
        String now = Instant.now().toString();
        long roundsPlayed = redRoundsWon + blueRoundsWon;

        Map<String, AttributeValue> markerItem = new HashMap<>();
        markerItem.put("PK", AttributeValue.fromS(aggregatePk));
        markerItem.put("SK", AttributeValue.fromS(markerSk));
        markerItem.put("matchId", AttributeValue.fromS(matchId));
        markerItem.put("gameStart", AttributeValue.fromN(String.valueOf(gameStart)));
        markerItem.put("seasonId", AttributeValue.fromS(seasonId));
        markerItem.put("map", AttributeValue.fromS(map == null ? "" : map));
        markerItem.put("mapId", AttributeValue.fromS(mapId == null ? "" : mapId));
        markerItem.put("agentName", AttributeValue.fromS(agentName == null ? "" : agentName));
        markerItem.put("agentId", AttributeValue.fromS(agentId == null ? "" : agentId));
        markerItem.put("team", AttributeValue.fromS(team == null ? "" : team));
        markerItem.put("kills", AttributeValue.fromN(String.valueOf(kills)));
        markerItem.put("deaths", AttributeValue.fromN(String.valueOf(deaths)));
        markerItem.put("assists", AttributeValue.fromN(String.valueOf(assists)));
        markerItem.put("score", AttributeValue.fromN(String.valueOf(score)));
        markerItem.put("headshots", AttributeValue.fromN(String.valueOf(headshots)));
        markerItem.put("bodyshots", AttributeValue.fromN(String.valueOf(bodyshots)));
        markerItem.put("legshots", AttributeValue.fromN(String.valueOf(legshots)));
        markerItem.put("damage_made", AttributeValue.fromN(String.valueOf(damageMade)));
        markerItem.put("redRoundsWon", AttributeValue.fromN(String.valueOf(redRoundsWon)));
        markerItem.put("blueRoundsWon", AttributeValue.fromN(String.valueOf(blueRoundsWon)));
        markerItem.put("processed_at", AttributeValue.fromS(now));

        Put putMarker = Put.builder()
                .tableName(tableName)
                .item(markerItem)
                .conditionExpression("attribute_not_exists(PK) AND attribute_not_exists(SK)")
                .build();

        Map<String, AttributeValue> v = new HashMap<>();
        v.put(":zero", AttributeValue.fromN("0"));
        v.put(":one", AttributeValue.fromN("1"));
        v.put(":kills", AttributeValue.fromN(Long.toString(kills)));
        v.put(":deaths", AttributeValue.fromN(Long.toString(deaths)));
        v.put(":assists", AttributeValue.fromN(Long.toString(assists)));
        v.put(":score", AttributeValue.fromN(Long.toString(score)));
        v.put(":head", AttributeValue.fromN(Long.toString(headshots)));
        v.put(":body", AttributeValue.fromN(Long.toString(bodyshots)));
        v.put(":leg", AttributeValue.fromN(Long.toString(legshots)));
        v.put(":damage", AttributeValue.fromN(Long.toString(damageMade)));
        v.put(":rounds", AttributeValue.fromN(Long.toString(roundsPlayed)));
        v.put(":matchId", AttributeValue.fromS(matchId));
        v.put(":now", AttributeValue.fromS(now));

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

        Update seasonUpdate = Update.builder()
                .tableName(tableName)
                .key(Map.of(
                        "PK", AttributeValue.fromS(aggregatePk),
                        "SK", AttributeValue.fromS(seasonSk)
                ))
                .updateExpression(updateExpr)
                .expressionAttributeValues(v)
                .build();

        Update totalUpdate = Update.builder()
                .tableName(tableName)
                .key(Map.of(
                        "PK", AttributeValue.fromS(aggregatePk),
                        "SK", AttributeValue.fromS(totalSk)
                ))
                .updateExpression(updateExpr)
                .expressionAttributeValues(v)
                .build();

        TransactWriteItemsRequest tx = TransactWriteItemsRequest.builder()
                .transactItems(
                        TransactWriteItem.builder().put(putMarker).build(),
                        TransactWriteItem.builder().update(seasonUpdate).build(),
                        TransactWriteItem.builder().update(totalUpdate).build()
                )
                .build();

        try {
            ddb.transactWriteItems(tx);
            LOG.debug("Processed match {} for player {}", matchId, puuid);
        } catch (TransactionCanceledException e) {
            LOG.debug("Match {} already processed", matchId);
        } catch (DynamoDbException e) {
            LOG.error("Failed to process match {} for player {}", matchId, puuid, e);
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