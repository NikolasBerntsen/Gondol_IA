package com.gondolia.pos.dto;

import com.gondolia.domain.pos.PosSaleStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Venta completa del POS (ticket no fiscal). {@code batchRef} es el de los movimientos de stock ({@code P-...}).
 */
public record PosSaleDto(Long id, String ticketCode, long number, Long branchId, String branchName, Long registerId,
                         String registerName, Long sessionId, PosSaleStatus status, BigDecimal subtotal,
                         BigDecimal discountTotal, BigDecimal total, int itemsCount, int units, BigDecimal paidTotal,
                         BigDecimal changeAmount, String customerName, String customerDoc, Long cashierId,
                         String cashierName, String batchRef, boolean hasShortage, Instant createdAt,
                         Instant voidedAt, String voidedByName, String voidReason, List<PosSaleItemDto> items,
                         List<PosPaymentDto> payments) {
}
