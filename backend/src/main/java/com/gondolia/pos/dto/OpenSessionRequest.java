package com.gondolia.pos.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Apertura de turno: caja y efectivo inicial del cajón. */
public record OpenSessionRequest(@NotNull(message = "es obligatoria") Long registerId,
                                 @NotNull(message = "es obligatorio")
                                 @DecimalMin(value = "0", message = "no puede ser negativo")
                                 @DecimalMax(value = "99999999.99", message = "es demasiado grande")
                                 BigDecimal openingCash) {
}
