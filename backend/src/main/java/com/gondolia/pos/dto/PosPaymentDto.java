package com.gondolia.pos.dto;

import com.gondolia.domain.pos.PaymentMethod;
import java.math.BigDecimal;

public record PosPaymentDto(Long id, PaymentMethod method, String label, BigDecimal amount, String reference) {
}
