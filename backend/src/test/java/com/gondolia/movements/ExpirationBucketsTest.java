package com.gondolia.movements;

import static org.assertj.core.api.Assertions.assertThat;

import com.gondolia.domain.inventory.MovementSource;
import com.gondolia.domain.inventory.MovementType;
import com.gondolia.movements.ExpirationsService.Thresholds;
import com.gondolia.movements.dto.ExpirationDtos.ExpirationBucket;
import org.junit.jupiter.api.Test;

/**
 * Buckets de vencimiento (SPEC §4.2) y etiquetas en español de los enums de movimientos.
 * Los umbrales salen de {@code tenant_settings}: acá se usan los valores de ejemplo 3 / 10 / 30.
 */
class ExpirationBucketsTest {

    private static final Thresholds THRESHOLDS = new Thresholds(3, 10, 30);

    // ------------------------------------------------------------------ buckets

    @Test
    void anythingInThePastIsExpired() {
        assertThat(ExpirationsService.bucketOf(-1, THRESHOLDS)).isEqualTo(ExpirationBucket.EXPIRED);
        assertThat(ExpirationsService.bucketOf(-90, THRESHOLDS)).isEqualTo(ExpirationBucket.EXPIRED);
    }

    @Test
    void todayAndUpToTheCriticalThresholdAreCritical() {
        assertThat(ExpirationsService.bucketOf(0, THRESHOLDS)).isEqualTo(ExpirationBucket.CRITICAL);
        assertThat(ExpirationsService.bucketOf(3, THRESHOLDS)).isEqualTo(ExpirationBucket.CRITICAL);
    }

    @Test
    void betweenTheCriticalAndWarningThresholdsIsWarning() {
        assertThat(ExpirationsService.bucketOf(4, THRESHOLDS)).isEqualTo(ExpirationBucket.WARNING);
        assertThat(ExpirationsService.bucketOf(10, THRESHOLDS)).isEqualTo(ExpirationBucket.WARNING);
    }

    @Test
    void beyondTheWarningThresholdIsUpcoming() {
        assertThat(ExpirationsService.bucketOf(11, THRESHOLDS)).isEqualTo(ExpirationBucket.UPCOMING);
        assertThat(ExpirationsService.bucketOf(365, THRESHOLDS)).isEqualTo(ExpirationBucket.UPCOMING);
    }

    @Test
    void theBucketsAreContiguousAndCoverEveryDay() {
        for (long days = -5; days <= 60; days++) {
            assertThat(ExpirationsService.bucketOf(days, THRESHOLDS)).as("días %s", days).isNotNull();
        }
    }

    @Test
    void aTenantWithTheSameCriticalAndWarningThresholdNeverReportsWarning() {
        Thresholds flat = new Thresholds(5, 5, 30);
        assertThat(ExpirationsService.bucketOf(5, flat)).isEqualTo(ExpirationBucket.CRITICAL);
        assertThat(ExpirationsService.bucketOf(6, flat)).isEqualTo(ExpirationBucket.UPCOMING);
    }

    // ------------------------------------------------------------------ etiquetas

    @Test
    void everyMovementTypeHasASpanishLabel() {
        for (MovementType type : MovementType.values()) {
            assertThat(MovementLabels.type(type)).as("etiqueta de %s", type).isNotBlank().isNotEqualTo("—");
        }
        assertThat(MovementLabels.type(MovementType.SALE_VOID)).isEqualTo("Anulación de venta");
        assertThat(MovementLabels.type(MovementType.WASTE_EXPIRED)).isEqualTo("Baja por vencimiento");
        assertThat(MovementLabels.type(null)).isEqualTo("—");
    }

    @Test
    void everySourceHasASpanishLabelAndTheTwoPosSourcesAreDistinguishable() {
        for (MovementSource source : MovementSource.values()) {
            assertThat(MovementLabels.source(source)).as("etiqueta de %s", source).isNotBlank().isNotEqualTo("—");
        }
        assertThat(MovementLabels.source(MovementSource.POS)).isEqualTo("POS externo");
        assertThat(MovementLabels.source(MovementSource.POS_GONDOLIA)).isEqualTo("POS GondolIA");
        assertThat(MovementLabels.source(null)).isEqualTo("—");
    }
}
