package com.gondolia.platform.dto;

import com.gondolia.domain.tenant.BusinessType;
import com.gondolia.domain.tenant.TenantModule;
import com.gondolia.domain.tenant.TenantPlan;
import com.gondolia.domain.user.Role;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Métricas agregadas de la plataforma (SPEC §6.6 y §14.3). <b>Solo agregados</b>: nunca datos de negocio de un
 * comercio (productos, stock, ventas, alertas, chats) ni qué comercios coinciden con un recall.
 */
public record PlatformMetrics(
        TenantCounts tenants,
        BranchCounts branches,
        Map<TenantPlan, Long> tenantsByPlan,
        Map<BusinessType, Long> tenantsByBusinessType,
        Engagement engagement,
        UserCounts users,
        Revenue revenue,
        List<GrowthPoint> growth,
        SupportSummary support,
        RecallSummary recalls,
        Map<TenantModule, ModuleAdoption> modules) {

    /**
     * Comercios por estado, con las altas y las bajas de los últimos 30 días (reloj de negocio). Altas y bajas cuentan
     * también a los comercios eliminados después, y cada comercio da de baja una sola vez por período.
     */
    public record TenantCounts(long total, long active, long disabled, long cancelled, long newLast30d,
                               long cancelledLast30d) {
    }

    /**
     * {@code total} = todas las sucursales registradas. {@code active}, {@code avgPerActiveTenant} y
     * {@code multiBranchTenants} miran lo mismo que el MRR: las sucursales activas de los comercios ACTIVE.
     */
    public record BranchCounts(long total, long active, double avgPerActiveTenant, long multiBranchTenants) {
    }

    /** Uso reciente: "activo" = algún usuario del comercio inició sesión en la ventana. */
    public record Engagement(long activeTenants7d, long activeTenants30d, long activeUsers7d) {
    }

    /** Usuarios de comercio (los de plataforma se administran en el equipo, §6.6). */
    public record UserCounts(long total, Map<Role, Long> byRole) {
    }

    /**
     * MRR = Σ de los comercios ACTIVE: sucursales activas × (precio del plan + adicionales de sus módulos).
     * {@code freemiumToPaidConversionPct} = comercios ACTIVE con plan pago sobre el total de ACTIVE.
     */
    public record Revenue(BigDecimal estimatedMrr, String currency, double freemiumToPaidConversionPct) {
    }

    /**
     * Un mes del crecimiento (12 meses, el último es el actual). {@code month} = {@code "2026-09"}. {@code cancelled}
     * = comercios cuya última baja o reactivación del mes fue una baja.
     */
    public record GrowthPoint(String month, long newTenants, long cancelled, long activeAtEndOfMonth) {
    }

    /** Agregados de soporte (sin contenido de tickets ni chats). */
    public record SupportSummary(long openTickets, long unassignedTickets, Double avgFirstResponseMinutes,
                                 long resolvedLast30d, Double avgRating) {
    }

    /**
     * Recalls en curso (publicados y con coincidencias sin resolver) y cuántos comercios distintos alcanzaron (solo
     * cantidades, nunca cuáles).
     */
    public record RecallSummary(long activeRecalls, long affectedTenantsTotal) {
    }

    /** Adopción de un módulo entre los comercios ACTIVE. */
    public record ModuleAdoption(long tenants, double pct) {
    }
}
