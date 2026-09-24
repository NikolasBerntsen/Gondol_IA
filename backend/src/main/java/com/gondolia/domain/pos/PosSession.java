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
 * Turno de caja: apertura con efectivo inicial, ventas y cierre con arqueo (SPEC §15.2). La base garantiza un solo
 * turno {@code OPEN} por caja y por usuario (índices únicos parciales).
 * <p>
 * {@code expectedCash = openingCash + Σ pagos CASH − Σ vuelto + CASH_IN − CASH_OUT − efectivo neto de ventas anuladas};
 * {@code cashDifference = countedCash − expectedCash}.
 */
@Entity
@Table(name = "pos_sessions")
@Getter
@Setter
@NoArgsConstructor
public class PosSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long tenantId;

    private Long branchId;

    private Long registerId;

    @Enumerated(EnumType.STRING)
    private PosSessionStatus status = PosSessionStatus.OPEN;

    private Long openedBy;

    private Long closedBy;

    @Column(precision = 14, scale = 2)
    private BigDecimal openingCash = BigDecimal.ZERO;

    @Column(precision = 14, scale = 2)
    private BigDecimal expectedCash;

    @Column(precision = 14, scale = 2)
    private BigDecimal countedCash;

    @Column(precision = 14, scale = 2)
    private BigDecimal cashDifference;

    private int salesCount;

    @Column(precision = 14, scale = 2)
    private BigDecimal salesTotal = BigDecimal.ZERO;

    private int voidedCount;

    @Column(precision = 14, scale = 2)
    private BigDecimal voidedTotal = BigDecimal.ZERO;

    private String closingNote;

    /**
     * El turno cerró sin ninguna venta vigente (el mostrador pide confirmarlo con un aviso). Se fija al cerrar, como
     * el arqueo: anular después una venta del turno cerrado no la cambia.
     */
    private boolean closedWithoutSales;

    /** Se completa al insertar si no se asignó (el seeder puede fijar turnos históricos). */
    private Instant openedAt;

    private Instant closedAt;

    @PrePersist
    void onCreate() {
        if (openedAt == null) {
            openedAt = Timestamps.now();
        }
    }

    public boolean isOpen() {
        return status == PosSessionStatus.OPEN;
    }
}
