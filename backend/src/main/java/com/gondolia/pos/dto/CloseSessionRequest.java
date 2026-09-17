package com.gondolia.pos.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Cierre de turno con arqueo: efectivo contado y nota opcional. */
public record CloseSessionRequest(@NotNull(message = "es obligatorio")
                                  @DecimalMin(value = "0", message = "no puede ser negativo")
                                  @DecimalMax(value = "99999999.99", message = "es demasiado grande")
                                  BigDecimal countedCash,
                                  @Size(max = 300, message = "no puede superar los 300 caracteres") String note) {
}
