package com.gondolia.security;

import com.gondolia.domain.user.Role;
import java.time.Instant;

/**
 * Claims validados de un JWT de GondolIA: {@code sub}=id, {@code email}, {@code role}, {@code tid}=tenantId
 * (NULL para plataforma), {@code tv}=tokenVersion.
 */
public record JwtClaims(Long userId, String email, Role role, Long tenantId, int tokenVersion, Instant issuedAt,
                        Instant expiresAt) {
}
