package com.gondolia.domain.notification;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Optional<Notification> findByIdAndUserId(Long id, Long userId);

    Page<Notification> findByUserIdOrderByCreatedAtDescIdDesc(Long userId, Pageable pageable);

    Page<Notification> findByUserIdAndReadAtIsNullOrderByCreatedAtDescIdDesc(Long userId, Pageable pageable);

    long countByUserIdAndReadAtIsNull(Long userId);

    @Transactional
    @Modifying
    @Query("update Notification n set n.readAt = :readAt where n.userId = :userId and n.readAt is null")
    int markAllRead(@Param("userId") Long userId, @Param("readAt") Instant readAt);

    @Transactional
    @Modifying
    @Query("update Notification n set n.readAt = :readAt where n.userId = :userId"
            + " and n.referenceType = :referenceType and n.referenceId = :referenceId and n.readAt is null")
    int markReadByReference(@Param("userId") Long userId, @Param("referenceType") String referenceType,
                            @Param("referenceId") Long referenceId, @Param("readAt") Instant readAt);
}
