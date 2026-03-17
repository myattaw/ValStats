package com.valstats.model.player;

import com.valstats.model.assets.Assets;
import com.valstats.model.assets.Agent;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;

@Introspected
@Serdeable.Deserializable
@Serdeable.Serializable
public record Player(
        String puuid,
        String name,
        String tag,
        String team,
        Agent agent,
        int currenttier,
        String currenttier_patched,
        Stats stats,
        Assets assets,
        int damage_made
) { }