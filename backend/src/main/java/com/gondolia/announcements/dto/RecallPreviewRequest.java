package com.gondolia.announcements.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/** Vista previa del alcance de un recall antes de publicarlo. Devuelve solo cantidades. */
public record RecallPreviewRequest(
        @NotBlank(message = "es obligatorio")
        @Size(max = 32, message = "no puede superar 32 caracteres")
        String barcode,

        List<@Size(max = 60, message = "no puede superar 60 caracteres") String> lotNumbers,

        boolean allLots,

        LocalDate expiryFrom,

        LocalDate expiryTo) {
}
