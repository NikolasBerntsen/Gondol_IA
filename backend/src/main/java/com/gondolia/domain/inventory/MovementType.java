package com.gondolia.domain.inventory;

public enum MovementType {
    ENTRY,
    SALE,
    ADJUSTMENT_IN,
    ADJUSTMENT_OUT,
    WASTE_EXPIRED,
    WASTE_DAMAGED,
    RECALL_REMOVAL,
    TRANSFER_OUT,
    TRANSFER_IN;

    /** {@code true} si suma stock; la cantidad del movimiento siempre es positiva y el signo lo da el tipo. */
    public boolean isInbound() {
        return this == ENTRY || this == ADJUSTMENT_IN || this == TRANSFER_IN;
    }

    /** {@code true} si descuenta stock. */
    public boolean isOutbound() {
        return !isInbound();
    }

    /** {@code true} para mermas (vencido o dañado). */
    public boolean isWaste() {
        return this == WASTE_EXPIRED || this == WASTE_DAMAGED;
    }

    /** {@code true} para los dos lados de una transferencia entre sucursales. */
    public boolean isTransfer() {
        return this == TRANSFER_OUT || this == TRANSFER_IN;
    }
}
