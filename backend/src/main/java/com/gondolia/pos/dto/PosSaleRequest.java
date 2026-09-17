package com.gondolia.pos.dto;

import com.gondolia.domain.pos.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * Cobro del POS (SPEC §15.2). Los medios de pago se pueden combinar; {@code allowShortage} permite vender aunque el
 * stock registrado no alcance (queda un faltante y la alerta {@code SALE_WITHOUT_STOCK}).
 */
public record PosSaleRequest(@NotNull(message = "es obligatorio") Long sessionId,
                             @NotEmpty(message = "agregá al menos un producto") @Valid List<Item> items,
                             @NotEmpty(message = "agregá al menos un pago") @Valid List<Payment> payments,
                             @Size(max = 150, message = "no puede superar los 150 caracteres") String customerName,
                             @Size(max = 20, message = "no puede superar los 20 caracteres") String customerDoc,
                             boolean allowShortage) {

    public record Item(@NotNull(message = "es obligatorio") Long productId,
                       @Min(value = 1, message = "tiene que ser al menos 1")
                       @Max(value = 9999, message = "no puede superar 9999") int quantity) {
    }

    public record Payment(@NotNull(message = "es obligatorio") PaymentMethod method,
                          @NotNull(message = "es obligatorio")
                          @DecimalMin(value = "0.01", message = "tiene que ser mayor a cero")
                          @DecimalMax(value = "99999999.99", message = "es demasiado grande") BigDecimal amount,
                          @Size(max = 100, message = "no puede superar los 100 caracteres") String reference) {
    }
}
