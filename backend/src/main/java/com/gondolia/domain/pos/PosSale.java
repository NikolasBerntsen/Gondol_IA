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
 * Venta (ticket no fiscal) del POS GondolIA (SPEC §15.2). Comparte {@code batchRef} con los movimientos de stock que
 * la registraron ({@code P-...}); al anularla, {@code StockService.voidSale} crea los {@code SALE_VOID} de ese mismo
 * batch.
 */
@Entity
@Table(name = "pos_sales")
@Getter
@Setter
@NoArgsConstructor
public class PosSale {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long tenantId;

    private Long branchId;

    private Long registerId;

    private Long sessionId;

    /** Correlativo por sucursal ({@code pos_branch_counters}). */
    private long number;

    /** {@code PosBranchCounter.ticketCode(branchId, number)}. */
    private String ticketCode;

    @Enumerated(EnumType.STRING)
    private PosSaleStatus status = PosSaleStatus.COMPLETED;

    /** Total a precio de lista, antes de los descuentos por lote. */
    @Column(precision = 14, scale = 2)
    private BigDecimal subtotal;

    @Column(precision = 14, scale = 2)
    private BigDecimal discountTotal = BigDecimal.ZERO;

    @Column(precision = 14, scale = 2)
    private BigDecimal total;

    private int itemsCount;

    private int units;

    /** Suma de los pagos (en efectivo se registra lo recibido, incluido el vuelto). */
    @Column(precision = 14, scale = 2)
    private BigDecimal paidTotal;

    @Column(precision = 14, scale = 2)
    private BigDecimal changeAmount = BigDecimal.ZERO;

    private String customerName;

    private String customerDoc;

    private Long cashierId;

    /** Igual a {@code stock_movements.batch_ref} de la venta. */
    private String batchRef;

    /** {@code true} si alguna línea se vendió sin stock suficiente ({@code allowShortage}). */
    private boolean hasShortage;

    /** Se completa al insertar si no se asignó (el seeder puede fijar ventas históricas). */
    @Column(updatable = false)
    private Instant createdAt;

    private Instant voidedAt;

    private Long voidedBy;

    private String voidReason;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Timestamps.now();
        }
    }

    public boolean isVoided() {
        return status == PosSaleStatus.VOIDED;
    }
}
