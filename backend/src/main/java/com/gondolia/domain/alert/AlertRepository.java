package com.gondolia.domain.alert;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AlertRepository extends JpaRepository<Alert, Long>, JpaSpecificationExecutor<Alert> {

    Optional<Alert> findByIdAndTenantId(Long id, Long tenantId);

    /** Alerta abierta (OPEN/ACKNOWLEDGED) con esa clave de deduplicación. */
    Optional<Alert> findFirstByTenantIdAndDedupeKeyAndStatusIn(Long tenantId, String dedupeKey,
                                                              Collection<AlertStatus> statuses);

    List<Alert> findByTenantIdAndStatusIn(Long tenantId, Collection<AlertStatus> statuses);

    List<Alert> findByTenantIdAndBranchIdAndStatusIn(Long tenantId, Long branchId, Collection<AlertStatus> statuses);

    List<Alert> findByTenantIdAndBranchIdAndTypeInAndStatusIn(Long tenantId, Long branchId,
                                                             Collection<AlertType> types,
                                                             Collection<AlertStatus> statuses);

    long countByTenantIdAndBranchIdInAndStatusIn(Long tenantId, Collection<Long> branchIds,
                                                 Collection<AlertStatus> statuses);
}
