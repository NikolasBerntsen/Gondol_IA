package com.gondolia.movements.dto;

import com.gondolia.domain.inventory.MovementSource;
import com.gondolia.domain.inventory.MovementType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * DTOs del historial de movimientos y de los ajustes manuales (SPEC §6.4).
 */
public final class MovementDtos {

    private MovementDtos() {
    }

    /** Fila del historial de movimientos. */
    public record MovementDto(Long id, Long branchId, String branchName, Long productId, String productName,
                              String barcode, Long lotId, String lotNumber, LocalDate expiryDate, MovementType type,
                              String typeLabel, int quantity, int signedQuantity, BigDecimal unitPrice,
                              BigDecimal discountPct, BigDecimal totalAmount, MovementSource source,
                              String sourceLabel, String batchRef, String reason, String userName,
                              Instant occurredAt) {
    }

    /**
     * Ajuste sobre un lote. El administrador puede usar todos los tipos; el empleado solo
     * {@code WASTE_EXPIRED} y {@code WASTE_DAMAGED} en sus sucursales (SPEC §3.3).
     */
    public record AdjustmentRequest(
            @NotNull(message = "es obligatorio") Long lotId,
            @NotNull(message = "es obligatorio") MovementType type,
            @NotNull(message = "es obligatoria") @Min(value = 1, message = "tiene que ser al menos 1")
            @Max(value = 1_000_000, message = "no puede superar 1.000.000") Integer quantity,
            @Size(max = 300, message = "no puede superar los 300 caracteres") String reason) {
    }
}
