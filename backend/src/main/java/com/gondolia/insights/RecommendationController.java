package com.gondolia.insights;

import com.gondolia.analytics.BranchScopeService;
import com.gondolia.analytics.BranchScopeService.Scope;
import com.gondolia.common.PageResponse;
import com.gondolia.common.error.BadRequestException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.domain.ai.RecommendationStatus;
import com.gondolia.domain.ai.RecommendationType;
import com.gondolia.insights.RecommendationService.AcceptRequest;
import com.gondolia.insights.RecommendationService.RecommendationQuery;
import com.gondolia.insights.dto.RecommendationDecisionDto;
import com.gondolia.insights.dto.RecommendationDto;
import com.gondolia.security.CurrentUser;
import com.gondolia.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Recomendaciones de la IA (SPEC §6.5). Ver, aceptar y descartar: jefe y administrador (el jefe es quien decide).
 */
@Tag(name = "Recomendaciones de la IA")
@RestController
@RequestMapping("/api/tenant/recommendations")
@PreAuthorize(Roles.TENANT_DASHBOARD)
@Validated
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendationService recommendationService;
    private final BranchScopeService branchScope;

    /** Cuerpo de aceptar: nota opcional, cantidad opcional y descuento opcional. */
    public record AcceptBody(@Size(max = 500) String note,
                             @Min(1) @Max(100_000) Integer quantity,
                             @Min(1) @Max(100) Integer discountPct) {
    }

    /** Cuerpo de descartar. */
    public record DiscardBody(@Size(max = 500) String note) {
    }

    private Scope scope() {
        return branchScope.current(CurrentUser.tenantId());
    }

    @Operation(summary = "Recomendaciones del alcance de sucursales")
    @GetMapping
    public PageResponse<RecommendationDto> list(
            @RequestParam(defaultValue = "PENDING") String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        RecommendationQuery query = new RecommendationQuery(parseStatus(status), parseType(type), productId, q,
                page, size);
        return recommendationService.list(CurrentUser.tenantId(), scope(), query);
    }

    @Operation(summary = "Aceptar la recomendación y ejecutar su acción")
    @PostMapping("/{id}/accept")
    @PreAuthorize(Roles.TENANT_DASHBOARD)
    public RecommendationDecisionDto accept(@PathVariable Long id, @RequestBody(required = false) @Valid
                                            AcceptBody body) {
        AcceptRequest request = body == null ? new AcceptRequest(null, null, null)
                : new AcceptRequest(body.note(), body.quantity(), body.discountPct());
        return recommendationService.accept(CurrentUser.tenantId(), id, CurrentUser.id(), scope(), request);
    }

    @Operation(summary = "Descartar la recomendación")
    @PostMapping("/{id}/discard")
    @PreAuthorize(Roles.TENANT_DASHBOARD)
    public RecommendationDecisionDto discard(@PathVariable Long id, @RequestBody(required = false) @Valid
                                             DiscardBody body) {
        return recommendationService.discard(CurrentUser.tenantId(), id, CurrentUser.id(), scope(),
                body == null ? null : body.note());
    }

    // ------------------------------------------------------------------ parseo

    private static RecommendationStatus parseStatus(String value) {
        if (isAll(value)) {
            return null;
        }
        try {
            return RecommendationStatus.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR,
                    "El estado no es válido: usá PENDING, ACCEPTED, DISCARDED, EXPIRED o ALL");
        }
    }

    private static RecommendationType parseType(String value) {
        if (isAll(value)) {
            return null;
        }
        try {
            return RecommendationType.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR, "El tipo de recomendación no es válido");
        }
    }

    private static boolean isAll(String value) {
        return value == null || value.isBlank() || "ALL".equalsIgnoreCase(value.strip());
    }
}
