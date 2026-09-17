package com.gondolia.domain.announcement;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnnouncementReadRepository extends JpaRepository<AnnouncementRead, Long> {

    boolean existsByAnnouncementIdAndUserId(Long announcementId, Long userId);

    long countByAnnouncementId(Long announcementId);

    @Query("select r.announcementId from AnnouncementRead r where r.userId = :userId and r.announcementId in :ids")
    List<Long> findReadAnnouncementIds(@Param("userId") Long userId, @Param("ids") Collection<Long> announcementIds);
}
