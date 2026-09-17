package com.gondolia.platform.dto;

import com.gondolia.domain.tenant.TenantEventType;
import java.time.Instant;

/**
 * Entrada del historial de un comercio (alta, cambio de plan, bloqueos, módulos). {@code actorName} es el usuario
 * de GondolIA que hizo el cambio ({@code null} si lo hizo el sistema).
 */
public record TenantEventDto(Long id, TenantEventType type, String fromValue, String toValue, String reason,
                             String actorName, Instant createdAt) {
}
