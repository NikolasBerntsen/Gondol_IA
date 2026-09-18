package com.gondolia.tenantadmin.dto;

import com.gondolia.domain.tenant.StockRotation;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;

/**
 * Cambio de configuración del comercio. Los rangos son los que aceptan el motor de alertas y la IA (SPEC §6.5, §8.2).
 * {@code currency} es opcional: si viene vacío se conserva la actual.
 */
public record TenantSettingsRequest(
        @Pattern(regexp = "^[A-Za-z]{3}$", message = "tiene que ser un código de 3 letras (por ejemplo ARS)")
        String currency,

        @NotNull(message = "es obligatoria")
        StockRotation stockRotation,

        @Min(value = 1, message = "tiene que ser al menos 1 día")
        @Max(value = 180, message = "no puede superar los 180 días")
        int expiryWarningDays,

        @Min(value = 1, message = "tiene que ser al menos 1 día")
        @Max(value = 60, message = "no puede superar los 60 días")
        int expiryCriticalDays,

        @Min(value = 0, message = "no puede ser negativo")
        @Max(value = 60, message = "no puede superar los 60 días")
        int defaultLeadTimeDays,

        @Min(value = 1, message = "tiene que ser al menos 1 día")
        @Max(value = 180, message = "no puede superar los 180 días")
        int targetCoverageDays,

        @NotNull(message = "es obligatorio")
        @DecimalMin(value = "0.500", message = "tiene que ser al menos 0,50")
        @DecimalMax(value = "0.999", message = "no puede superar 0,999")
        @Digits(integer = 1, fraction = 3, message = "admite hasta 3 decimales")
        BigDecimal serviceLevel,

        @Min(value = 0, message = "no puede ser negativo")
        @Max(value = 80, message = "no puede superar el 80%")
        int maxDiscountPct) {
}
