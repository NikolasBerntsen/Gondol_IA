package com.gondolia.catalog;

import com.gondolia.domain.tenant.TenantSettings;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Buckets de vencimiento (SPEC §4.2) con la configuración del comercio: {@code EXPIRED} (menos de 0 días),
 * {@code CRITICAL} (hasta {@code expiryCriticalDays}), {@code WARNING} (hasta {@code expiryWarningDays}),
 * {@code UPCOMING} (hasta 30 días) y {@code OK} el resto. Un lote sin vencimiento es {@code OK}.
 * Etiquetas de interfaz: Vencido / Crítico / Por vencer / Próximo.
 */
public final class ExpiryBuckets {

    public static final String EXPIRED = "EXPIRED";
    public static final String CRITICAL = "CRITICAL";
    public static final String WARNING = "WARNING";
    public static final String UPCOMING = "UPCOMING";
    public static final String OK = "OK";

    /** Último bucket antes de {@code OK}, en días (SPEC §4.2). */
    public static final int UPCOMING_DAYS = 30;

    private ExpiryBuckets() {
    }

    public static String of(LocalDate expiryDate, LocalDate today, TenantSettings settings) {
        Integer days = daysToExpiry(expiryDate, today);
        if (days == null) {
            return OK;
        }
        if (days < 0) {
            return EXPIRED;
        }
        if (days <= settings.getExpiryCriticalDays()) {
            return CRITICAL;
        }
        if (days <= settings.getExpiryWarningDays()) {
            return WARNING;
        }
        return days <= UPCOMING_DAYS ? UPCOMING : OK;
    }

    /** Días hasta el vencimiento (negativo si ya venció); {@code null} si el lote no vence. */
    public static Integer daysToExpiry(LocalDate expiryDate, LocalDate today) {
        return expiryDate == null ? null : Math.toIntExact(ChronoUnit.DAYS.between(today, expiryDate));
    }
}
