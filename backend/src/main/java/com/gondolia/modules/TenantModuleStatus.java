package com.gondolia.modules;

import com.gondolia.domain.tenant.TenantModule;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Estado de un módulo para un comercio (SPEC §14.2): datos del catálogo más si está habilitado y quién lo cambió por
 * última vez ({@code updatedAt} y {@code updatedByName} son null si nunca se tocó).
 */
public record TenantModuleStatus(
        TenantModule module,
        String name,
        String description,
        BigDecimal monthlyPricePerBranch,
        boolean enabled,
        Instant updatedAt,
        String updatedByName) {

    /** Estado de un módulo que nunca se configuró: deshabilitado. */
    public static TenantModuleStatus disabled(TenantModule module) {
        ModuleCatalog.ModuleInfo info = ModuleCatalog.of(module);
        return new TenantModuleStatus(module, info.name(), info.description(), info.monthlyPricePerBranch(), false,
                null, null);
    }
}
