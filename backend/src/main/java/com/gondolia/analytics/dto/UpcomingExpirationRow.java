package com.gondolia.analytics.dto;

import java.time.LocalDate;

/** Fila de "Próximos vencimientos" del Inicio (SPEC §6.5). */
public record UpcomingExpirationRow(
        Long lotId,
        Long branchId,
        String branchName,
        Long productId,
        String productName,
        String lotNumber,
        LocalDate expiryDate,
        long daysLeft,
        int quantity,
        String bucket) {
}
