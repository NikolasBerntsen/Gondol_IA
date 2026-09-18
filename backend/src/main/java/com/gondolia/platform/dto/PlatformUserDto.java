package com.gondolia.platform.dto;

import com.gondolia.domain.user.Role;
import java.time.Instant;

/** Integrante del equipo de GondolIA (dueño o soporte), SPEC §6.6. */
public record PlatformUserDto(Long id, String fullName, String email, Role role, boolean active, Instant lastLoginAt,
                              Instant createdAt) {
}
