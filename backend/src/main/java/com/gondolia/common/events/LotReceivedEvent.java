package com.gondolia.common.events;

/**
 * Ingresó un lote nuevo en una sucursal (carga de mercadería o destino de una transferencia).
 */
public record LotReceivedEvent(Long tenantId, Long branchId, Long productId, Long lotId) {
}
