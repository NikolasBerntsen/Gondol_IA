package com.gondolia.tenantadmin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Alta y edición de una sucursal. El estado (activa/desactivada) se cambia con
 * {@code /activate} y {@code /deactivate}.
 */
public record BranchRequest(
        @NotBlank(message = "es obligatorio")
        @Size(max = 100, message = "no puede superar los 100 caracteres")
        String name,

        @Size(max = 20, message = "no puede superar los 20 caracteres")
        String code,

        @Size(max = 200, message = "no puede superar los 200 caracteres")
        String address,

        @Size(max = 100, message = "no puede superar los 100 caracteres")
        String city,

        @Size(max = 100, message = "no puede superar los 100 caracteres")
        String province,

        @Size(max = 50, message = "no puede superar los 50 caracteres")
        String phone) {
}
