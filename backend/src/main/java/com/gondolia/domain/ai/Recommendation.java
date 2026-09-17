package com.gondolia.domain.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.gondolia.domain.common.Timestamps;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * Recomendación de IA para una sucursal. Solo puede haber una PENDING por {@code (branchId, dedupeKey)}.
 */
@Entity
@Table(name = "recommendations")
@Getter
@Setter
@NoArgsConstructor
public class Recommendation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long tenantId;

    private Long branchId;

    private Long runId;

    @Enumerated(EnumType.STRING)
    private RecommendationType type;

    @Enumerated(EnumType.STRING)
    private RecommendationStatus status = RecommendationStatus.PENDING;

    private Long productId;

    private Long lotId;

    private String title;

    private String explanation;

    private Integer suggestedQuantity;

    @Column(precision = 5, scale = 2)
    private BigDecimal suggestedDiscountPct;

    private LocalDate suggestedDate;

    /** 1..100. */
    private int priority = 50;

    /** 0..1. */
    @Column(precision = 4, scale = 3)
    private BigDecimal confidence;

    /** $ estimado ahorrado/recuperado. */
    @Column(precision = 14, scale = 2)
    private BigDecimal expectedImpact;

    private String dedupeKey;

    private Long decidedBy;

    private Instant decidedAt;

    private String decisionNote;

    /** Resultado medido (feedback para la IA). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode outcome;

    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Timestamps.now();
        }
    }
}
