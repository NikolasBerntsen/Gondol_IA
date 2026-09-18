package com.gondolia.pos.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Alta o edición de una caja. {@code branchId} es obligatorio al crear (si falta se toma del alcance);
 * {@code active} null = no se cambia.
 */
public record PosRegisterRequest(Long branchId,
                                 @NotBlank(message = "es obligatorio")
                                 @Size(max = 60, message = "no puede superar los 60 caracteres")
                                 String name,
                                 Boolean active) {
}
