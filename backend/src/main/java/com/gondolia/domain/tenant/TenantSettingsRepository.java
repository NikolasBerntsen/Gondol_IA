package com.gondolia.domain.tenant;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * La PK de {@link TenantSettings} es el {@code tenantId}: usar {@code findById(tenantId)}.
 */
public interface TenantSettingsRepository extends JpaRepository<TenantSettings, Long> {
}
