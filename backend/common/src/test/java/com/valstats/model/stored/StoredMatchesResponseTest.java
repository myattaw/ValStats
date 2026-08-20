package com.valstats.model.stored;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StoredMatchesResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsMadeFromStoredMatchDamagePayload() throws Exception {
        StoredMatchesResponse.Damage damage = objectMapper.readValue(
                "{\"made\":3600,\"received\":2800}",
                StoredMatchesResponse.Damage.class);

        assertEquals(3600, damage.made());
        assertEquals(2800, damage.received());
    }
}
