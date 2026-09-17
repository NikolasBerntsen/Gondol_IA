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
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Producto del catálogo del tenant (compartido por todas sus sucursales). {@code minStock} aplica a cada sucursal.
 */
@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long tenantId;

    /** Normalizado con {@code Barcodes.normalize}; único por tenant. */
    private String barcode;

    private String name;

    private String brand;

    private String description;

    private Long categoryId;

    private Long supplierId;

    @Enumerated(EnumType.STRING)
    private ProductUnit unit = ProductUnit.UNIDAD;

    @Column(precision = 12, scale = 2)
    private BigDecimal costPrice = BigDecimal.ZERO;

    @Column(precision = 12, scale = 2)
    private BigDecimal salePrice = BigDecimal.ZERO;

    private int minStock = 0;

    private boolean perishable = true;

    private boolean active = true;

    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Timestamps.now();
        }
    }
}
