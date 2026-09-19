package com.gondolia.insights;

import com.gondolia.analytics.BranchScopeService;
import com.gondolia.analytics.BranchScopeService.Scope;
import com.gondolia.analytics.ParamMessages;
import com.gondolia.common.PageResponse;
import com.gondolia.common.error.BadRequestException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.domain.ai.SalesPattern;
import com.gondolia.insights.InsightsService.InsightQuery;
import com.gondolia.insights.dto.AiRunDto;
import com.gondolia.insights.dto.InsightsRunLaunchedDto;
import com.gondolia.insights.dto.InsightsSummaryDto;
import com.gondolia.insights.dto.ProductInsightDetail;
import com.gondolia.insights.dto.ProductInsightRow;
import com.gondolia.security.CurrentUser;
import com.gondolia.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inteligencia IA del comercio (SPEC §6.5). Ver: jefe y administrador. Recalcular: solo administrador.
 */
@Tag(name = "Inteligencia IA")
@RestController
@RequestMapping("/api/tenant/insights")
@PreAuthorize(Roles.TENANT_DASHBOARD)
@Validated
@RequiredArgsConstructor
public class InsightsController {

    private final InsightsService insightsService;
    private final BranchScopeService branchScope;

    private Scope scope() {
        return branchScope.current(CurrentUser.tenantId());
    }

    @Operation(summary = "Resumen de la inteligencia del comercio")
    @GetMapping("/summary")
    public InsightsSummaryDto summary() {
        return insightsService.summary(CurrentUser.tenantId(), scope());
    }

    @Operation(summary = "Patrones de venta por producto y sucursal")
    @GetMapping("/products")
    public PageResponse<ProductInsightRow> products(
            @RequestParam(required = false) String pattern,
            @RequestParam(required = false) String abc,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = ParamMessages.MIN) int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = ParamMessages.MIN)
            @Max(value = 100, message = ParamMessages.MAX) int size) {
        InsightQuery query = new InsightQuery(parsePattern(pattern), parseAbc(abc), q, page, size);
        return insightsService.products(CurrentUser.tenantId(), scope(), query);
    }

    @Operation(summary = "Ficha de inteligencia de un producto en una sucursal")
    @GetMapping("/products/{productId}")
    public ProductInsightDetail productDetail(@PathVariable Long productId,
                                              @RequestParam(required = false) Long branchId) {
        return insightsService.productDetail(CurrentUser.tenantId(), scope(), productId, branchId);
    }

    @Operation(summary = "Estado del último análisis de cada sucursal del alcance")
    @GetMapping("/runs/latest")
    public List<AiRunDto> latestRuns() {
        return insightsService.latestRuns(CurrentUser.tenantId(), scope());
    }

    @Operation(summary = "Recalcular la IA de las sucursales del alcance (asincrónico)")
    @PostMapping("/run")
    @PreAuthorize(Roles.TENANT_ADMIN)
    public InsightsRunLaunchedDto run() {
        return insightsService.launch(CurrentUser.tenantId(), scope());
    }

    // ------------------------------------------------------------------ parseo

    private static SalesPattern parsePattern(String value) {
        if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value.strip())) {
            return null;
        }
        try {
            return SalesPattern.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR, "El patrón de venta no es válido");
        }
    }

    private static String parseAbc(String value) {
        if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value.strip())) {
            return null;
        }
        String abc = value.strip().toUpperCase(Locale.ROOT);
        if (!List.of("A", "B", "C").contains(abc)) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR, "La clase ABC no es válida: usá A, B o C");
        }
        return abc;
    }
}
