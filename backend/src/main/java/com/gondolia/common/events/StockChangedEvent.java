package com.gondolia.common.events;

/**
 * Cambió el stock de un producto en una sucursal (entrada, venta, ajuste, merma, recall, transferencia).
 * Se publica dentro de la transacción; los listeners usan {@code @TransactionalEventListener(phase = AFTER_COMMIT)}.
 */
public record StockChangedEvent(Long tenantId, Long branchId, Long productId) {
}
