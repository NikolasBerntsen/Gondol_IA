package com.gondolia.domain.ai;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface RecommendationRepository extends JpaRepository<Recommendation, Long>,
        JpaSpecificationExecutor<Recommendation> {

    Optional<Recommendation> findByIdAndTenantId(Long id, Long tenantId);

    Optional<Recommendation> findByBranchIdAndDedupeKeyAndStatus(Long branchId, String dedupeKey,
                                                                RecommendationStatus status);

    List<Recommendation> findByTenantIdAndBranchIdAndStatus(Long tenantId, Long branchId,
                                                            RecommendationStatus status);

    List<Recommendation> findByTenantIdAndTypeAndStatusAndDecidedAtBefore(Long tenantId, RecommendationType type,
                                                                          RecommendationStatus status,
                                                                          Instant decidedBefore);

    List<Recommendation> findByTenantIdAndBranchIdAndTypeAndStatusAndDecidedAtBefore(
            Long tenantId, Long branchId, RecommendationType type, RecommendationStatus status,
            Instant decidedBefore);

    long countByTenantIdAndBranchIdInAndStatus(Long tenantId, Collection<Long> branchIds,
                                               RecommendationStatus status);
}
