package com.gondolia.domain.ai;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiRunRepository extends JpaRepository<AiRun, Long> {

    Optional<AiRun> findByIdAndTenantId(Long id, Long tenantId);

    Optional<AiRun> findFirstByTenantIdOrderByStartedAtDesc(Long tenantId);

    Optional<AiRun> findFirstByBranchIdOrderByStartedAtDesc(Long branchId);

    Optional<AiRun> findFirstByBranchIdAndStatusOrderByStartedAtDesc(Long branchId, AiRunStatus status);

    boolean existsByBranchIdAndStatus(Long branchId, AiRunStatus status);

    boolean existsByBranchIdAndStatusAndStartedAtAfter(Long branchId, AiRunStatus status, Instant after);
}
