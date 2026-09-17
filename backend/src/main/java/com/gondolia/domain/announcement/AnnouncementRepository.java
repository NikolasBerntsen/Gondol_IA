package com.gondolia.domain.announcement;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AnnouncementRepository extends JpaRepository<Announcement, Long>,
        JpaSpecificationExecutor<Announcement> {

    List<Announcement> findByStatusOrderByPublishedAtDesc(AnnouncementStatus status);

    List<Announcement> findByKindAndStatus(AnnouncementKind kind, AnnouncementStatus status);

    List<Announcement> findByKindAndStatusAndRecallBarcode(AnnouncementKind kind, AnnouncementStatus status,
                                                           String recallBarcode);

    long countByKindAndStatus(AnnouncementKind kind, AnnouncementStatus status);
}
