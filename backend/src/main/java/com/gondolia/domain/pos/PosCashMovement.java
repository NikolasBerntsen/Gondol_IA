package com.gondolia.domain.pos;

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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Ingreso o retiro de efectivo durante un turno de caja (entra en el efectivo esperado del arqueo).
 */
@Entity
@Table(name = "pos_cash_movements")
@Getter
@Setter
@NoArgsConstructor
public class PosCashMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long tenantId;

    private Long sessionId;

    @Enumerated(EnumType.STRING)
    private CashMovementType type;

    @Column(precision = 14, scale = 2)
    private BigDecimal amount;

    private String reason;

    private Long userId;

    /** Se completa al insertar si no se asignó (el seeder puede fijar movimientos históricos). */
    @Column(updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Timestamps.now();
        }
    }
}
