package com.valstats.service.match;

import com.valstats.model.stored.StoredMatchesResponse;
import com.valstats.service.player.PlayerNameRecorder;
import com.valstats.service.SeasonNames;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Singleton
public class MatchProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(MatchProcessor.class);

    private final DynamoDbClient ddb;
    private final List<PlayerNameRecorder> playerNameRecorders;
    @Value("${dynamodb.table-name:valstats}")
    private String tableName = "valstats";

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
        String seasonShort = season != null ? SeasonNames.normalizeShortCode(season.shortName()) : "";
        String seasonName = SeasonNames.format(seasonShort);
        storeSeasonMetadata(seasonId, seasonShort, seasonName);
        String mode = normalizeMode(meta.mode());
        String modeName = displayMode(meta.mode());

        int redRounds = extractTeamRounds(match.teams(), "red");
        int blueRounds = extractTeamRounds(match.teams(), "blue");

        long damageMade = damage != null ? damage.made() : 0L;

        int tier = stats.tier();
        long gameStart = parseDateRaw(meta.startedAt());

        boolean newlyProcessed = processPlayerMatch(
                puuid,
                seasonId,
                seasonShort,
                seasonName,
                mode,
                modeName,
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
                str(meta.cluster()),
                redRounds,
                blueRounds,
                tier
        );

        if (newlyProcessed) {
            String outcome = outcome(str(stats.team()), redRounds, blueRounds);
            updateDimensionAggregate("PLAYER#" + puuid, "MAP#" + dimensionKey(
                            mapObj != null ? str(mapObj.id()) : str(mapObj != null ? mapObj.name() : "unknown")),
                    "mapName", mapObj != null ? str(mapObj.name()) : "Unknown",
                    outcome, stats.kills(), stats.deaths(), stats.assists());
            updateDimensionAggregate("PLAYER#" + puuid, "AGENT#" + dimensionKey(
                            character != null ? str(character.id()) : str(character != null ? character.name() : "unknown")),
                    "agentName", character != null ? str(character.name()) : "Unknown",
                    outcome, stats.kills(), stats.deaths(), stats.assists());
            updateSocialAggregates(match, puuid, str(stats.team()), outcome);
        }

        recordPlayerNames(match, gameStart);

        return true;
    }

    public boolean processPlayerMatch(
            String puuid,
            String seasonId,
            String seasonShort,
            String seasonName,
            String mode,
            String modeName,
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
            String server,
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
        if (!seasonShort.isBlank()) marker.put("seasonShort", AttributeValue.fromS(seasonShort));
        if (!seasonName.isBlank()) marker.put("seasonName", AttributeValue.fromS(seasonName));
        marker.put("mode", AttributeValue.fromS(mode));
        marker.put("modeName", AttributeValue.fromS(modeName));
        marker.put("map", AttributeValue.fromS(nullSafe(map)));
        marker.put("mapId", AttributeValue.fromS(nullSafe(mapId)));
        marker.put("agentName", AttributeValue.fromS(nullSafe(agentName)));
        marker.put("agentId", AttributeValue.fromS(nullSafe(agentId)));
        marker.put("team", AttributeValue.fromS(nullSafe(team)));
        if (!server.isBlank()) marker.put("server", AttributeValue.fromS(server));
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
        marker.put("damageSchemaVersion", AttributeValue.fromN("2"));
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
            updateExistingMatchMetadata(
                    pk, sk, seasonShort, seasonName, mode, modeName,
                    server, damageMade, roundsPlayed, adr);
            LOG.debug("Match {} already processed for {}", matchId, puuid);
            return false;
        } catch (DynamoDbException e) {
            LOG.error("Failed to write marker for match {}", matchId, e);
            return false; // do NOT continue if marker fails
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
        return true;
    }

    /**
     * Persists one large stored-match section using retry-safe overwrite rows.
     * Aggregate rows are scoped to the section, so retrying the same page replaces
     * its contribution instead of incrementing counters twice.
     */
    public int processStoredMatchBatch(
            List<StoredMatchesResponse.StoredMatch> matches, String puuid, int page, boolean includeInsights) {
        Map<String, Map<String, AttributeValue>> uniqueRows = new LinkedHashMap<>();
        Map<String, BulkAggregate> aggregates = new LinkedHashMap<>();
        int accepted = 0;
        for (StoredMatchesResponse.StoredMatch match : matches) {
            Map<String, AttributeValue> row = storedMatchItem(match, puuid);
            if (row == null) continue;
            String itemKey = row.get("PK").s() + "\u0000" + row.get("SK").s();
            if (uniqueRows.putIfAbsent(itemKey, row) != null) continue;
            accepted++;
            if (includeInsights) collectBulkAggregates(match, puuid, aggregates);
        }
        List<Map<String, AttributeValue>> rows = new ArrayList<>(uniqueRows.values());
        if (includeInsights) {
            String segment = String.format("BULK#V1#%05d#", page);
            aggregates.forEach((key, aggregate) -> rows.add(aggregate.toItem(puuid, segment + key)));
        }
        batchPut(rows);
        LOG.info("Bulk-persisted {} matches and {} insight rows for {} page {}",
                accepted, aggregates.size(), puuid, page);
        return accepted;
    }

    private Map<String, AttributeValue> storedMatchItem(StoredMatchesResponse.StoredMatch match, String puuid) {
        if (match == null || match.meta() == null || match.stats() == null) return null;
        StoredMatchesResponse.Meta meta = match.meta();
        StoredMatchesResponse.Stats stats = match.stats();
        String matchId = str(meta.id());
        if (matchId.isBlank()) return null;
        StoredMatchesResponse.Season season = meta.season();
        String seasonId = season == null || str(season.id()).isBlank() ? "unknown" : str(season.id());
        String seasonShort = season == null ? "" : SeasonNames.normalizeShortCode(season.shortName());
        String seasonName = SeasonNames.format(seasonShort);
        StoredMatchesResponse.Shots shots = stats.shots();
        StoredMatchesResponse.Damage damage = stats.damage();
        StoredMatchesResponse.MapInfo map = meta.map();
        StoredMatchesResponse.Character agent = stats.character();
        long started = parseDateRaw(meta.startedAt());
        long red = extractTeamRounds(match.teams(), "red");
        long blue = extractTeamRounds(match.teams(), "blue");
        long rounds = red + blue;
        long damageMade = damage == null ? 0 : damage.made();
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("PK", AttributeValue.fromS("PLAYER#" + puuid));
        item.put("SK", AttributeValue.fromS(String.format("SEASON#%s#MATCH#%013d#%s", seasonId, started, matchId)));
        item.put("GSI1PK", AttributeValue.fromS("PLAYER#" + puuid));
        item.put("GSI1SK", AttributeValue.fromN(String.valueOf(started)));
        putS(item, "matchId", matchId); putN(item, "gameStart", started); putS(item, "seasonId", seasonId);
        if (!seasonShort.isBlank()) putS(item, "seasonShort", seasonShort);
        if (!seasonName.isBlank()) putS(item, "seasonName", seasonName);
        putS(item, "mode", normalizeMode(meta.mode())); putS(item, "modeName", displayMode(meta.mode()));
        putS(item, "map", map == null ? "" : str(map.name())); putS(item, "mapId", map == null ? "" : str(map.id()));
        putS(item, "agentName", agent == null ? "" : str(agent.name())); putS(item, "agentId", agent == null ? "" : str(agent.id()));
        putS(item, "team", str(stats.team())); putS(item, "server", str(meta.cluster()));
        putN(item, "kills", stats.kills()); putN(item, "deaths", stats.deaths()); putN(item, "assists", stats.assists());
        putN(item, "score", stats.score()); putN(item, "headshots", shots == null ? 0 : shots.head());
        putN(item, "bodyshots", shots == null ? 0 : shots.body()); putN(item, "legshots", shots == null ? 0 : shots.leg());
        putN(item, "damage_made", damageMade); putN(item, "rounds_played", rounds);
        item.put("adr", AttributeValue.fromN(String.valueOf(rounds == 0 ? 0.0 : (double) damageMade / rounds)));
        putN(item, "damageSchemaVersion", 2); putN(item, "redRoundsWon", red); putN(item, "blueRoundsWon", blue);
        putN(item, "tier", stats.tier()); putS(item, "processed_at", Instant.now().toString());
        return item;
    }

    private void collectBulkAggregates(StoredMatchesResponse.StoredMatch match, String puuid,
                                       Map<String, BulkAggregate> aggregates) {
        StoredMatchesResponse.Stats stats = match.stats();
        String result = outcome(str(stats.team()), extractTeamRounds(match.teams(), "red"),
                extractTeamRounds(match.teams(), "blue"));
        StoredMatchesResponse.MapInfo map = match.meta().map();
        StoredMatchesResponse.Character agent = stats.character();
        addAggregate(aggregates, "MAP#" + dimensionKey(map == null ? "unknown" : str(map.id())),
                "MAP", map == null ? "Unknown" : str(map.name()), null, result, stats);
        addAggregate(aggregates, "AGENT#" + dimensionKey(agent == null ? "unknown" : str(agent.id())),
                "AGENT", agent == null ? "Unknown" : str(agent.name()), null, result, stats);
        if (match.players() == null) return;
        for (StoredMatchesResponse.Player player : match.players()) {
            if (player == null || str(player.puuid()).isBlank() || puuid.equals(player.puuid())) continue;
            boolean with = str(player.team()).equalsIgnoreCase(stats.team());
            addAggregate(aggregates, "SOCIAL#" + (with ? "WITH#" : "AGAINST#") + player.puuid(),
                    with ? "WITH" : "AGAINST", str(player.name()), player, result, null);
        }
    }

    private void addAggregate(Map<String, BulkAggregate> values, String key, String kind, String label,
                              StoredMatchesResponse.Player player, String outcome, StoredMatchesResponse.Stats stats) {
        BulkAggregate aggregate = values.computeIfAbsent(key, ignored -> new BulkAggregate(kind, label,
                player == null ? "" : str(player.puuid()), player == null ? "" : str(player.tag())));
        aggregate.add(outcome, stats);
    }

    private void batchPut(List<Map<String, AttributeValue>> items) {
        for (int offset = 0; offset < items.size(); offset += 25) {
            List<WriteRequest> writes = items.subList(offset, Math.min(offset + 25, items.size())).stream()
                    .map(item -> WriteRequest.builder().putRequest(PutRequest.builder().item(item).build()).build()).toList();
            Map<String, List<WriteRequest>> pending = Map.of(tableName, writes);
            for (int attempt = 0; !pending.isEmpty(); attempt++) {
                BatchWriteItemResponse response = ddb.batchWriteItem(
                        BatchWriteItemRequest.builder().requestItems(pending).build());
                pending = response.unprocessedItems();
                if (pending == null || pending.isEmpty()) break;
                if (attempt >= 7) throw DynamoDbException.builder()
                        .message("DynamoDB bulk write remained unprocessed").build();
                try { TimeUnit.MILLISECONDS.sleep(Math.min(1_000L, 25L << attempt)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            }
        }
    }

    private void putS(Map<String, AttributeValue> item, String key, String value) {
        item.put(key, AttributeValue.fromS(value == null ? "" : value));
    }
    private void putN(Map<String, AttributeValue> item, String key, long value) {
        item.put(key, AttributeValue.fromN(String.valueOf(value)));
    }

    private static final class BulkAggregate {
        private final String kind; private final String label; private final String playerPuuid; private final String tag;
        private long games, wins, losses, draws, kills, deaths, assists;
        private BulkAggregate(String kind, String label, String playerPuuid, String tag) {
            this.kind = kind; this.label = label; this.playerPuuid = playerPuuid; this.tag = tag;
        }
        private void add(String outcome, StoredMatchesResponse.Stats stats) {
            games++; if ("win".equals(outcome)) wins++; else if ("loss".equals(outcome)) losses++; else draws++;
            if (stats != null) { kills += stats.kills(); deaths += stats.deaths(); assists += stats.assists(); }
        }
        private Map<String, AttributeValue> toItem(String puuid, String sk) {
            Map<String, AttributeValue> item = new HashMap<>();
            item.put("PK", AttributeValue.fromS("PLAYER#" + puuid)); item.put("SK", AttributeValue.fromS(sk));
            item.put("kind", AttributeValue.fromS(kind)); item.put("label", AttributeValue.fromS(label));
            if (!playerPuuid.isBlank()) item.put("playerPuuid", AttributeValue.fromS(playerPuuid));
            if (!tag.isBlank()) item.put("tag", AttributeValue.fromS(tag));
            item.put("games", AttributeValue.fromN(String.valueOf(games))); item.put("wins", AttributeValue.fromN(String.valueOf(wins)));
            item.put("losses", AttributeValue.fromN(String.valueOf(losses))); item.put("draws", AttributeValue.fromN(String.valueOf(draws)));
            item.put("kills", AttributeValue.fromN(String.valueOf(kills))); item.put("deaths", AttributeValue.fromN(String.valueOf(deaths)));
            item.put("assists", AttributeValue.fromN(String.valueOf(assists)));
            return item;
        }
    }

    private void updateDimensionAggregate(
            String pk, String sk, String labelField, String label, String outcome,
            long kills, long deaths, long assists) {
        Map<String, String> names = Map.of("#label", labelField);
        Map<String, AttributeValue> values = aggregateValues(label, outcome, kills, deaths, assists);
        String update = "SET #label = :label, games = if_not_exists(games, :zero) + :one, "
                + "wins = if_not_exists(wins, :zero) + :wins, losses = if_not_exists(losses, :zero) + :losses, "
                + "draws = if_not_exists(draws, :zero) + :draws, kills = if_not_exists(kills, :zero) + :kills, "
                + "deaths = if_not_exists(deaths, :zero) + :deaths, assists = if_not_exists(assists, :zero) + :assists";
        updateItem(pk, sk, update, names, values);
    }

    private void updateSocialAggregates(
            StoredMatchesResponse.StoredMatch match, String playerPuuid, String playerTeam, String outcome) {
        if (match.players() == null) return;
        for (StoredMatchesResponse.Player other : match.players()) {
            if (other == null || str(other.puuid()).isBlank() || playerPuuid.equals(other.puuid())) continue;
            boolean teammate = str(other.team()).equalsIgnoreCase(playerTeam);
            String sk = (teammate ? "SOCIAL#WITH#" : "SOCIAL#AGAINST#") + other.puuid();
            Map<String, AttributeValue> values = aggregateValues(
                    str(other.name()) + "#" + str(other.tag()), outcome, 0, 0, 0);
            values.remove(":label");
            values.remove(":kills");
            values.remove(":deaths");
            values.remove(":assists");
            values.put(":name", AttributeValue.fromS(str(other.name())));
            values.put(":tag", AttributeValue.fromS(str(other.tag())));
            values.put(":puuid", AttributeValue.fromS(str(other.puuid())));
            String update = "SET playerPuuid = :puuid, #n = :name, tag = :tag, games = if_not_exists(games, :zero) + :one, "
                    + "wins = if_not_exists(wins, :zero) + :wins, losses = if_not_exists(losses, :zero) + :losses, "
                    + "draws = if_not_exists(draws, :zero) + :draws";
            updateItem("PLAYER#" + playerPuuid, sk, update, Map.of("#n", "name"), values);
        }
    }

    private Map<String, AttributeValue> aggregateValues(
            String label, String outcome, long kills, long deaths, long assists) {
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":label", AttributeValue.fromS(label == null ? "" : label));
        values.put(":zero", AttributeValue.fromN("0"));
        values.put(":one", AttributeValue.fromN("1"));
        values.put(":wins", AttributeValue.fromN("win".equals(outcome) ? "1" : "0"));
        values.put(":losses", AttributeValue.fromN("loss".equals(outcome) ? "1" : "0"));
        values.put(":draws", AttributeValue.fromN("draw".equals(outcome) ? "1" : "0"));
        values.put(":kills", AttributeValue.fromN(String.valueOf(kills)));
        values.put(":deaths", AttributeValue.fromN(String.valueOf(deaths)));
        values.put(":assists", AttributeValue.fromN(String.valueOf(assists)));
        return values;
    }

    private void updateItem(String pk, String sk, String expression,
                            Map<String, String> names, Map<String, AttributeValue> values) {
        try {
            UpdateItemRequest.Builder request = UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of("PK", AttributeValue.fromS(pk), "SK", AttributeValue.fromS(sk)))
                    .updateExpression(expression)
                    .expressionAttributeValues(values);
            if (!names.isEmpty()) request.expressionAttributeNames(names);
            ddb.updateItem(request.build());
        } catch (DynamoDbException e) {
            LOG.warn("Failed to update breakdown {} for {}", sk, pk, e);
        }
    }

    private String outcome(String team, int redRounds, int blueRounds) {
        if (redRounds == blueRounds) return "draw";
        boolean redWon = redRounds > blueRounds;
        boolean playerRed = "red".equalsIgnoreCase(team);
        return redWon == playerRed ? "win" : "loss";
    }

    private String dimensionKey(String value) {
        String normalized = value == null ? "" : value.replaceAll("[^A-Za-z0-9-]", "").toLowerCase();
        return normalized.isBlank() ? "unknown" : normalized;
    }

    private void updateExistingMatchMetadata(
            String pk, String sk, String seasonShort, String seasonName, String mode, String modeName,
            String server, long damageMade, long roundsPlayed, double adr) {
        Map<String, AttributeValue> values = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        names.put("#mode", "mode");
        values.put(":mode", AttributeValue.fromS(mode));
        values.put(":modeName", AttributeValue.fromS(modeName));
        values.put(":damage", AttributeValue.fromN(String.valueOf(damageMade)));
        values.put(":rounds", AttributeValue.fromN(String.valueOf(roundsPlayed)));
        values.put(":adr", AttributeValue.fromN(String.valueOf(adr)));
        values.put(":damageSchemaVersion", AttributeValue.fromN("2"));
        String expression = "SET #mode = :mode, modeName = :modeName, "
                + "damage_made = :damage, rounds_played = :rounds, adr = :adr, "
                + "damageSchemaVersion = :damageSchemaVersion";
        if (!seasonShort.isBlank() && !seasonName.isBlank()) {
            expression += ", seasonShort = :seasonShort, seasonName = :seasonName";
            values.put(":seasonShort", AttributeValue.fromS(seasonShort));
            values.put(":seasonName", AttributeValue.fromS(seasonName));
        }
        if (!server.isBlank()) {
            expression += ", #server = :server";
            names.put("#server", "server");
            values.put(":server", AttributeValue.fromS(server));
        }
        try {
            ddb.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(Map.of("PK", AttributeValue.fromS(pk), "SK", AttributeValue.fromS(sk)))
                    .updateExpression(expression)
                    .expressionAttributeNames(names)
                    .expressionAttributeValues(values)
                    .build());
        } catch (DynamoDbException updateError) {
            LOG.warn("Failed to backfill metadata for {}", sk, updateError);
        }
    }

    private void storeSeasonMetadata(String seasonId, String seasonShort, String seasonName) {
        if (seasonId.isBlank() || "unknown".equals(seasonId) || seasonName.isBlank()) return;
        try {
            ddb.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(Map.of(
                            "PK", AttributeValue.fromS("METADATA#SEASONS"),
                            "SK", AttributeValue.fromS("SEASON#" + seasonId),
                            "seasonId", AttributeValue.fromS(seasonId),
                            "seasonShort", AttributeValue.fromS(seasonShort),
                            "seasonName", AttributeValue.fromS(seasonName),
                            "updatedAt", AttributeValue.fromS(Instant.now().toString())
                    ))
                    .build());
        } catch (DynamoDbException e) {
            LOG.warn("Failed to store season metadata for {}", seasonId, e);
        }
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

    private String normalizeMode(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.replaceAll("[^A-Za-z0-9]", "").toLowerCase(java.util.Locale.ROOT);
    }

    private String displayMode(String value) {
        if (value == null || value.isBlank()) return "Unknown";
        String spaced = value.trim().replaceAll("([a-z])([A-Z])", "$1 $2").replace('_', ' ');
        String[] words = spaced.split("\\s+");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0)))
                    .append(word.substring(1).toLowerCase(java.util.Locale.ROOT));
        }
        return result.toString();
    }
}

