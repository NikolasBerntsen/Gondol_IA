package com.gondolia.domain.pos;

/**
 * Medio de pago de una venta del POS GondolIA (SPEC §15).
 */
public enum PaymentMethod {
    CASH,
    DEBIT,
    CREDIT,
    TRANSFER,
    QR;

    /** {@code true} si mueve efectivo del cajón (cuenta para el arqueo). */
    public boolean isCash() {
        return this == CASH;
    }
}
