package com.gondolia.pos.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Anulación de una venta: el motivo queda en el ticket y en los movimientos {@code SALE_VOID}. */
public record VoidSaleRequest(@NotBlank(message = "es obligatorio")
                              @Size(max = 300, message = "no puede superar los 300 caracteres") String reason) {
}
