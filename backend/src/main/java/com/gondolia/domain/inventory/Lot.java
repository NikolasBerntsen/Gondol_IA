package com.gondolia.domain.inventory;

import com.gondolia.common.util.LotNumbers;
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
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Lote de mercadería de una sucursal. Cada ingreso crea un lote nuevo (puede repetir número o vencimiento de otro).
 * {@code receivedAt} define el orden FIFO; una transferencia conserva el {@code receivedAt} del lote de origen
 * ({@code originLotId}).
 */
@Entity
@Table(name = "lots")
@Getter
@Setter
@NoArgsConstructor
public class Lot {

    /** Largo máximo de {@code lot_number} (y de su forma normalizada) en el esquema. */
    public static final int MAX_LOT_NUMBER_LENGTH = 60;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long tenantId;

    private Long branchId;

    private Long productId;

    /** Lote de origen si vino por transferencia entre sucursales. */
    private Long originLotId;

    private Long supplierId;

    private String lotNumber;

    /** {@code LotNumbers.normalize(lotNumber)}. */
    private String lotNumberNormalized;

    private LocalDate expiryDate;

    private int initialQuantity;

    /** Remanente. */
    private int quantity;

    @Column(precision = 12, scale = 2)
    private BigDecimal costPrice;

    /** Instante de ingreso (orden FIFO). Si no se asigna, el momento de la inserción. */
    private Instant receivedAt;

    @Enumerated(EnumType.STRING)
    private LotStatus status = LotStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    private MovementSource source = MovementSource.MANUAL;

    /** Descuento activo en porcentaje (0..100), por recomendación aceptada. */
    @Column(precision = 5, scale = 2)
    private BigDecimal discountPct;

    private Instant discountStartedAt;

    private Long createdBy;

    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Timestamps.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (receivedAt == null) {
            receivedAt = now;
        }
    }

    /** Asigna el número de lote guardando también su forma normalizada. */
    public void assignLotNumber(String rawLotNumber) {
        this.lotNumber = LotNumbers.clean(rawLotNumber);
        this.lotNumberNormalized = LotNumbers.normalize(rawLotNumber);
    }

    /** {@code true} si tiene fecha de vencimiento anterior a {@code today}. */
    public boolean isExpiredOn(LocalDate today) {
        return expiryDate != null && expiryDate.isBefore(today);
    }

    /** Vendible: {@code ACTIVE}, con remanente y no vencido (SPEC §4.2). */
    public boolean isSellableOn(LocalDate today) {
        return status == LotStatus.ACTIVE && quantity > 0 && !isExpiredOn(today);
    }
}
