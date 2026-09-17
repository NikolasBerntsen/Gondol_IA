package com.gondolia.domain.imports;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportJobRepository extends JpaRepository<ImportJob, Long> {

    Optional<ImportJob> findByIdAndTenantId(Long id, Long tenantId);

    Page<ImportJob> findByTenantIdOrderByCreatedAtDescIdDesc(Long tenantId, Pageable pageable);

    List<ImportJob> findByTenantIdAndStatusOrderByCreatedAtDesc(Long tenantId, ImportStatus status);

    List<ImportJob> findByStatusInOrderByIdAsc(Collection<ImportStatus> statuses);

    long countByTenantIdAndStatus(Long tenantId, ImportStatus status);
}
