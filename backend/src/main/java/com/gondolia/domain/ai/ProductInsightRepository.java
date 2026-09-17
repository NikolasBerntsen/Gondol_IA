package com.gondolia.domain.ai;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ProductInsightRepository extends JpaRepository<ProductInsight, Long>,
        JpaSpecificationExecutor<ProductInsight> {

    Optional<ProductInsight> findByIdAndTenantId(Long id, Long tenantId);

    Optional<ProductInsight> findByTenantIdAndBranchIdAndProductId(Long tenantId, Long branchId, Long productId);

    List<ProductInsight> findByTenantIdAndBranchId(Long tenantId, Long branchId);

    List<ProductInsight> findByTenantIdAndBranchIdIn(Long tenantId, Collection<Long> branchIds);

    List<ProductInsight> findByTenantIdAndProductId(Long tenantId, Long productId);
}
