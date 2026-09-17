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
 * Pago de una venta del POS (los medios se pueden combinar). En efectivo {@code amount} es lo recibido; el vuelto se
 * guarda en {@code pos_sales.change_amount}.
 */
@Entity
@Table(name = "pos_payments")
@Getter
@Setter
@NoArgsConstructor
public class PosPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long saleId;

    @Enumerated(EnumType.STRING)
    private PaymentMethod method;

    @Column(precision = 14, scale = 2)
    private BigDecimal amount;

    /** Número de operación, cupón o referencia de la transferencia. */
    private String reference;

    /** Se completa al insertar si no se asignó (el seeder puede fijar pagos históricos). */
    @Column(updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Timestamps.now();
        }
    }
}
