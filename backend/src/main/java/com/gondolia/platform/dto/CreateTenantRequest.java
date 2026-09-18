package com.gondolia.platform.dto;

import com.gondolia.domain.tenant.BusinessType;
import com.gondolia.domain.tenant.StockRotation;
import com.gondolia.domain.tenant.TenantModule;
import com.gondolia.domain.tenant.TenantPlan;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Alta de un comercio (SPEC §6.6): crea el tenant, su configuración, la primera sucursal, los tres usuarios
 * (jefe, administrador y empleado, este último asignado a esa sucursal) y los módulos indicados o el preset del plan.
 */
public record CreateTenantRequest(
        @NotBlank(message = "es obligatorio") @Size(max = 150, message = "no puede superar los 150 caracteres")
        String name,

        @Size(max = 200, message = "no puede superar los 200 caracteres") String legalName,

        @Size(max = 20, message = "no puede superar los 20 caracteres") String taxId,

        @NotNull(message = "elegí un rubro") BusinessType businessType,

        @NotNull(message = "elegí un plan") TenantPlan plan,

        @Size(max = 150, message = "no puede superar los 150 caracteres") String contactName,

        @Email(message = "no es un email válido") @Size(max = 150, message = "no puede superar los 150 caracteres")
        String contactEmail,

        @Size(max = 50, message = "no puede superar los 50 caracteres") String contactPhone,

        @Size(max = 200, message = "no puede superar los 200 caracteres") String address,

        @Size(max = 100, message = "no puede superar los 100 caracteres") String city,

        @Size(max = 100, message = "no puede superar los 100 caracteres") String province,

        @Size(max = 2000, message = "no puede superar los 2000 caracteres") String notes,

        /** Opcional: si no viene se crea "Sucursal Principal" con la dirección del comercio. */
        @Valid BranchRequest firstBranch,

        @NotNull(message = "cargá los datos del jefe") @Valid NewUserRequest boss,

        @NotNull(message = "cargá los datos del administrador") @Valid NewUserRequest admin,

        @NotNull(message = "cargá los datos del empleado") @Valid NewUserRequest employee,

        /** Módulos a habilitar. Si es {@code null} se aplica el preset del plan (SPEC §14.1). */
        List<TenantModule> modules,

        /** Rotación del comercio; por defecto FIFO. */
        StockRotation stockRotation) {

    /** Primera sucursal del comercio. */
    public record BranchRequest(
            @Size(max = 100, message = "no puede superar los 100 caracteres") String name,
            @Size(max = 20, message = "no puede superar los 20 caracteres") String code,
            @Size(max = 200, message = "no puede superar los 200 caracteres") String address,
            @Size(max = 100, message = "no puede superar los 100 caracteres") String city,
            @Size(max = 100, message = "no puede superar los 100 caracteres") String province) {
    }

    /** Usuario inicial del comercio. */
    public record NewUserRequest(
            @NotBlank(message = "es obligatorio")
            @Size(max = 150, message = "no puede superar los 150 caracteres") String fullName,

            @NotBlank(message = "es obligatorio") @Email(message = "no es un email válido")
            @Size(max = 150, message = "no puede superar los 150 caracteres") String email,

            @NotBlank(message = "es obligatoria")
            @Size(min = 8, max = 72, message = "tiene que tener entre 8 y 72 caracteres") String password) {
    }
}
