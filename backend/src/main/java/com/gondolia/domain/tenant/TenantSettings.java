package com.gondolia.domain.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Configuración del comercio (1:1 con tenant, la PK es {@code tenant_id}).
 */
@Entity
@Table(name = "tenant_settings")
@Getter
@Setter
@NoArgsConstructor
public class TenantSettings {

    public static final String DEFAULT_CURRENCY = "ARS";

    @Id
    private Long tenantId;

    private String currency = DEFAULT_CURRENCY;

    @Enumerated(EnumType.STRING)
    private StockRotation stockRotation = StockRotation.FIFO;

    private int expiryWarningDays = 15;

    private int expiryCriticalDays = 5;

    private int defaultLeadTimeDays = 3;

    private int targetCoverageDays = 14;

    @Column(precision = 4, scale = 3)
    private BigDecimal serviceLevel = new BigDecimal("0.950");

    private int maxDiscountPct = 40;

    @UpdateTimestamp
    private Instant updatedAt;

    /** Settings con los valores por defecto del esquema para un tenant (sin persistir). */
    public static TenantSettings defaultsFor(Long tenantId) {
        TenantSettings settings = new TenantSettings();
        settings.setTenantId(tenantId);
        return settings;
    }
}
