package com.gondolia.domain.announcement;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecallMatchRepository extends JpaRepository<RecallMatch, Long> {

    Optional<RecallMatch> findByIdAndTenantId(Long id, Long tenantId);

    boolean existsByAnnouncementIdAndLotId(Long announcementId, Long lotId);

    Optional<RecallMatch> findByAnnouncementIdAndLotId(Long announcementId, Long lotId);

    List<RecallMatch> findByTenantIdAndBranchIdInOrderByMatchedAtDesc(Long tenantId, Collection<Long> branchIds);

    List<RecallMatch> findByTenantIdAndBranchIdInAndStatusInOrderByMatchedAtDesc(
            Long tenantId, Collection<Long> branchIds, Collection<RecallMatchStatus> statuses);

    List<RecallMatch> findByTenantIdAndLotIdAndStatusIn(Long tenantId, Long lotId,
                                                        Collection<RecallMatchStatus> statuses);

    List<RecallMatch> findByAnnouncementId(Long announcementId);

    long countByAnnouncementIdAndStatus(Long announcementId, RecallMatchStatus status);

    long countByTenantIdAndBranchIdInAndStatusIn(Long tenantId, Collection<Long> branchIds,
                                                 Collection<RecallMatchStatus> statuses);

    @Query("select count(distinct m.tenantId) from RecallMatch m where m.announcementId = :announcementId")
    long countDistinctTenantsByAnnouncementId(@Param("announcementId") Long announcementId);

    @Query("select distinct m.announcementId from RecallMatch m where m.tenantId = :tenantId")
    List<Long> findAnnouncementIdsByTenantId(@Param("tenantId") Long tenantId);
}
