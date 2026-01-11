package com.valstats.model.player;

import com.valstats.model.assets.Assets;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;

@Introspected
@Serdeable.Deserializable
@Serdeable.Serializable
public record Player(String puuid, String name, String tag, String team, String character,
                     int currenttier, String currenttier_patched, Stats stats, Assets assets, int damage_made) {
}
