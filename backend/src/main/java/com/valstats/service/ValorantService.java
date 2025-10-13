package com.valstats.service;

import com.valstats.client.ValorantApiClient;
import com.valstats.model.Match;
import com.valstats.model.MatchResponse;
import com.valstats.model.player.Assets;
import com.valstats.model.player.Player;
import com.valstats.model.player.Players;
import com.valstats.model.player.Stats;
import jakarta.inject.Singleton;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Singleton
public class ValorantService {

    private final ValorantApiClient valorantApiClient;
    private static final String AUTH_TOKEN = System.getenv("HDEV_KEY");

    public ValorantService(ValorantApiClient valorantApiClient) {
        this.valorantApiClient = valorantApiClient;
    }

    public MatchResponse getMatches(String region, String playerName, String playerTag) {
        MatchResponse rawResponse = valorantApiClient.getMatches(region, playerName, playerTag, 10, AUTH_TOKEN);
        return filterMatchesResponse(rawResponse);
    }

    public Map<String, Object> getMMRHistory(String region, String playerName, String playerTag) {
        return valorantApiClient.getMMRHistory(region, playerName, playerTag, AUTH_TOKEN);
    }

    private MatchResponse filterMatchesResponse(MatchResponse rawResponse) {
        List<Match> filteredData = rawResponse.data().stream()
                .map(match -> {
                    List<Player> filteredPlayers = match.players().all_players().stream()
                            .map(player -> new Player(
                                    player.puuid(),
                                    player.name(),
                                    player.tag(),
                                    player.team(),
                                    player.character(),
                                    player.currenttier(),
                                    player.currenttier_patched(),
                                    new Stats(
                                            player.stats().score(),
                                            player.stats().kills(),
                                            player.stats().deaths(),
                                            player.stats().assists()
                                    ),
                                    player.assets() == null ? null : new Assets(player.assets().card() == null ? null :
                                            new Assets.Card(player.assets().card().small()),
                                            player.assets().agent() == null ? null : new Assets.Agent(player.assets().agent().small())
                                    )
                            ))
                            .collect(Collectors.toList());
                    // Pass through metadata and teams as well
                    return new Match(match.metadata(), new Players(filteredPlayers), match.teams());
                })
                .collect(Collectors.toList());
        return new MatchResponse(rawResponse.status(), filteredData);
    }

}
