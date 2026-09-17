package com.gondolia.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class LotNumbersTest {

    @ParameterizedTest
    @CsvSource({
            "'l-2409/a ', L2409A",
            "L2409A, L2409A",
            "'  lote: 123-b ', LOTE123B",
            "'ab.c_d e', ABCDE",
            "0001, 0001"
    })
    void normalizesToUppercaseAlphanumeric(String raw, String expected) {
        assertThat(LotNumbers.normalize(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "-/.", " ñ "})
    void returnsNullWhenNothingRemains(String raw) {
        assertThat(LotNumbers.normalize(raw)).isNull();
    }

    @Test
    void removesNonAsciiLetters() {
        assertThat(LotNumbers.normalize("añ9")).isEqualTo("A9");
    }

    @Test
    void cleanTrimsAndKeepsOriginalFormat() {
        assertThat(LotNumbers.clean("  l-2409/a ")).isEqualTo("l-2409/a");
        assertThat(LotNumbers.clean("   ")).isNull();
        assertThat(LotNumbers.clean(null)).isNull();
    }
}
