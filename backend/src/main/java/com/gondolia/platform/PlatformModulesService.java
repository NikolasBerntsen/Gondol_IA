package com.gondolia.platform;

import com.gondolia.common.PageResponse;
import com.gondolia.domain.tenant.TenantModule;
import com.gondolia.domain.tenant.TenantPlan;
import com.gondolia.domain.tenant.TenantStatus;
import com.gondolia.modules.ModuleCatalog;
import com.gondolia.modules.ModuleService;
import com.gondolia.modules.TenantModuleStatus;
import com.gondolia.platform.PlatformQueries.TenantFilters;
import com.gondolia.platform.PlatformQueries.TenantRow;
import com.gondolia.platform.dto.ModuleCatalogItem;
import com.gondolia.platform.dto.PlatformMetrics.ModuleAdoption;
import com.gondolia.platform.dto.TenantModulesRow;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Módulos por comercio desde la consola de dueños (SPEC §14.3): catálogo con adopción, matriz clientes × módulos y
 * el alta/baja de cada módulo (que delega en {@link ModuleService}, del núcleo).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlatformModulesService {

    private final ModuleService moduleService;
    private final PlatformMetricsService metricsService;
    private final PlatformQueries queries;
    private final TenantAdminService tenantAdminService;

    /** Catálogo de módulos con cuántos comercios ACTIVE los tienen habilitados. */
    public List<ModuleCatalogItem> catalog() {
        Map<TenantModule, ModuleAdoption> adoption = metricsService.moduleAdoption();
        long activeTenants = metricsService.activeTenantCount();
        return ModuleCatalog.all().stream()
                .map(info -> {
                    ModuleAdoption stats = adoption.getOrDefault(info.module(), new ModuleAdoption(0, 0));
                    return new ModuleCatalogItem(info.module(), info.name(), info.description(),
                            info.monthlyPricePerBranch(), stats.tenants(), stats.pct(), activeTenants);
                })
                .toList();
    }

    /** Matriz clientes × módulos con la cuota estimada de cada uno. */
    public PageResponse<TenantModulesRow> matrix(String q, TenantStatus status, TenantPlan plan, TenantModule module,
                                                 String sort, int page, int size) {
        TenantFilters filters = TenantFilters.of(q, status, plan, null, module);
        long total = queries.count(filters);
        if (total == 0) {
            return PageResponse.empty(page, size);
        }
        List<TenantRow> rows = queries.search(filters, sort, page, size);
        Map<Long, Set<TenantModule>> enabled = queries.enabledModules(rows.stream().map(TenantRow::id).toList());
        List<TenantModulesRow> content = rows.stream().map(row -> {
            Set<TenantModule> modules = enabled.getOrDefault(row.id(), EnumSet.noneOf(TenantModule.class));
            Map<TenantModule, Boolean> flags = new EnumMap<>(TenantModule.class);
            for (TenantModule value : TenantModule.values()) {
                flags.put(value, modules.contains(value));
            }
            BigDecimal fee = row.status() == TenantStatus.ACTIVE
                    ? ModuleCatalog.monthlyFee(row.plan(), modules, row.activeBranchCount())
                    : BigDecimal.ZERO;
            return new TenantModulesRow(row.id(), row.name(), row.businessType(), row.city(), row.plan(),
                    row.status(), row.activeBranchCount(), flags, fee, row.lastActivityAt());
        }).toList();
        return PageResponse.of(content, page, size, total);
    }

    /** Estado de los tres módulos de un comercio. */
    public List<TenantModuleStatus> statuses(Long tenantId) {
        tenantAdminService.requireTenant(tenantId);
        return moduleService.statuses(tenantId);
    }

    /**
     * Habilita o deshabilita un módulo. Deshabilitar {@code MULTI_BRANCH} con más de una sucursal activa responde
     * 409 {@code MODULE_IN_USE} (lo valida el núcleo).
     */
    @Transactional
    public TenantModuleStatus setEnabled(Long tenantId, TenantModule module, boolean enabled, Long actorUserId) {
        tenantAdminService.requireTenant(tenantId);
        return moduleService.setEnabled(tenantId, module, enabled, actorUserId);
    }
}
