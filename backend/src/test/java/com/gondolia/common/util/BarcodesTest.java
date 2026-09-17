package com.gondolia.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class BarcodesTest {

    @ParameterizedTest
    @CsvSource({
            "' 7791234500012 ', 7791234500012",
            "'779 1234 500012', 7791234500012",
            "'ABC-123\t', ABC-123",
            "96385074, 96385074"
    })
    void trimsAndRemovesWhitespace(String raw, String expected) {
        assertThat(Barcodes.normalize(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t \n"})
    void returnsNullWhenEmpty(String raw) {
        assertThat(Barcodes.normalize(raw)).isNull();
    }

    @Test
    void acceptsAlphanumericUpTo32Characters() {
        assertThat(Barcodes.isValid("7791234500012")).isTrue();
        assertThat(Barcodes.isValid("CODE128ABC")).isTrue();
        assertThat(Barcodes.isValid("A".repeat(32))).isTrue();
        assertThat(Barcodes.isValid("A".repeat(33))).isFalse();
        assertThat(Barcodes.isValid("ABC-123")).isFalse();
        assertThat(Barcodes.isValid(null)).isFalse();
    }

    @Test
    void validatesGtinCheckDigit() {
        assertThat(Barcodes.isValidGtin("4006381333931")).isTrue();  // EAN-13
        assertThat(Barcodes.isValidGtin("036000291452")).isTrue();   // UPC-A
        assertThat(Barcodes.isValidGtin("96385074")).isTrue();       // EAN-8
        assertThat(Barcodes.isValidGtin("4006381333932")).isFalse();
        assertThat(Barcodes.isValidGtin("12345")).isFalse();
        assertThat(Barcodes.isValidGtin("ABCDEFGH")).isFalse();
    }
}
