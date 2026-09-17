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
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Ejecución del análisis de IA para una sucursal.
 */
@Entity
@Table(name = "ai_runs")
@Getter
@Setter
@NoArgsConstructor
public class AiRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long tenantId;

    private Long branchId;

    @Enumerated(EnumType.STRING)
    private AiRunStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type")
    private AiRunTrigger triggerType;

    private String modelVersion;

    private Integer productsAnalyzed;

    private Integer recommendationsCreated;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode summary;

    private String errorMessage;

    private Instant startedAt;

    private Instant finishedAt;

    @PrePersist
    void onCreate() {
        if (startedAt == null) {
            startedAt = Timestamps.now();
        }
    }
}
