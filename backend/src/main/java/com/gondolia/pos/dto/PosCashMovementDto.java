package com.gondolia.pos.dto;

import com.gondolia.domain.pos.CashMovementType;
import java.math.BigDecimal;
import java.time.Instant;

public record PosCashMovementDto(Long id, CashMovementType type, BigDecimal amount, String reason, String userName,
                                 Instant createdAt) {
}
