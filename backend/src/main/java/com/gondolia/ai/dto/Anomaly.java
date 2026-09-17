package com.gondolia.ai.dto;

import java.time.LocalDate;

/** Venta anómala detectada ({@code kind}: p. ej. {@code SPIKE}). */
public record Anomaly(LocalDate date, int quantity, double expected, double score, String kind) {
}
