package com.gondolia.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gondolia.common.error.ApiException;
import org.junit.jupiter.api.Test;

/** Normalización y validación de los filtros del inventario (SPEC §6.3). */
class ProductQueryTest {

    @Test
    void aplica_los_valores_por_defecto() {
        ProductQuery query = new ProductQuery(null, null, null, null, 0, 0, null);
        assertThat(query.stockStatus()).isEqualTo(ProductQuery.STATUS_ALL);
        assertThat(query.size()).isEqualTo(20);
        assertThat(query.sortField()).isEqualTo("name");
        assertThat(query.sortDescending()).isFalse();
        assertThat(query.filtersStock()).isFalse();
    }

    @Test
    void recorta_el_texto_y_normaliza_el_estado() {
        ProductQuery query = new ProductQuery("  yogur  ", null, "low", null, 2, 10, "salePrice,desc");
        assertThat(query.q()).isEqualTo("yogur");
        assertThat(query.stockStatus()).isEqualTo(StockStatuses.LOW);
        assertThat(query.filtersStock()).isTrue();
        assertThat(query.sortField()).isEqualTo("salePrice");
        assertThat(query.sortDescending()).isTrue();
    }

    @Test
    void el_texto_vacio_no_filtra() {
        assertThat(new ProductQuery("   ", null, null, null, 0, 20, null).q()).isNull();
    }

    @Test
    void limita_el_tamano_de_pagina_y_la_pagina_negativa() {
        ProductQuery query = new ProductQuery(null, null, null, null, -3, 500, null);
        assertThat(query.size()).isEqualTo(100);
        assertThat(query.page()).isZero();
    }

    @Test
    void rechaza_un_estado_desconocido() {
        assertThatThrownBy(() -> new ProductQuery(null, null, "CASI", null, 0, 20, null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("ALL, OK, LOW, OUT o EXPIRING");
    }

    @Test
    void rechaza_un_campo_de_orden_desconocido() {
        ProductQuery query = new ProductQuery(null, null, null, null, 0, 20, "precio,asc");
        assertThatThrownBy(query::sortField)
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("precio");
    }
}
