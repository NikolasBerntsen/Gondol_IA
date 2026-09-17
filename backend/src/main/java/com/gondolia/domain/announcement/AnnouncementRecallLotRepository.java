package com.gondolia.domain.announcement;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnnouncementRecallLotRepository extends JpaRepository<AnnouncementRecallLot, Long> {

    List<AnnouncementRecallLot> findByAnnouncementIdOrderByIdAsc(Long announcementId);

    List<AnnouncementRecallLot> findByAnnouncementIdIn(Collection<Long> announcementIds);
}
