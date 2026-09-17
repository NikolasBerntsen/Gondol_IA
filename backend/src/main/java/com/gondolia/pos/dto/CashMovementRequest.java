package com.gondolia.pos.dto;

import com.gondolia.domain.pos.CashMovementType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Ingreso o retiro de efectivo del turno. */
public record CashMovementRequest(@NotNull(message = "es obligatorio") CashMovementType type,
                                  @NotNull(message = "es obligatorio")
                                  @DecimalMin(value = "0.01", message = "tiene que ser mayor a cero")
                                  @DecimalMax(value = "99999999.99", message = "es demasiado grande")
                                  BigDecimal amount,
                                  @NotBlank(message = "es obligatorio")
                                  @Size(max = 300, message = "no puede superar los 300 caracteres")
                                  String reason) {
}
