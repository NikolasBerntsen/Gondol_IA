package com.gondolia.platform.dto;

import com.gondolia.domain.user.Role;
import java.time.Instant;

/**
 * Usuario de un comercio para la consola de dueños: datos de la cuenta (para saber a quién escribir y resetear la
 * contraseña del administrador), nunca su actividad de negocio.
 */
public record TenantUserDto(Long id, String fullName, String email, Role role, boolean active, Instant lastLoginAt,
                            Instant createdAt) {
}
