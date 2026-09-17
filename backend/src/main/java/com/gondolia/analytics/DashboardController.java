package com.gondolia.analytics;

import com.gondolia.analytics.BranchScopeService.Scope;
import com.gondolia.analytics.dto.BranchComparisonRow;
import com.gondolia.analytics.dto.DashboardSummary;
import com.gondolia.analytics.dto.ReorderRow;
import com.gondolia.analytics.dto.SalesStockPoint;
import com.gondolia.analytics.dto.UpcomingExpirationRow;
import com.gondolia.security.CurrentUser;
import com.gondolia.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inicio del comercio (SPEC §1.1, §6.5). Lectura para jefe y administrador; todo por alcance de sucursales.
 */
@Tag(name = "Inicio del comercio")
@RestController
@RequestMapping("/api/tenant/dashboard")
@PreAuthorize(Roles.TENANT_DASHBOARD)
@Validated
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;
    private final BranchScopeService branchScope;

    private Scope scope() {
        return branchScope.current(CurrentUser.tenantId());
    }

    @Operation(summary = "Resumen del negocio (KPIs del Inicio)")
    @GetMapping("/summary")
    public DashboardSummary summary() {
        return dashboardService.summary(CurrentUser.tenantId(), scope());
    }

    @Operation(summary = "Tendencia de ventas netas y stock total")
    @GetMapping("/sales-stock-trend")
    public List<SalesStockPoint> salesStockTrend(
            @RequestParam(defaultValue = "30") @Min(1) @Max(DashboardService.MAX_TREND_DAYS) int days) {
        return dashboardService.salesStockTrend(CurrentUser.tenantId(), scope(), days);
    }

    @Operation(summary = "Comparación entre sucursales del alcance")
    @GetMapping("/branch-comparison")
    public List<BranchComparisonRow> branchComparison(
            @RequestParam(defaultValue = "30") @Min(1) @Max(DashboardService.MAX_TREND_DAYS) int days) {
        return dashboardService.branchComparison(CurrentUser.tenantId(), scope(), days);
    }

    @Operation(summary = "Próximos vencimientos (30 días)")
    @GetMapping("/upcoming-expirations")
    public List<UpcomingExpirationRow> upcomingExpirations(
            @RequestParam(defaultValue = "8") @Min(1) @Max(100) int limit) {
        return dashboardService.upcomingExpirations(CurrentUser.tenantId(), scope(), limit);
    }

    @Operation(summary = "Artículos a reponer (una fila por producto y sucursal)")
    @GetMapping("/reorder")
    public List<ReorderRow> reorder(@RequestParam(defaultValue = "8") @Min(1) @Max(200) int limit) {
        return dashboardService.reorder(CurrentUser.tenantId(), scope(), limit);
    }
}
