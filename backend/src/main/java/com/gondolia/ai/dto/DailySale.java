package com.gondolia.ai.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Ventas de un día ({@code dailySales[]} de §8.2); los días sin fila cuentan como 0. */
public record DailySale(LocalDate date, int quantity, BigDecimal discountPct) {
}
