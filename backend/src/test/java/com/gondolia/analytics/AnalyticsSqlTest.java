package com.gondolia.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.gondolia.analytics.BranchScopeService.Scope;
import com.gondolia.domain.tenant.TenantSettings;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Reglas del módulo B que no necesitan base de datos: buckets de vencimiento, estado de reposición (SPEC §4.2) y
 * el alcance de sucursales (SPEC §3.5).
 */
class AnalyticsSqlTest {

    private static final ZoneId ZONE = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 17);

    private final TenantSettings settings = TenantSettings.defaultsFor(1L);

    @Test
    void expiryBucketFollowsTheTenantThresholds() {
        // Defaults: crítico 5 días, aviso 15 días.
        assertThat(AnalyticsSql.expiryBucket(TODAY.minusDays(1), TODAY, settings)).isEqualTo("EXPIRED");
        assertThat(AnalyticsSql.expiryBucket(TODAY, TODAY, settings)).isEqualTo("CRITICAL");
        assertThat(AnalyticsSql.expiryBucket(TODAY.plusDays(5), TODAY, settings)).isEqualTo("CRITICAL");
        assertThat(AnalyticsSql.expiryBucket(TODAY.plusDays(6), TODAY, settings)).isEqualTo("WARNING");
        assertThat(AnalyticsSql.expiryBucket(TODAY.plusDays(15), TODAY, settings)).isEqualTo("WARNING");
        assertThat(AnalyticsSql.expiryBucket(TODAY.plusDays(16), TODAY, settings)).isEqualTo("UPCOMING");
        assertThat(AnalyticsSql.expiryBucket(TODAY.plusDays(30), TODAY, settings)).isEqualTo("UPCOMING");
        assertThat(AnalyticsSql.expiryBucket(TODAY.plusDays(31), TODAY, settings)).isEqualTo("OK");
        assertThat(AnalyticsSql.expiryBucket(null, TODAY, settings)).isEqualTo("OK");
    }

    @Test
    void reorderStatusSeparatesEmptyFromCriticalAndLow() {
        assertThat(AnalyticsSql.reorderStatus(0, 10)).isEqualTo("SIN_STOCK");
        assertThat(AnalyticsSql.reorderStatus(-3, 10)).isEqualTo("SIN_STOCK");
        assertThat(AnalyticsSql.reorderStatus(5, 10)).isEqualTo("CRITICO");
        assertThat(AnalyticsSql.reorderStatus(6, 10)).isEqualTo("BAJO");
        assertThat(AnalyticsSql.reorderStatus(10, 10)).isEqualTo("BAJO");
    }

    @Test
    void startOfDayUsesTheBusinessZone() {
        var start = AnalyticsSql.startOfDay(TODAY, Clock.system(ZONE));
        assertThat(start.toInstant()).isEqualTo(TODAY.atStartOfDay(ZONE).toInstant());
    }

    @Test
    void scopeReportsAllOrOneBranchAndResolvesNames() {
        Scope all = new Scope(List.of(3L, 4L), Map.of(3L, "Centro", 4L, "Norte"), true);
        assertThat(all.label()).isEqualTo("ALL");
        assertThat(all.isEmpty()).isFalse();
        assertThat(all.singleBranchId()).isNull();
        assertThat(all.nameOf(4L)).isEqualTo("Norte");
        assertThat(all.nameOf(null)).isNull();
        assertThat(all.nameOf(99L)).isNull();

        Scope one = new Scope(List.of(3L), Map.of(3L, "Centro"), false);
        assertThat(one.label()).isEqualTo("BRANCH");
        assertThat(one.singleBranchId()).isEqualTo(3L);

        Scope none = new Scope(null, null, true);
        assertThat(none.isEmpty()).isTrue();
        assertThat(none.branchIds()).isEmpty();
        assertThat(none.singleBranchId()).isNull();
    }
}
