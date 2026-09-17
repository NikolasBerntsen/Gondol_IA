package com.gondolia.common.events;

import com.gondolia.domain.tenant.TenantStatus;

/**
 * Cambió el estado de un tenant (habilitado, deshabilitado, baja, reactivación).
 */
public record TenantStatusChangedEvent(Long tenantId, TenantStatus from, TenantStatus to) {
}
