package com.valstats.service;

import com.valstats.model.Match;
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

    /**
     * Process and store a match. Returns true if the match was new and stored.
     */
    public boolean processMatch(Match match, String puuid) {
        String matchId = match.metadata().matchId();
        String seasonId = match.metadata().seasonId() != null ? match.metadata().seasonId() : "unknown";
        String now = Instant.now().toString();

        // Find the player in this match
        Player targetPlayer = match.players().all_players().stream()
                .filter(p -> p.puuid().equals(puuid))
                .findFirst()
                .orElse(null);

        if (targetPlayer == null) {
            LOG.warn("Player {} not found in match {}", puuid, matchId);
            return false;
        }

        // Store full match data
        if (!storeMatchData(match, now)) {
            // Match already exists, skip processing
            return false;
        }

        // Process player stats for this match
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
                match.metadata().gameStart()
        );

        return true;
    }

    /**
     * Store full match data with conditional write (only if not exists).
     */
    private boolean storeMatchData(Match match, String now) {
        String matchId = match.metadata().matchId();
        String matchPk = "MATCH#" + matchId;

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("PK", AttributeValue.fromS(matchPk));
        item.put("SK", AttributeValue.fromS("METADATA"));
        item.put("matchId", AttributeValue.fromS(matchId));
        item.put("map", AttributeValue.fromS(match.metadata().map()));
        item.put("mode", AttributeValue.fromS(match.metadata().mode()));
        item.put("gameStart", AttributeValue.fromN(String.valueOf(match.metadata().gameStart())));
        item.put("gameLength", AttributeValue.fromN(String.valueOf(match.metadata().gameLength())));
        item.put("seasonId", AttributeValue.fromS(match.metadata().seasonId() != null ? match.metadata().seasonId() : "unknown"));
        item.put("storedAt", AttributeValue.fromS(now));

        // Store team scores
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
                    .conditionExpression("attribute_not_exists(PK)")
                    .build());

            // Store player entries for this match
            storeMatchPlayers(match, now);
            return true;

        } catch (ConditionalCheckFailedException e) {
            // Match already exists
            LOG.debug("Match {} already exists in database", matchId);
            return false;
        }
    }

    /**
     * Store each player's data for a match (for match lookups).
     */
    private void storeMatchPlayers(Match match, String now) {
        String matchId = match.metadata().matchId();

        for (Player player : match.players().all_players()) {
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("PK", AttributeValue.fromS("MATCH#" + matchId));
            item.put("SK", AttributeValue.fromS("PLAYER#" + player.puuid()));
            item.put("puuid", AttributeValue.fromS(player.puuid()));
            item.put("name", AttributeValue.fromS(player.name()));
            item.put("tag", AttributeValue.fromS(player.tag()));
            item.put("team", AttributeValue.fromS(player.team()));
            item.put("character", AttributeValue.fromS(player.character()));
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

    /**
     * Process a single player's contribution for a match using transactions.
     */
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
            long gameStart
    ) {
        String markerPk = "PLAYER#" + puuid + "#MATCH#" + matchId;
        String aggregatePk = "PLAYER#" + puuid;
        String aggregateSk = "SEASON#" + seasonId;
        String now = Instant.now().toString();

        // Build marker item
        Map<String, AttributeValue> markerItem = new HashMap<>();
        markerItem.put("PK", AttributeValue.fromS(markerPk));
        markerItem.put("SK", AttributeValue.fromS("MARKER"));
        markerItem.put("matchId", AttributeValue.fromS(matchId));
        markerItem.put("gameStart", AttributeValue.fromN(String.valueOf(gameStart)));
        markerItem.put("processed_at", AttributeValue.fromS(now));

        // Conditional Put for marker (fails if already exists)
        Put putMarker = Put.builder()
                .tableName(tableName)
                .item(markerItem)
                .conditionExpression("attribute_not_exists(PK)")
                .build();

        // Build expression attribute values
        Map<String, AttributeValue> exprValues = new HashMap<>();
        exprValues.put(":zero", AttributeValue.fromN("0"));
        exprValues.put(":one", AttributeValue.fromN("1"));
        exprValues.put(":kills", AttributeValue.fromN(Long.toString(kills)));
        exprValues.put(":deaths", AttributeValue.fromN(Long.toString(deaths)));
        exprValues.put(":assists", AttributeValue.fromN(Long.toString(assists)));
        exprValues.put(":score", AttributeValue.fromN(Long.toString(score)));
        exprValues.put(":headshots", AttributeValue.fromN(Long.toString(headshots)));
        exprValues.put(":bodyshots", AttributeValue.fromN(Long.toString(bodyshots)));
        exprValues.put(":legshots", AttributeValue.fromN(Long.toString(legshots)));
        exprValues.put(":damage", AttributeValue.fromN(Long.toString(damageMade)));
        exprValues.put(":matchId", AttributeValue.fromS(matchId));
        exprValues.put(":now", AttributeValue.fromS(now));

        // Update aggregate increments
        Update updateAgg = Update.builder()
                .tableName(tableName)
                .key(Map.of(
                        "PK", AttributeValue.fromS(aggregatePk),
                        "SK", AttributeValue.fromS(aggregateSk)
                ))
                .updateExpression(
                        "SET matches_played = if_not_exists(matches_played, :zero) + :one, " +
                                "total_kills = if_not_exists(total_kills, :zero) + :kills, " +
                                "total_deaths = if_not_exists(total_deaths, :zero) + :deaths, " +
                                "total_assists = if_not_exists(total_assists, :zero) + :assists, " +
                                "total_score = if_not_exists(total_score, :zero) + :score, " +
                                "total_headshots = if_not_exists(total_headshots, :zero) + :headshots, " +
                                "total_bodyshots = if_not_exists(total_bodyshots, :zero) + :bodyshots, " +
                                "total_legshots = if_not_exists(total_legshots, :zero) + :legshots, " +
                                "total_damage = if_not_exists(total_damage, :zero) + :damage, " +
                                "lastProcessedMatchId = :matchId, updatedAt = :now"
                )
                .expressionAttributeValues(exprValues)
                .build();

        TransactWriteItem tPut = TransactWriteItem.builder().put(putMarker).build();
        TransactWriteItem tUpdate = TransactWriteItem.builder().update(updateAgg).build();

        TransactWriteItemsRequest tx = TransactWriteItemsRequest.builder()
                .transactItems(tPut, tUpdate)
                .build();

        try {
            ddb.transactWriteItems(tx);
            LOG.debug("Processed match {} for player {}", matchId, puuid);
        } catch (TransactionCanceledException e) {
            // Marker existed; already processed
            LOG.debug("Match {} already processed for player {}", matchId, puuid);
        }
    }

    /**
     * Get all match IDs for a player (for determining what's already stored).
     */
    public List<String> getStoredMatchIdsForPlayer(String puuid) {
        String prefix = "PLAYER#" + puuid + "#MATCH#";

        QueryResponse response = ddb.query(QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("begins_with(PK, :prefix) AND SK = :marker")
                .expressionAttributeValues(Map.of(
                        ":prefix", AttributeValue.fromS(prefix),
                        ":marker", AttributeValue.fromS("MARKER")
                ))
                .build());

        return response.items().stream()
                .map(item -> item.get("matchId").s())
                .toList();
    }
}
