package com.gondolia.ai.dto;

/**
 * Efecto medido de un descuento aceptado ({@code outcome} de §8.2; §6.5 agrega {@code lotUnitsSold} y
 * {@code lotUnitsRemaining}, que el servicio de IA acepta).
 */
public record FeedbackOutcome(
        Integer unitsBefore7d,
        Integer unitsAfter7d,
        Double lift,
        Integer lotUnitsSold,
        Integer lotUnitsRemaining) {
}
