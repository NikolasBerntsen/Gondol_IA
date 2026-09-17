package com.gondolia.platform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Motivo del bloqueo o de la baja de un comercio (SPEC §6.6): queda en {@code tenants.status_reason} y en el
 * historial. Habilitar y reactivar aceptan un motivo opcional (ver {@link #optional(String)}).
 */
public record TenantStatusRequest(
        @NotBlank(message = "contá el motivo") @Size(max = 300, message = "no puede superar los 300 caracteres")
        String reason) {

    /** Motivo opcional (habilitar / reactivar): puede llegar sin cuerpo. */
    public static String optional(String reason) {
        if (reason == null) {
            return null;
        }
        String trimmed = reason.strip();
        return trimmed.isEmpty() ? null : trimmed.substring(0, Math.min(trimmed.length(), 300));
    }
}
