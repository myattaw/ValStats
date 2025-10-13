package com.valstats.model.player;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;
import java.util.List;

@Introspected
@Serdeable.Deserializable
@Serdeable.Serializable
public record Players(List<Player> all_players) {
}
