package com.gondolia.domain.tenant;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantEventRepository extends JpaRepository<TenantEvent, Long> {

    List<TenantEvent> findByTenantIdOrderByCreatedAtDesc(Long tenantId);

    List<TenantEvent> findByTypeOrderByCreatedAtAsc(TenantEventType type);
}
