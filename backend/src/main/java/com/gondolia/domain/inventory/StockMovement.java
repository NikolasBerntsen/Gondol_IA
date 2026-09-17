package com.gondolia.domain.inventory;

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
 * Movimiento de stock de una sucursal. {@code quantity} siempre es positiva: el signo lo da
 * {@link MovementType#isInbound()}. {@code lotId} NULL = venta sin stock (faltante).
 */
@Entity
@Table(name = "stock_movements")
@Getter
@Setter
@NoArgsConstructor
public class StockMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long tenantId;

    private Long branchId;

    private Long productId;

    private Long lotId;

    @Enumerated(EnumType.STRING)
    private MovementType type;

    private int quantity;

    @Column(precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(precision = 5, scale = 2)
    private BigDecimal discountPct;

    @Column(precision = 14, scale = 2)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    private MovementSource source = MovementSource.MANUAL;

    /** Agrupa las filas de una misma operación (venta {@code S-...}, transferencia {@code T-...}). */
    private String batchRef;

    private String reason;

    private Long userId;

    /** Momento del hecho; si no se asigna, el de la inserción. */
    private Instant occurredAt;

    @Column(updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        Instant now = Timestamps.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (occurredAt == null) {
            occurredAt = now;
        }
    }
}
