package com.gondolia.support.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Calificación de la atención (1 a 5 estrellas) con un comentario opcional. */
public record RateTicketRequest(
        @NotNull(message = "es obligatoria")
        @Min(value = 1, message = "tiene que ser al menos 1")
        @Max(value = 5, message = "no puede superar 5")
        Integer rating,
        @Size(max = 500, message = "no puede superar los 500 caracteres")
        String comment) {
}
