package com.gondolia.platform.dto;

import com.gondolia.domain.user.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Altas y cambios del equipo de GondolIA (SPEC §6.6). */
public final class PlatformUserRequests {

    private PlatformUserRequests() {
    }

    /** Alta de un integrante del equipo. {@code role} solo puede ser {@code PLATFORM_OWNER} o {@code SUPPORT_AGENT}. */
    public record Create(
            @NotBlank(message = "es obligatorio")
            @Size(max = 150, message = "no puede superar los 150 caracteres") String fullName,

            @NotBlank(message = "es obligatorio") @Email(message = "no es un email válido")
            @Size(max = 150, message = "no puede superar los 150 caracteres") String email,

            @NotBlank(message = "es obligatoria")
            @Size(min = 8, max = 72, message = "tiene que tener entre 8 y 72 caracteres") String password,

            @NotNull(message = "elegí un rol") Role role) {
    }

    /** Edición: nombre y alta/baja de la cuenta. */
    public record Update(
            @NotBlank(message = "es obligatorio")
            @Size(max = 150, message = "no puede superar los 150 caracteres") String fullName,

            @NotNull(message = "indicá si la cuenta queda activa") Boolean active) {
    }

    /** Nueva contraseña de un integrante del equipo (queda obligado a cambiarla al entrar). */
    public record ResetPassword(
            @NotBlank(message = "es obligatoria")
            @Size(min = 8, max = 72, message = "tiene que tener entre 8 y 72 caracteres") String newPassword) {
    }
}
