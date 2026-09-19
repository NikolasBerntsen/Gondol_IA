package com.gondolia.catalog.dto;

import com.gondolia.domain.inventory.MovementSource;
import com.gondolia.domain.inventory.MovementType;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Movimiento reciente de un producto en el alcance de sucursales, para la ficha del producto.
 * <p>
 * Es una vista acotada y de solo lectura del historial (el historial completo con filtros es del módulo A2,
 * {@code GET /api/tenant/movements}, y lo ven el jefe y el administrador). Para los roles sin historial de ventas
 * (el empleado) {@code unitPrice}, {@code discountPct}, {@code totalAmount} y {@code batchRef} vienen en {@code null}.
 */
public record ProductMovementDto(
        Long id,
        Long branchId,
        String branchName,
        Long lotId,
        String lotNumber,
        MovementType type,
        int quantity,
        BigDecimal unitPrice,
        BigDecimal discountPct,
        BigDecimal totalAmount,
        MovementSource source,
        String batchRef,
        String reason,
        String userName,
        Instant occurredAt) {
}
