package com.gondolia.insights.dto;

import java.util.List;

/** Respuesta de {@code POST /api/tenant/insights/run}: qué sucursales quedaron analizándose. */
public record InsightsRunLaunchedDto(String message, List<AiRunDto> runs) {
}
