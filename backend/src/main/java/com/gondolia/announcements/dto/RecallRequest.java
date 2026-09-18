package com.gondolia.announcements.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * Datos del retiro de mercadería de un recall. Se valida en el servicio: código de barras válido, al menos un lote
 * (o "todos los lotes") y rango de vencimiento coherente.
 */
public record RecallRequest(
        @NotBlank(message = "es obligatorio")
        @Size(max = 200, message = "no puede superar 200 caracteres")
        String productName,

        @Size(max = 100, message = "no puede superar 100 caracteres")
        String brand,

        @NotBlank(message = "es obligatorio")
        @Size(max = 32, message = "no puede superar 32 caracteres")
        String barcode,

        List<@Size(max = 60, message = "no puede superar 60 caracteres") String> lotNumbers,

        boolean allLots,

        LocalDate expiryFrom,

        LocalDate expiryTo,

        @NotBlank(message = "es obligatorio")
        @Size(max = 2000, message = "no puede superar 2000 caracteres")
        String reason,

        @NotBlank(message = "es obligatorio")
        @Size(max = 2000, message = "no puede superar 2000 caracteres")
        String instructions) {
}
