package com.gondolia.catalog.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Alta o edición de un proveedor. */
public record SupplierRequest(
        @NotBlank(message = "es obligatorio")
        @Size(max = 150, message = "no puede superar los 150 caracteres")
        String name,

        @Size(max = 150, message = "no puede superar los 150 caracteres")
        String contactName,

        @Size(max = 50, message = "no puede superar los 50 caracteres")
        String phone,

        @Email(message = "no tiene un formato válido")
        @Size(max = 150, message = "no puede superar los 150 caracteres")
        String email,

        @Min(value = 0, message = "no puede ser negativo")
        @Max(value = 365, message = "no puede superar los 365 días")
        Integer leadTimeDays,

        @Size(max = 2000, message = "no puede superar los 2000 caracteres")
        String notes,

        Boolean active) {
}
