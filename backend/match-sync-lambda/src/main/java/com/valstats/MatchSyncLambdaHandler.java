package com.valstats;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.valstats.config.LambdaApplicationContext;
import com.valstats.service.MatchSyncService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Lambda handler for the match-sync function.
 * Triggered by EventBridge on a schedule or by S3 events.
 *
 * Uses shared LambdaApplicationContext for efficient context reuse across invocations
 * in a warm Lambda container.
 */
@Singleton
public class MatchSyncLambdaHandler implements RequestHandler<Map<String, Object>, String> {

    private static final Logger LOG = LoggerFactory.getLogger(MatchSyncLambdaHandler.class);

    @Inject
    private MatchSyncService matchSyncService;

    @Override
    public String handleRequest(Map<String, Object> input, Context context) {
        try {
            LOG.info("Match Sync Lambda triggered with input: {}", input);

            // Get the shared application context (lazy-initialized on first invocation)
            var applicationContext = LambdaApplicationContext.getContext(ValstatsApplication.class);
            var handler = applicationContext.getBean(MatchSyncLambdaHandler.class);

            // Extract player details from input
            String region = (String) input.getOrDefault("region", "na");
            String name = (String) input.getOrDefault("name", null);
            String tag = (String) input.getOrDefault("tag", null);

            if (name == null || tag == null) {
                LOG.warn("Missing required parameters: name={}, tag={}", name, tag);
                return "ERROR: Missing name or tag parameters";
            }

            // Sync the player's matches
            handler.matchSyncService.syncPlayerMatches(region, name, tag);
            handler.matchSyncService.updatePlayerLastSync(region, name, tag);

            return "SUCCESS: Synced matches for " + name + "#" + tag;

        } catch (Exception e) {
            LOG.error("Error in match sync lambda handler", e);
            return "ERROR: " + e.getMessage();
        }
    }
}

