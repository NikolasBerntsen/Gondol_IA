package com.gondolia.alerts.dto;

import com.gondolia.domain.alert.AlertStatus;
import com.gondolia.domain.alert.AlertType;
import com.gondolia.domain.common.Severity;
import java.time.Instant;

/** Fila de la bandeja de alertas (SPEC §6.5). */
public record AlertDto(
        Long id,
        Long branchId,
        String branchName,
        AlertType type,
        Severity severity,
        AlertStatus status,
        Long productId,
        String productName,
        Long lotId,
        String lotNumber,
        Long announcementId,
        String title,
        String message,
        Instant createdAt,
        Instant updatedAt,
        String handledByName,
        Instant resolvedAt) {
}
