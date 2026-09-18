package com.gondolia.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.gondolia.domain.tenant.TenantSettings;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Buckets de vencimiento con la configuración del comercio (SPEC §4.2). */
class ExpiryBucketsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 17);

    private final TenantSettings settings = settings(5, 15);

    @Test
    void lote_sin_vencimiento_es_ok() {
        assertThat(ExpiryBuckets.of(null, TODAY, settings)).isEqualTo(ExpiryBuckets.OK);
        assertThat(ExpiryBuckets.daysToExpiry(null, TODAY)).isNull();
    }

    @Test
    void ayer_esta_vencido() {
        assertThat(ExpiryBuckets.of(TODAY.minusDays(1), TODAY, settings)).isEqualTo(ExpiryBuckets.EXPIRED);
    }

    @Test
    void hoy_y_hasta_los_dias_criticos_es_critico() {
        assertThat(ExpiryBuckets.of(TODAY, TODAY, settings)).isEqualTo(ExpiryBuckets.CRITICAL);
        assertThat(ExpiryBuckets.of(TODAY.plusDays(5), TODAY, settings)).isEqualTo(ExpiryBuckets.CRITICAL);
    }

    @Test
    void hasta_los_dias_de_aviso_es_por_vencer() {
        assertThat(ExpiryBuckets.of(TODAY.plusDays(6), TODAY, settings)).isEqualTo(ExpiryBuckets.WARNING);
        assertThat(ExpiryBuckets.of(TODAY.plusDays(15), TODAY, settings)).isEqualTo(ExpiryBuckets.WARNING);
    }

    @Test
    void hasta_30_dias_es_proximo_y_despues_ok() {
        assertThat(ExpiryBuckets.of(TODAY.plusDays(16), TODAY, settings)).isEqualTo(ExpiryBuckets.UPCOMING);
        assertThat(ExpiryBuckets.of(TODAY.plusDays(30), TODAY, settings)).isEqualTo(ExpiryBuckets.UPCOMING);
        assertThat(ExpiryBuckets.of(TODAY.plusDays(31), TODAY, settings)).isEqualTo(ExpiryBuckets.OK);
    }

    @Test
    void respeta_la_configuracion_de_cada_comercio() {
        TenantSettings estricto = settings(1, 3);
        assertThat(ExpiryBuckets.of(TODAY.plusDays(2), TODAY, estricto)).isEqualTo(ExpiryBuckets.WARNING);
        assertThat(ExpiryBuckets.of(TODAY.plusDays(2), TODAY, settings)).isEqualTo(ExpiryBuckets.CRITICAL);
    }

    @Test
    void cuenta_los_dias_que_faltan() {
        assertThat(ExpiryBuckets.daysToExpiry(TODAY.plusDays(8), TODAY)).isEqualTo(8);
        assertThat(ExpiryBuckets.daysToExpiry(TODAY.minusDays(3), TODAY)).isEqualTo(-3);
    }

    private static TenantSettings settings(int critical, int warning) {
        TenantSettings settings = TenantSettings.defaultsFor(1L);
        settings.setExpiryCriticalDays(critical);
        settings.setExpiryWarningDays(warning);
        return settings;
    }
}
