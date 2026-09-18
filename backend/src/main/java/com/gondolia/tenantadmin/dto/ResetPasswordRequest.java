package com.gondolia.tenantadmin.dto;

import jakarta.validation.constraints.Size;

/**
 * Reseteo de contraseña desde la administración. Si {@code newPassword} viene vacío, el backend genera una
 * contraseña temporal y la devuelve una sola vez.
 */
public record ResetPasswordRequest(
        @Size(min = 8, max = 72, message = "tiene que tener entre 8 y 72 caracteres")
        String newPassword) {
}
