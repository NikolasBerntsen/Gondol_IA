package com.gondolia.domain.announcement;

import com.gondolia.domain.common.Timestamps;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Lectura de un aviso por un usuario (única por {@code (announcementId, userId)}).
 */
@Entity
@Table(name = "announcement_reads")
@Getter
@Setter
@NoArgsConstructor
public class AnnouncementRead {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long announcementId;

    private Long userId;

    private Instant readAt;

    public AnnouncementRead(Long announcementId, Long userId) {
        this.announcementId = announcementId;
        this.userId = userId;
    }

    @PrePersist
    void onCreate() {
        if (readAt == null) {
            readAt = Timestamps.now();
        }
    }
}
