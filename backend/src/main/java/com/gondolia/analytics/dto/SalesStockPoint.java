package com.gondolia.analytics.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Un día de la curva "Ventas y stock" (SPEC §6.5). Las ventas son netas de anulaciones. */
public record SalesStockPoint(LocalDate date, long salesUnits, BigDecimal salesAmount, long stockUnits) {
}
