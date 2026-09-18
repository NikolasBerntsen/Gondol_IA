package com.gondolia.platform.dto;

import com.gondolia.domain.tenant.TenantModule;
import java.math.BigDecimal;

/**
 * Módulo del catálogo con su adopción (SPEC §14.3): cuántos comercios <b>ACTIVE</b> lo tienen habilitado y qué
 * porcentaje representan.
 */
public record ModuleCatalogItem(TenantModule module, String name, String description,
                                BigDecimal monthlyPricePerBranch, long enabledTenants, double adoptionPct,
                                long activeTenants) {
}
