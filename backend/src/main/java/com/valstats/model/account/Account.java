package com.valstats.model.account;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;

@Introspected
@Serdeable.Deserializable
@Serdeable.Serializable
public record Account(int status, Data data) {

    @Introspected
    @Serdeable.Deserializable
    @Serdeable.Serializable
    public record Data(
            String puuid,
            String region,
            int account_level,
            String name,
            String tag,
            Card card,
            String last_update,
            long last_update_raw
    ) {}

    @Introspected
    @Serdeable.Deserializable
    @Serdeable.Serializable
    public record Card(
            String small,
            String large,
            String wide,
            String id
    ) {}
}
