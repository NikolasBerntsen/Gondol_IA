package com.gondolia.support.dto;

/**
 * Tablero de la bandeja de soporte (SPEC §6.8). {@code mine} son los tickets activos asignados al agente que consulta y
 * {@code avgFirstResponseMinutes} el promedio de primera respuesta de los últimos 30 días ({@code null} si todavía no
 * hubo ninguna).
 */
public record SupportStatsDto(long open, long inProgress, long waitingCustomer, long unassigned, long mine,
                              long resolvedToday, Double avgFirstResponseMinutes) {
}
