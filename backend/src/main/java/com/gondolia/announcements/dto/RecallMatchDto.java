package com.gondolia.announcements.dto;

import com.gondolia.domain.announcement.RecallMatchStatus;
import com.gondolia.domain.announcement.RecallResolution;
import com.gondolia.domain.common.Severity;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Coincidencia de un recall con un lote de una sucursal (SPEC §6.7). {@code quantityAtMatch} es el remanente al
 * detectarla y {@code currentQuantity} el que queda hoy en cuarentena.
 */
public record RecallMatchDto(
        Long id,
        Long announcementId,
        Long branchId,
        String branchName,
        String title,
        Severity severity,
        String reason,
        String instructions,
        Long productId,
        String productName,
        String barcode,
        Long lotId,
        String lotNumber,
        LocalDate expiryDate,
        int quantityAtMatch,
        int currentQuantity,
        RecallMatchStatus status,
        Instant matchedAt,
        Instant acknowledgedAt,
        String acknowledgedByName,
        Instant resolvedAt,
        String resolvedByName,
        RecallResolution resolution,
        String resolutionNote) {
}
