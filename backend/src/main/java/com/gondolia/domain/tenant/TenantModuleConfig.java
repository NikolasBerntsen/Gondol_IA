package com.gondolia.domain.tenant;

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
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Módulo habilitado (o deshabilitado explícitamente) para un comercio (SPEC §14). Sin fila = deshabilitado;
 * {@code updatedBy} es el dueño de GondolIA que hizo el último cambio.
 */
@Entity
@Table(name = "tenant_modules")
@Getter
@Setter
@NoArgsConstructor
public class TenantModuleConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long tenantId;

    @Enumerated(EnumType.STRING)
    private TenantModule module;

    private boolean enabled;

    private Long updatedBy;

    /** Se completa al insertar si no se asignó (el seeder puede fijar fechas históricas). */
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    public TenantModuleConfig(Long tenantId, TenantModule module, boolean enabled, Long updatedBy) {
        this.tenantId = tenantId;
        this.module = module;
        this.enabled = enabled;
        this.updatedBy = updatedBy;
    }

    @PrePersist
    void onCreate() {
        Instant now = Timestamps.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }
}
