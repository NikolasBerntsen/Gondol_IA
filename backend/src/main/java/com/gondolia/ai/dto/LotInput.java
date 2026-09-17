package com.gondolia.ai.dto;

import com.gondolia.domain.inventory.LotStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Lote de la sucursal ({@code lots[]} de §8.2); {@code receivedAt} define el orden FIFO. */
public record LotInput(
        Long lotId,
        String lotNumber,
        LocalDate expiryDate,
        Instant receivedAt,
        int quantity,
        BigDecimal discountPct,
        LotStatus status) {
}
