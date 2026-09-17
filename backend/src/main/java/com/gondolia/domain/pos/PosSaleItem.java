package com.gondolia.domain.pos;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Línea de una venta del POS. {@code lots} guarda de qué lotes salieron las unidades:
 * {@code [{lotId,lotNumber,expiryDate,quantity,unitPrice,discountPct}]}. El nombre y el código del producto quedan
 * copiados para que el ticket se pueda reimprimir aunque el producto cambie.
 */
@Entity
@Table(name = "pos_sale_items")
@Getter
@Setter
@NoArgsConstructor
public class PosSaleItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long saleId;

    private Long productId;

    private String barcode;

    private String productName;

    private int quantity;

    /** Precio de lista unitario, sin el descuento del lote. */
    @Column(precision = 12, scale = 2)
    private BigDecimal listUnitPrice;

    @Column(precision = 14, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(precision = 14, scale = 2)
    private BigDecimal lineTotal;

    /** Unidades vendidas sin stock registrado (movimiento {@code SALE} con {@code lot_id} NULL). */
    private int shortageQuantity;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode lots;
}
