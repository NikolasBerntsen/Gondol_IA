package com.gondolia.ai.dto;

import java.time.LocalDate;

/** Pronóstico de un día: valor esperado e intervalo. */
public record ForecastPoint(LocalDate date, double yhat, double lo, double hi) {
}
