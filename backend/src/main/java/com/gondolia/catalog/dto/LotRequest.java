package com.gondolia.catalog.dto;

import com.gondolia.domain.inventory.MovementSource;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Carga de mercadería (SPEC §6.3). {@code branchId} es opcional: si falta se toma del encabezado
 * {@code X-Branch-Id} y, si el alcance es "todas" con más de una sucursal accesible, responde 400
 * {@code BRANCH_REQUIRED}.
 */
public record LotRequest(
        Long branchId,

        @NotNull(message = "es obligatorio")
        Long productId,

        @Size(max = 60, message = "no puede superar los 60 caracteres")
        String lotNumber,

        LocalDate expiryDate,

        @NotNull(message = "es obligatoria")
        @Min(value = 1, message = "tiene que ser mayor a cero")
        @Max(value = 1000000, message = "es demasiado grande")
        Integer quantity,

        @PositiveOrZero(message = "no puede ser negativo")
        @DecimalMax(value = "9999999999", message = "es demasiado grande")
        BigDecimal costPrice,

        Long supplierId,

        MovementSource source) {
}
