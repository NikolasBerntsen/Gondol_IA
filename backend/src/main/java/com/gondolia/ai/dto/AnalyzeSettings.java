package com.gondolia.ai.dto;

import com.gondolia.domain.tenant.StockRotation;
import java.math.BigDecimal;

/** Parámetros del análisis ({@code settings} de §8.2). */
public record AnalyzeSettings(
        StockRotation stockRotation,
        int expiryWarningDays,
        int expiryCriticalDays,
        int leadTimeDays,
        int targetCoverageDays,
        BigDecimal serviceLevel,
        int maxDiscountPct,
        int horizonDays) {
}
