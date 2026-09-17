package com.gondolia.pos;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Importes del POS: siempre dos decimales, redondeo HALF_UP. */
public final class PosMoney {

    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private PosMoney() {
    }

    public static BigDecimal scale(BigDecimal value) {
        return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal orZero(BigDecimal value) {
        return value == null ? ZERO : scale(value);
    }

    /** Precio con el descuento del lote aplicado ({@code precio × (1 − pct/100)}). */
    public static BigDecimal withDiscount(BigDecimal price, BigDecimal discountPct) {
        BigDecimal base = orZero(price);
        if (discountPct == null || discountPct.signum() <= 0) {
            return base;
        }
        return scale(base.multiply(HUNDRED.subtract(discountPct)).divide(HUNDRED, 4, RoundingMode.HALF_UP));
    }

    /** Divide un importe entre unidades (precio unitario promedio de una línea). */
    public static BigDecimal perUnit(BigDecimal total, int quantity) {
        if (quantity <= 0) {
            return ZERO;
        }
        return orZero(total).divide(BigDecimal.valueOf(quantity), 2, RoundingMode.HALF_UP);
    }
}
