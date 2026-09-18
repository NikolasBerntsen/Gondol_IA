package com.gondolia.alerts.dto;

import java.util.Map;

/**
 * Contadores de la bandeja de alertas para los filtros de la pantalla (SPEC §6.5).
 * {@code byType} y {@code bySeverity} son del estado consultado.
 */
public record AlertCountsDto(
        long open,
        long acknowledged,
        long resolved,
        long dismissed,
        long critical,
        long warning,
        long info,
        Map<String, Long> byType) {
}
