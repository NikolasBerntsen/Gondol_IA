package com.gondolia.catalog.dto;

import com.gondolia.domain.inventory.ProductUnit;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Alta o edición de un producto (SPEC §6.3). {@code categoryName} permite crear la categoría al vuelo desde el
 * formulario: si no existe una con ese nombre en el comercio, se crea (se ignora cuando llega {@code categoryId}).
 */
public record ProductRequest(
        @Size(max = 32, message = "no puede superar los 32 caracteres")
        String barcode,

        @NotBlank(message = "es obligatorio")
        @Size(max = 200, message = "no puede superar los 200 caracteres")
        String name,

        @Size(max = 100, message = "no puede superar los 100 caracteres")
        String brand,

        @Size(max = 2000, message = "no puede superar los 2000 caracteres")
        String description,

        Long categoryId,

        @Size(max = 100, message = "no puede superar los 100 caracteres")
        String categoryName,

        Long supplierId,

        ProductUnit unit,

        @PositiveOrZero(message = "no puede ser negativo")
        @DecimalMax(value = "9999999999", message = "es demasiado grande")
        BigDecimal costPrice,

        @PositiveOrZero(message = "no puede ser negativo")
        @DecimalMax(value = "9999999999", message = "es demasiado grande")
        BigDecimal salePrice,

        @Min(value = 0, message = "no puede ser negativo")
        @Max(value = 1000000, message = "es demasiado grande")
        Integer minStock,

        Boolean perishable,

        Boolean active) {
}
