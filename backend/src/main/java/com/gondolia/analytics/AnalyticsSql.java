package com.gondolia.analytics;

import com.gondolia.domain.tenant.TenantSettings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * Fragmentos de SQL y utilidades compartidas por las consultas analíticas del módulo B.
 * <p>
 * Reglas de SPEC §4.2 traducidas a SQL: stock vendible, stock físico, ventas netas de anulaciones y buckets de
 * vencimiento.
 */
public final class AnalyticsSql {

    /** Movimientos que componen las ventas netas: {@code SALE} suma y {@code SALE_VOID} resta. */
    static final String NET_SALE_UNITS = "sum(case when m.type = 'SALE' then m.quantity else -m.quantity end)";
    static final String NET_SALE_AMOUNT =
            "sum(case when m.type = 'SALE' then coalesce(m.total_amount, 0) else -coalesce(m.total_amount, 0) end)";
    static final String SALE_TYPES = "('SALE', 'SALE_VOID')";

    /** Signo del movimiento sobre el stock físico (solo movimientos con lote: los faltantes no mueven stock). */
    static final String STOCK_DELTA = """
            sum(case when m.type in ('ENTRY', 'ADJUSTMENT_IN', 'TRANSFER_IN', 'SALE_VOID')
                     then m.quantity else -m.quantity end)
            """;

    /** Lote vendible: ACTIVE, con remanente y sin vencer a la fecha indicada. */
    static final String SELLABLE_LOT =
            "l.status = 'ACTIVE' and l.quantity > 0 and (l.expiry_date is null or l.expiry_date >= :today)";

    /** Stock físico (SPEC §4.2): lotes ACTIVE y en cuarentena con remanente. */
    static final String PHYSICAL_LOT = "l.quantity > 0 and l.status in ('ACTIVE', 'RECALLED')";

    private AnalyticsSql() {
    }

    /** Bucket de vencimiento de un lote según los umbrales del comercio (SPEC §4.2). */
    static String expiryBucket(LocalDate expiry, LocalDate today, TenantSettings settings) {
        if (expiry == null) {
            return "OK";
        }
        long days = ChronoUnit.DAYS.between(today, expiry);
        if (days < 0) {
            return "EXPIRED";
        }
        if (days <= settings.getExpiryCriticalDays()) {
            return "CRITICAL";
        }
        if (days <= settings.getExpiryWarningDays()) {
            return "WARNING";
        }
        return days <= 30 ? "UPCOMING" : "OK";
    }

    /** Estado de reposición de un producto en una sucursal (SPEC §4.2). */
    static String reorderStatus(int sellable, int minStock) {
        if (sellable <= 0) {
            return "SIN_STOCK";
        }
        return sellable * 2 <= minStock ? "CRITICO" : "BAJO";
    }

    /**
     * Comienzo del día en la zona del comercio, tipado como {@code OffsetDateTime}: un {@code Instant} suelto no le
     * permite a Postgres inferir el tipo del parámetro.
     */
    public static OffsetDateTime startOfDay(LocalDate date, Clock clock) {
        return OffsetDateTime.ofInstant(date.atStartOfDay(clock.getZone()).toInstant(), ZoneOffset.UTC);
    }

    /**
     * Lee un {@code timestamptz}. El driver de Postgres no convierte directo a {@code Instant}: hay que pasar por
     * {@code OffsetDateTime}.
     */
    public static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    static BigDecimal money(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    static BigDecimal scaled(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }
}
