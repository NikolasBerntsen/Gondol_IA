package com.gondolia.analytics;

import com.gondolia.analytics.dto.StatisticsOverview;
import com.gondolia.security.CurrentUser;
import com.gondolia.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Estadísticas del comercio (SPEC §6.5), lectura para jefe y administrador.
 */
@Tag(name = "Estadísticas")
@RestController
@RequestMapping("/api/tenant/statistics")
@PreAuthorize(Roles.TENANT_DASHBOARD)
@Validated
@RequiredArgsConstructor
public class StatisticsController {

    private final StatisticsService statisticsService;
    private final BranchScopeService branchScope;

    @Operation(summary = "Panorama de ventas, rotación, pérdidas e impacto de la IA")
    @GetMapping("/overview")
    public StatisticsOverview overview(
            @RequestParam(defaultValue = "90") @Min(StatisticsService.MIN_DAYS) @Max(StatisticsService.MAX_DAYS)
            int days) {
        Long tenantId = CurrentUser.tenantId();
        return statisticsService.overview(tenantId, branchScope.current(tenantId), days);
    }
}
