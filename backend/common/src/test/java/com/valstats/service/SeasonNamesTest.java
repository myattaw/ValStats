package com.valstats.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SeasonNamesTest {

    @Test
    void formatsHenrikShortCodeWithoutAUuidLookupTable() {
        assertEquals("Episode 4 Act 1", SeasonNames.format("e4a1"));
        assertEquals("Episode 12 Act 3", SeasonNames.format("E12A3"));
    }

    @Test
    void rejectsValuesThatCannotProduceAFormattedName() {
        assertEquals("", SeasonNames.format("573f53ac-41a5-3a7d-d9ce-d6a6298e5704"));
        assertEquals("", SeasonNames.format("unknown"));
    }
}
