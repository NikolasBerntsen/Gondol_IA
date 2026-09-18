package com.gondolia.pos.dto;

import com.gondolia.domain.pos.PaymentMethod;
import com.gondolia.domain.pos.PosSaleStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Fila del historial de ventas del POS. */
public record PosSaleSummaryDto(Long id, String ticketCode, Long branchId, String branchName, Long registerId,
                                String registerName, Long sessionId, PosSaleStatus status, BigDecimal total,
                                int itemsCount, int units, String cashierName, Instant createdAt,
                                boolean hasShortage, Instant voidedAt, String voidedByName, String voidReason,
                                List<PaymentMethod> paymentMethods) {
}
