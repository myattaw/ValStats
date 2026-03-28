package com.valstats.service;

import com.valstats.client.ValorantApiClient;
import com.valstats.service.match.MatchProcessor;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service for syncing match data from the Valorant API and writing to DynamoDB.
 * Used by the match-sync-lambda for periodic data updates.
 */
@Singleton
public class MatchSyncService {

    private static final Logger LOG = LoggerFactory.getLogger(MatchSyncService.class);

    private final ValorantApiClient apiClient;
    private final MatchProcessor matchProcessor;
    private final DynamoDbService dynamoDbService;
    private final String apiKey;

    public MatchSyncService(
            ValorantApiClient apiClient,
            MatchProcessor matchProcessor,
            DynamoDbService dynamoDbService
    ) {
        this.apiClient = apiClient;
        this.matchProcessor = matchProcessor;
        this.dynamoDbService = dynamoDbService;
        this.apiKey = System.getenv("HDEV_KEY");
    }

    /**
     * Trigger a sync for a specific player's matches.
     * This fetches stored matches from the API and writes them to DynamoDB.
     */
    public void syncPlayerMatches(String region, String name, String tag) {
        try {
            LOG.info("Starting match sync for player: {}#{} in region: {}", name, tag, region);

            var storedMatches = apiClient.getStoredMatches(
                    region,
                    name,
                    tag,
                    20, // size
                    1,  // page
                    "competitive",
                    apiKey
            );

            if (storedMatches == null || storedMatches.data() == null) {
                LOG.warn("No match data returned from API for {}#{}", name, tag);
                return;
            }

            // Process and store each match
            int processedCount = 0;
            for (var match : storedMatches.data()) {
                // Get the PUUID from the player in the match data
                if (match.players() != null && !match.players().isEmpty()) {
                    var playerInMatch = match.players().stream()
                            .filter(p -> name.equalsIgnoreCase(p.name()) && tag.equalsIgnoreCase(p.tag()))
                            .findFirst();

                    if (playerInMatch.isPresent()) {
                        String puuid = playerInMatch.get().puuid();
                        if (matchProcessor.processStoredMatchSummary(match, puuid)) {
                            processedCount++;
                        }
                    }
                }
            }

            LOG.info("Successfully processed {} matches for {}#{}", processedCount, name, tag);

        } catch (Exception e) {
            LOG.error("Failed to sync matches for {}#{}", name, tag, e);
        }
    }

    /**
     * Update the last match sync timestamp for a player.
     */
    public void updatePlayerLastSync(String region, String name, String tag) {
        try {
            dynamoDbService.updatePlayerLastRecentMatchUpdate(region, name, tag);
            LOG.info("Updated last sync timestamp for {}#{}", name, tag);
        } catch (Exception e) {
            LOG.error("Failed to update sync timestamp for {}#{}", name, tag, e);
        }
    }
}

