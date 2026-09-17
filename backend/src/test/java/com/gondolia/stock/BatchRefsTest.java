package com.gondolia.stock;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BatchRefsTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-17T17:30:12Z"),
            ZoneId.of("America/Argentina/Buenos_Aires"));

    @Test
    void usesPrefixBusinessTimeAndRandomSuffix() {
        assertThat(BatchRefs.sale(clock)).matches("S-20260917143012-[2-9A-HJ-NP-Z]{6}");
        assertThat(BatchRefs.adjustment(clock)).startsWith("A-20260917143012-");
        assertThat(BatchRefs.transfer(clock)).startsWith("T-20260917143012-").hasSize(23)
                .hasSizeLessThanOrEqualTo(BatchRefs.MAX_LENGTH);
    }

    @Test
    void referencesGeneratedInTheSameSecondAreDistinct() {
        Set<String> refs = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            refs.add(BatchRefs.sale(clock));
        }
        assertThat(refs).hasSize(500);
    }
}
