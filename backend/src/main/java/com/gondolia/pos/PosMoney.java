package com.gondolia.pos;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Importes del POS: siempre dos decimales, redondeo HALF_UP. */
public final class PosMoney {

    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

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
        BigDecimal pct = discountPct.min(HUNDRED);
        return scale(base.multiply(HUNDRED.subtract(pct)).divide(HUNDRED, 4, RoundingMode.HALF_UP));
    }

    /**
     * Importe en pesos como lo muestra la interfaz ({@code formatMoney} con dos decimales): {@code "$ 1.234,50"}.
     * Se usa en los mensajes de error que el mostrador muestra tal cual.
     */
    public static String format(BigDecimal value) {
        BigDecimal amount = orZero(value);
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setGroupingSeparator('.');
        symbols.setDecimalSeparator(',');
        DecimalFormat format = new DecimalFormat("#,##0.00", symbols);
        format.setRoundingMode(RoundingMode.HALF_UP);
        return (amount.signum() < 0 ? "-" : "") + "$ " + format.format(amount.abs());
    }

    /** Divide un importe entre unidades (precio unitario promedio de una línea). */
    public static BigDecimal perUnit(BigDecimal total, int quantity) {
        if (quantity <= 0) {
            return ZERO;
        }
        return orZero(total).divide(BigDecimal.valueOf(quantity), 2, RoundingMode.HALF_UP);
    }
}
