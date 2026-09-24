package com.gondolia.platform.dto;

import com.gondolia.domain.tenant.BusinessType;
import com.gondolia.domain.tenant.StockRotation;
import com.gondolia.domain.tenant.TenantPlan;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Edición de los datos administrativos y del plan de un comercio (SPEC §6.6). Si cambia algún dato se registra
 * {@code DATA_UPDATED}; cambiar el plan registra {@code PLAN_CHANGED} (solo un dueño) y bajar a un plan con menos
 * sucursales que las activas devuelve 409 {@code BRANCH_LIMIT_REACHED}.
 */
public record UpdateTenantRequest(
        @NotBlank(message = "es obligatorio") @Size(max = 150, message = "no puede superar los 150 caracteres")
        String name,

        @Size(max = 200, message = "no puede superar los 200 caracteres") String legalName,

        @Size(max = 20, message = "no puede superar los 20 caracteres") String taxId,

        @NotNull(message = "elegí un rubro") BusinessType businessType,

        /**
         * Plan nuevo; si es {@code null} se deja el actual. Soporte no cambia el plan y lo manda así: si un dueño lo
         * cambió mientras editaba, guardar no lo pisa ni responde 403.
         */
        TenantPlan plan,

        @Size(max = 150, message = "no puede superar los 150 caracteres") String contactName,

        @Email(message = "no es un email válido") @Size(max = 150, message = "no puede superar los 150 caracteres")
        String contactEmail,

        @Size(max = 50, message = "no puede superar los 50 caracteres") String contactPhone,

        @Size(max = 200, message = "no puede superar los 200 caracteres") String address,

        @Size(max = 100, message = "no puede superar los 100 caracteres") String city,

        @Size(max = 100, message = "no puede superar los 100 caracteres") String province,

        @Size(max = 2000, message = "no puede superar los 2000 caracteres") String notes,

        /** Motivo del cambio de plan (queda en el historial). */
        @Size(max = 300, message = "no puede superar los 300 caracteres") String planChangeReason,

        /** Rotación del comercio; si es {@code null} se deja la actual. */
        StockRotation stockRotation) {
}
