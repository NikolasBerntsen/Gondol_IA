package com.gondolia.movements;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Parseo del CSV de ventas del POS propio (SPEC §6.4): separadores, fechas en los formatos aceptados,
 * importes con coma o punto decimal y celdas entrecomilladas.
 */
class SalesCsvParsingTest {

    // ------------------------------------------------------------------ fechas

    @Test
    void parsesTheThreeAcceptedDateFormats() {
        assertThat(SalesCsvService.parseDate("2026-09-15")).contains(LocalDate.of(2026, 9, 15));
        assertThat(SalesCsvService.parseDate("15/09/2026")).contains(LocalDate.of(2026, 9, 15));
        assertThat(SalesCsvService.parseDate("15-09-2026")).contains(LocalDate.of(2026, 9, 15));
    }

    @Test
    void trimsTheDateBeforeParsingIt() {
        assertThat(SalesCsvService.parseDate("  2026-09-15  ")).contains(LocalDate.of(2026, 9, 15));
    }

    @Test
    void rejectsDatesThatDoNotExistOrAreNotDates() {
        assertThat(SalesCsvService.parseDate("31/02/2026")).isEmpty();
        assertThat(SalesCsvService.parseDate("15/9/26")).isEmpty();
        assertThat(SalesCsvService.parseDate("ayer")).isEmpty();
        assertThat(SalesCsvService.parseDate("")).isEmpty();
        assertThat(SalesCsvService.parseDate(null)).isEmpty();
    }

    // ------------------------------------------------------------------ importes

    @Test
    void parsesAmountsWithDotOrCommaDecimals() {
        assertThat(SalesCsvService.parseAmount("1850.00")).isEqualByComparingTo("1850.00");
        assertThat(SalesCsvService.parseAmount("1850,50")).isEqualByComparingTo("1850.50");
        assertThat(SalesCsvService.parseAmount("980")).isEqualByComparingTo("980");
    }

    @Test
    void parsesAmountsWithThousandSeparatorsAndCurrencySigns() {
        assertThat(SalesCsvService.parseAmount("$ 1.850,50")).isEqualByComparingTo("1850.50");
        assertThat(SalesCsvService.parseAmount("1,850.50")).isEqualByComparingTo("1850.50");
    }

    @Test
    void returnsNullForAnEmptyOrInvalidAmount() {
        assertThat(SalesCsvService.parseAmount("")).isNull();
        assertThat(SalesCsvService.parseAmount("   ")).isNull();
        assertThat(SalesCsvService.parseAmount(null)).isNull();
        assertThat(SalesCsvService.parseAmount("gratis")).isNull();
    }

    // ------------------------------------------------------------------ separación de celdas

    @Test
    void splitsPlainCommaSeparatedLines() {
        assertThat(SalesCsvService.splitLine("2026-09-15,7791234000012,3,1850.00", ','))
                .containsExactly("2026-09-15", "7791234000012", "3", "1850.00");
    }

    @Test
    void splitsSemicolonSeparatedLines() {
        assertThat(SalesCsvService.splitLine("2026-09-15;7791234000012;3;1850,00", ';'))
                .containsExactly("2026-09-15", "7791234000012", "3", "1850,00");
    }

    @Test
    void keepsSeparatorsThatAreInsideQuotedCells() {
        assertThat(SalesCsvService.splitLine("2026-09-15,\"7791234000012\",\"1,5\",100", ','))
                .containsExactly("2026-09-15", "7791234000012", "1,5", "100");
    }

    @Test
    void unescapesDoubledQuotesInsideACell() {
        assertThat(SalesCsvService.splitLine("\"dice \"\"hola\"\"\",2", ','))
                .containsExactly("dice \"hola\"", "2");
    }

    @Test
    void keepsEmptyCells() {
        assertThat(SalesCsvService.splitLine("2026-09-15,7791234000012,3,", ','))
                .containsExactly("2026-09-15", "7791234000012", "3", "");
    }

    // ------------------------------------------------------------------ plantilla

    @Test
    void theTemplateHasTheDocumentedHeaderAndParsableRows() {
        String[] lines = SalesCsvService.TEMPLATE.strip().split("\n");
        assertThat(lines[0].strip()).isEqualTo("fecha,codigo_barras,cantidad,precio_unitario");
        for (int i = 1; i < lines.length; i++) {
            String[] cells = SalesCsvService.splitLine(lines[i].strip(), ',');
            assertThat(cells).as("fila %s", i).hasSize(4);
            assertThat(SalesCsvService.parseDate(cells[0])).as("fecha de la fila %s", i).isPresent();
            assertThat(SalesCsvService.parseAmount(cells[3])).as("precio de la fila %s", i)
                    .isNotNull()
                    .isGreaterThan(BigDecimal.ZERO);
        }
    }
}
