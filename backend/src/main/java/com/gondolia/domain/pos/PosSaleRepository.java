package com.gondolia.domain.pos;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface PosSaleRepository extends JpaRepository<PosSale, Long>, JpaSpecificationExecutor<PosSale> {

    Optional<PosSale> findByIdAndTenantId(Long id, Long tenantId);

    /** La venta del POS que originó un batch de movimientos de stock ({@code P-...}). */
    Optional<PosSale> findByTenantIdAndBatchRef(Long tenantId, String batchRef);

    List<PosSale> findByTenantIdAndBatchRefIn(Long tenantId, Collection<String> batchRefs);

    List<PosSale> findBySessionIdOrderByNumberAsc(Long sessionId);

    Page<PosSale> findBySessionIdOrderByCreatedAtDesc(Long sessionId, Pageable pageable);

    Page<PosSale> findByTenantIdAndBranchIdInOrderByCreatedAtDesc(Long tenantId, Collection<Long> branchIds,
                                                                  Pageable pageable);

    Optional<PosSale> findByBranchIdAndTicketCode(Long branchId, String ticketCode);

    long countBySessionIdAndStatus(Long sessionId, PosSaleStatus status);
}
