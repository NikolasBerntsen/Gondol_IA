package com.gondolia.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.gondolia.imports.parse.ImportValues;
import com.gondolia.imports.parse.ImportValues.DateOrder;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Parseo de celdas de una planilla argentina (SPEC §16.2). */
class ImportValuesTest {

    @Nested
    @DisplayName("Números es-AR y en")
    class Numbers {

        @ParameterizedTest(name = "«{0}» → {1}")
        @CsvSource({
                "'$ 1.234,50', 1234.50",
                "'1.234,50', 1234.50",
                "'1234.5', 1234.5",
                "'1234,5', 1234.5",
                "'1.234', 1234",
                "'1.234.567', 1234567",
                "'1234', 1234",
                "'0,99', 0.99",
                "'  950,00  ', 950.00",
                "'12,5%', 12.5",
                "'$1350', 1350",
                "'-120,50', -120.50",
                "'1,234.50', 1234.50",
        })
        void parsesNumbers(String raw, String expected) {
            assertThat(ImportValues.number(raw)).hasValueSatisfying(
                    value -> assertThat(value).isEqualByComparingTo(new BigDecimal(expected)));
        }

        @ParameterizedTest
        @ValueSource(strings = {"mil doscientos", "abc", "-", "$", "1.2.3,4,5", ""})
        void rejectsNonNumbers(String raw) {
            assertThat(ImportValues.number(raw)).isEmpty();
        }

        @Test
        void detectsIntegers() {
            assertThat(ImportValues.isInteger("24")).isTrue();
            assertThat(ImportValues.isInteger("24,00")).isTrue();
            assertThat(ImportValues.isInteger("1.200")).isTrue();
            assertThat(ImportValues.isInteger("12,5")).isFalse();
            assertThat(ImportValues.isInteger("abc")).isFalse();
        }
    }

    @Nested
    @DisplayName("Fechas")
    class Dates {

        @ParameterizedTest(name = "«{0}» → {1}")
        @CsvSource({
                "'15/10/2026', 2026-10-15",
                "'15-10-2026', 2026-10-15",
                "'15.10.2026', 2026-10-15",
                "'15/10/26', 2026-10-15",
                "'2026-10-15', 2026-10-15",
                "'1/2/2026', 2026-02-01",
        })
        void parsesDmy(String raw, LocalDate expected) {
            assertThat(ImportValues.date(raw, DateOrder.DMY)).contains(expected);
        }

        @Test
        void parsesMdyAndYmdWhenAsked() {
            assertThat(ImportValues.date("10/15/2026", DateOrder.MDY)).contains(LocalDate.of(2026, 10, 15));
            assertThat(ImportValues.date("2026/10/15", DateOrder.YMD)).contains(LocalDate.of(2026, 10, 15));
        }

        @Test
        void fallsBackWhenTheChosenOrderIsImpossible() {
            // "25/12/2026" leído como MDY no existe: se corrige a DMY en vez de dar error.
            assertThat(ImportValues.date("25/12/2026", DateOrder.MDY)).contains(LocalDate.of(2026, 12, 25));
        }

        @Test
        void parsesExcelSerials() {
            // 46310 = 15/10/2026 en el sistema de fechas 1900 de Excel.
            assertThat(ImportValues.date("46310", DateOrder.DMY)).contains(LocalDate.of(2026, 10, 15));
            assertThat(ImportValues.excelSerial(46_310)).contains(LocalDate.of(2026, 10, 15));
        }

        @ParameterizedTest
        @ValueSource(strings = {"31/02/2026", "45/10/2026", "ayer", "10//2026", "2026", "0"})
        void rejectsInvalidDates(String raw) {
            assertThat(ImportValues.date(raw, DateOrder.DMY)).isEmpty();
        }

        @Test
        void acceptsInstantsExportedByOtherTools() {
            assertThat(ImportValues.date("2026-10-15 00:00:00", DateOrder.DMY)).contains(LocalDate.of(2026, 10, 15));
            assertThat(ImportValues.date("2026-10-15T00:00", DateOrder.DMY)).contains(LocalDate.of(2026, 10, 15));
        }
    }

    @Nested
    @DisplayName("Booleanos y texto")
    class BooleansAndText {

        @ParameterizedTest
        @ValueSource(strings = {"si", "Sí", "SI", "true", "1", "x", "X", "verdadero"})
        void readsTrue(String raw) {
            assertThat(ImportValues.bool(raw)).contains(true);
        }

        @ParameterizedTest
        @ValueSource(strings = {"no", "No", "false", "0", "falso"})
        void readsFalse(String raw) {
            assertThat(ImportValues.bool(raw)).contains(false);
        }

        @ParameterizedTest
        @ValueSource(strings = {"quizás", "", "  "})
        void rejectsOtherBooleans(String raw) {
            assertThat(ImportValues.bool(raw)).isEmpty();
        }

        @Test
        void trimsAndCollapsesSpaces() {
            assertThat(ImportValues.text("  Leche   entera  ")).isEqualTo("Leche entera");
            assertThat(ImportValues.text(" ")).isNull();
            assertThat(ImportValues.text(null)).isNull();
        }

        @Test
        void slugIgnoresAccentsCaseAndPunctuation() {
            assertThat(ImportValues.slug("Código de Barras")).isEqualTo("codigodebarras");
            assertThat(ImportValues.slug("N° Lote")).isEqualTo("nlote");
            assertThat(ImportValues.slug("U. Medida")).isEqualTo("umedida");
            assertThat(ImportValues.slug(null)).isEmpty();
        }
    }
}
