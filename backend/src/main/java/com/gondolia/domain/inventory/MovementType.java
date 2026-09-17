package com.gondolia.domain.inventory;

public enum MovementType {
    ENTRY,
    SALE,
    /** Anulación de una venta: devuelve las unidades a sus lotes (SPEC §15.1). */
    SALE_VOID,
    ADJUSTMENT_IN,
    ADJUSTMENT_OUT,
    WASTE_EXPIRED,
    WASTE_DAMAGED,
    RECALL_REMOVAL,
    TRANSFER_OUT,
    TRANSFER_IN;

    /** {@code true} si suma stock; la cantidad del movimiento siempre es positiva y el signo lo da el tipo. */
    public boolean isInbound() {
        return this == ENTRY || this == ADJUSTMENT_IN || this == TRANSFER_IN || this == SALE_VOID;
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

    /**
     * {@code true} para los movimientos que componen las ventas netas: {@code SALE} suma y {@code SALE_VOID} resta
     * (SPEC §4.2). Toda métrica de ventas tiene que descontar las anulaciones.
     */
    public boolean isSaleRelated() {
        return this == SALE || this == SALE_VOID;
    }
}
