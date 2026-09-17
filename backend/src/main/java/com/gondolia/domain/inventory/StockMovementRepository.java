package com.gondolia.domain.inventory;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface StockMovementRepository extends JpaRepository<StockMovement, Long>,
        JpaSpecificationExecutor<StockMovement> {

    Optional<StockMovement> findByIdAndTenantId(Long id, Long tenantId);

    List<StockMovement> findByTenantIdAndBatchRefOrderByIdAsc(Long tenantId, String batchRef);

    boolean existsByTenantIdAndBatchRef(Long tenantId, String batchRef);

    boolean existsByTenantIdAndProductId(Long tenantId, Long productId);

    List<StockMovement> findByTenantIdAndLotIdOrderByOccurredAtAscIdAsc(Long tenantId, Long lotId);

    /** Último movimiento de un lote (define si un lote agotado queda DEPLETED o EXPIRED_DISCARDED). */
    Optional<StockMovement> findFirstByLotIdOrderByIdDesc(Long lotId);

    Optional<StockMovement> findFirstByTenantIdAndBranchIdAndSourceAndTypeOrderByOccurredAtDesc(
            Long tenantId, Long branchId, MovementSource source, MovementType type);
}
