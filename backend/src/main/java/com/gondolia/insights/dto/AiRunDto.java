package com.gondolia.insights.dto;

import com.gondolia.domain.ai.AiRunStatus;
import com.gondolia.domain.ai.AiRunTrigger;
import java.time.Instant;

/** Estado del último análisis de IA de una sucursal (SPEC §6.5). */
public record AiRunDto(
        Long id,
        Long branchId,
        String branchName,
        AiRunStatus status,
        AiRunTrigger trigger,
        String modelVersion,
        Integer productsAnalyzed,
        Integer recommendationsCreated,
        Instant startedAt,
        Instant finishedAt,
        String errorMessage) {
}
