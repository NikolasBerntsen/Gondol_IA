package com.gondolia.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Estado de stock por sucursal y consolidado del alcance (SPEC §4.2). */
class StockStatusesTest {

    @Test
    void sin_stock_vendible_es_out() {
        assertThat(StockStatuses.of(0, 10)).isEqualTo(StockStatuses.OUT);
        assertThat(StockStatuses.of(0, 0)).isEqualTo(StockStatuses.OUT);
    }

    @Test
    void hasta_el_minimo_es_low() {
        assertThat(StockStatuses.of(10, 10)).isEqualTo(StockStatuses.LOW);
        assertThat(StockStatuses.of(3, 10)).isEqualTo(StockStatuses.LOW);
    }

    @Test
    void por_encima_del_minimo_es_ok() {
        assertThat(StockStatuses.of(11, 10)).isEqualTo(StockStatuses.OK);
        assertThat(StockStatuses.of(1, 0)).isEqualTo(StockStatuses.OK);
    }

    @Test
    void el_consolidado_toma_el_peor_estado() {
        assertThat(StockStatuses.worst(List.of(StockStatuses.OK, StockStatuses.LOW))).isEqualTo(StockStatuses.LOW);
        assertThat(StockStatuses.worst(List.of(StockStatuses.OK, StockStatuses.OUT, StockStatuses.LOW)))
                .isEqualTo(StockStatuses.OUT);
        assertThat(StockStatuses.worst(List.of(StockStatuses.OK, StockStatuses.OK))).isEqualTo(StockStatuses.OK);
    }

    @Test
    void sin_sucursales_el_consolidado_es_ok() {
        assertThat(StockStatuses.worst(List.of())).isEqualTo(StockStatuses.OK);
    }

    @Test
    void la_gravedad_ordena_out_primero() {
        assertThat(StockStatuses.rank(StockStatuses.OUT)).isGreaterThan(StockStatuses.rank(StockStatuses.LOW));
        assertThat(StockStatuses.rank(StockStatuses.LOW)).isGreaterThan(StockStatuses.rank(StockStatuses.OK));
    }
}
