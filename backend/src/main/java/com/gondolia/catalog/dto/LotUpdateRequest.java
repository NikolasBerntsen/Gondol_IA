package com.gondolia.catalog.dto;

import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** Corrección del número de lote y del vencimiento de un lote ya cargado (SPEC §6.3). */
public record LotUpdateRequest(
        @Size(max = 60, message = "no puede superar los 60 caracteres")
        String lotNumber,

        LocalDate expiryDate) {
}
