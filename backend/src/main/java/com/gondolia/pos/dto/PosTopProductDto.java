package com.gondolia.pos.dto;

import java.math.BigDecimal;

public record PosTopProductDto(Long productId, String productName, int units, BigDecimal total) {
}
