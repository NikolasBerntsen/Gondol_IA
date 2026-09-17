package com.gondolia.catalog.dto;

import com.gondolia.domain.inventory.LotStatus;
import com.gondolia.domain.inventory.MovementSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Lote de una sucursal (SPEC §6.3). {@code rotationRank} es la posición en la que se venderá dentro de su sucursal
 * (1 = "se vende primero"); es {@code null} para los lotes que no son vendibles (vencidos, agotados o en cuarentena).
 */
public record LotDto(
        Long id,
        Long branchId,
        String branchName,
        Long productId,
        String productName,
        String lotNumber,
        LocalDate expiryDate,
        Integer daysToExpiry,
        int initialQuantity,
        int quantity,
        BigDecimal costPrice,
        Instant receivedAt,
        LotStatus status,
        MovementSource source,
        BigDecimal discountPct,
        Long supplierId,
        String supplierName,
        String expiryBucket,
        Long originLotId,
        Integer rotationRank) {
}
