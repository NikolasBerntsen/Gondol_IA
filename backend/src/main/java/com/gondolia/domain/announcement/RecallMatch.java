package com.gondolia.domain.announcement;

import com.gondolia.domain.common.Timestamps;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Coincidencia de un recall con un lote de una sucursal de un tenant (única por {@code (announcementId, lotId)}).
 */
@Entity
@Table(name = "recall_matches")
@Getter
@Setter
@NoArgsConstructor
public class RecallMatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long announcementId;

    private Long tenantId;

    private Long branchId;

    private Long productId;

    private Long lotId;

    private int quantityAtMatch;

    @Enumerated(EnumType.STRING)
    private RecallMatchStatus status = RecallMatchStatus.OPEN;

    @Enumerated(EnumType.STRING)
    private RecallResolution resolution;

    private String resolutionNote;

    private Instant matchedAt;

    private Instant acknowledgedAt;

    private Long acknowledgedBy;

    private Instant resolvedAt;

    private Long resolvedBy;

    @PrePersist
    void onCreate() {
        if (matchedAt == null) {
            matchedAt = Timestamps.now();
        }
    }
}
