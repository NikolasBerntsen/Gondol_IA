package com.gondolia.recall;

import com.gondolia.domain.common.Severity;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Push de {@code /user/queue/security-alerts} cuando un recall alcanza un lote de una sucursal (SPEC §7).
 * {@code quantity} es el remanente del lote al momento de la coincidencia.
 */
public record RecallAlertMessage(
        Long matchId,
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
        int quantity,
        Instant matchedAt) {
}
