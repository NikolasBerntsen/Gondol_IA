package com.gondolia.modules;

import com.gondolia.domain.tenant.TenantModule;
import com.gondolia.domain.tenant.TenantPlan;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Catálogo de módulos (SPEC §14.1): nombre y descripción para la consola de dueños y el adicional mensual en ARS
 * <b>por sucursal activa</b>, que se suma al precio del plan para calcular la cuota y el MRR.
 * <p>
 * Los presets por plan se aplican al crear un comercio si no se indican módulos
 * ({@code ModuleService.applyPlanPreset}).
 */
public final class ModuleCatalog {

    /** Mensaje de 403 {@code MODULE_DISABLED}. */
    public static final String MSG_MODULE_DISABLED =
            "Esta función no está habilitada para tu comercio. Contactá a GondolIA para activarla.";

    /** Datos fijos de un módulo. */
    public record ModuleInfo(TenantModule module, String name, String description,
                             BigDecimal monthlyPricePerBranch) {
    }

    private static final Map<TenantModule, ModuleInfo> CATALOG = new EnumMap<>(TenantModule.class);
    private static final Map<TenantPlan, Set<TenantModule>> PRESETS = new EnumMap<>(TenantPlan.class);

    static {
        CATALOG.put(TenantModule.POS_GONDOLIA, new ModuleInfo(TenantModule.POS_GONDOLIA, "Punto de venta GondolIA",
                "Cajas por sucursal, cobro con escáner, medios de pago, tickets, anulaciones, cierre de caja",
                BigDecimal.valueOf(12_000)));
        CATALOG.put(TenantModule.POS_INTEGRATION, new ModuleInfo(TenantModule.POS_INTEGRATION,
                "Integración con POS propio",
                "API key por sucursal (webhook de ventas), importación CSV de ventas, simulador",
                BigDecimal.valueOf(8_000)));
        CATALOG.put(TenantModule.MULTI_BRANCH, new ModuleInfo(TenantModule.MULTI_BRANCH, "Multi-sucursal",
                "Más de una sucursal (hasta el límite del plan), transferencias, vista consolidada",
                BigDecimal.ZERO));

        PRESETS.put(TenantPlan.FREEMIUM, EnumSet.of(TenantModule.POS_GONDOLIA));
        PRESETS.put(TenantPlan.BASICO, EnumSet.of(TenantModule.POS_GONDOLIA, TenantModule.POS_INTEGRATION,
                TenantModule.MULTI_BRANCH));
        PRESETS.put(TenantPlan.PROFESIONAL, EnumSet.allOf(TenantModule.class));
    }

    private ModuleCatalog() {
    }

    /** Todos los módulos en el orden del enum. */
    public static List<ModuleInfo> all() {
        return EnumSet.allOf(TenantModule.class).stream().map(ModuleCatalog::of).toList();
    }

    public static ModuleInfo of(TenantModule module) {
        ModuleInfo info = CATALOG.get(module);
        if (info == null) {
            throw new IllegalArgumentException("Módulo desconocido: " + module);
        }
        return info;
    }

    public static String name(TenantModule module) {
        return of(module).name();
    }

    public static String description(TenantModule module) {
        return of(module).description();
    }

    /** Adicional mensual en ARS por sucursal activa (0 si no tiene). */
    public static BigDecimal monthlyPricePerBranch(TenantModule module) {
        return of(module).monthlyPricePerBranch();
    }

    /** Módulos que se habilitan por defecto según el plan (SPEC §14.1). */
    public static Set<TenantModule> preset(TenantPlan plan) {
        Set<TenantModule> preset = plan == null ? null : PRESETS.get(plan);
        return preset == null ? EnumSet.noneOf(TenantModule.class) : EnumSet.copyOf(preset);
    }

    /**
     * Cuota mensual estimada de un comercio: sucursales activas × (precio del plan + adicionales de sus módulos
     * habilitados). Es la base del MRR (SPEC §14.1).
     */
    public static BigDecimal monthlyFee(TenantPlan plan, Set<TenantModule> enabledModules, long activeBranches) {
        BigDecimal perBranch = plan == null ? BigDecimal.ZERO : plan.monthlyPricePerBranch();
        if (enabledModules != null) {
            for (TenantModule module : enabledModules) {
                perBranch = perBranch.add(monthlyPricePerBranch(module));
            }
        }
        return perBranch.multiply(BigDecimal.valueOf(Math.max(activeBranches, 0)));
    }
}
