package com.gondolia.domain.ai;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
 * Último análisis de IA de un producto en una sucursal (único por {@code (branchId, productId)}).
 */
@Entity
@Table(name = "product_insights")
@Getter
@Setter
@NoArgsConstructor
public class ProductInsight {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long tenantId;

    private Long branchId;

    private Long productId;

    private Long runId;

    @Enumerated(EnumType.STRING)
    private SalesPattern pattern;

    private String patternDescription;

    /** "A", "B" o "C". */
    private String abcClass;

    /** "X", "Y" o "Z". */
    private String xyzClass;

    @Column(precision = 10, scale = 3)
    private BigDecimal avgDailySales;

    @Column(precision = 8, scale = 2)
    private BigDecimal trendPct;

    /** Multiplicadores [lun..dom]. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode weekdayProfile;

    /** [{date,yhat,lo,hi}]. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode forecast;

    private String forecastMethod;

    @Column(precision = 10, scale = 2)
    private BigDecimal daysOfCover;

    private LocalDate predictedStockoutDate;

    private Integer reorderPoint;

    private Integer safetyStock;

    private Integer suggestedOrderQty;

    /** [{date,quantity,expected,score,kind}]. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode anomalies;

    /** [{lotId,daysToExpiry,quantity,...}]. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode lotRisks;

    @UpdateTimestamp
    private Instant updatedAt;
}
