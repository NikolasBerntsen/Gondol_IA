package com.gondolia.alerts;

import com.gondolia.alerts.AlertService.AlertQuery;
import com.gondolia.alerts.dto.AlertCountsDto;
import com.gondolia.alerts.dto.AlertDto;
import com.gondolia.analytics.BranchScopeService;
import com.gondolia.analytics.BranchScopeService.Scope;
import com.gondolia.analytics.ParamMessages;
import com.gondolia.common.PageResponse;
import com.gondolia.common.error.BadRequestException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.domain.alert.AlertStatus;
import com.gondolia.domain.alert.AlertType;
import com.gondolia.domain.common.Severity;
import com.gondolia.security.CurrentUser;
import com.gondolia.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
 * Alertas del comercio (SPEC §6.5). Ver: jefe y administrador. Gestionar: solo administrador.
 */
@Tag(name = "Alertas")
@RestController
@RequestMapping("/api/tenant/alerts")
@PreAuthorize(Roles.TENANT_DASHBOARD)
@Validated
@RequiredArgsConstructor
public class AlertController {

    /** Valor de {@code status} y {@code type} para no filtrar. */
    static final String ALL = "ALL";

    private final AlertService alertService;
    private final BranchScopeService branchScope;

    private Scope scope() {
        return branchScope.current(CurrentUser.tenantId());
    }

    @Operation(summary = "Bandeja de alertas del alcance de sucursales")
    @GetMapping
    public PageResponse<AlertDto> list(
            @RequestParam(defaultValue = "OPEN") String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = ParamMessages.MIN) int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = ParamMessages.MIN)
            @Max(value = 100, message = ParamMessages.MAX) int size) {
        AlertQuery query = new AlertQuery(parseStatus(status), parseType(type), parseSeverity(severity), productId,
                q, page, size);
        return alertService.list(CurrentUser.tenantId(), scope(), query);
    }

    @Operation(summary = "Contadores para los filtros de la bandeja")
    @GetMapping("/counts")
    public AlertCountsDto counts() {
        return alertService.counts(CurrentUser.tenantId(), scope());
    }

    @Operation(summary = "Marcar la alerta como vista")
    @PostMapping("/{id}/acknowledge")
    @PreAuthorize(Roles.TENANT_ADMIN)
    public AlertDto acknowledge(@PathVariable Long id) {
        return alertService.acknowledge(CurrentUser.tenantId(), id, CurrentUser.id(), scope());
    }

    @Operation(summary = "Marcar la alerta como resuelta")
    @PostMapping("/{id}/resolve")
    @PreAuthorize(Roles.TENANT_ADMIN)
    public AlertDto resolve(@PathVariable Long id) {
        return alertService.resolve(CurrentUser.tenantId(), id, CurrentUser.id(), scope());
    }

    @Operation(summary = "Descartar la alerta")
    @PostMapping("/{id}/dismiss")
    @PreAuthorize(Roles.TENANT_ADMIN)
    public AlertDto dismiss(@PathVariable Long id) {
        return alertService.dismiss(CurrentUser.tenantId(), id, CurrentUser.id(), scope());
    }

    // ------------------------------------------------------------------ parseo

    private static AlertStatus parseStatus(String value) {
        if (isAll(value)) {
            return null;
        }
        try {
            return AlertStatus.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR,
                    "El estado de alerta no es válido: usá OPEN, ACKNOWLEDGED, RESOLVED, DISMISSED o ALL");
        }
    }

    private static AlertType parseType(String value) {
        if (isAll(value)) {
            return null;
        }
        try {
            return AlertType.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR, "El tipo de alerta no es válido");
        }
    }

    private static Severity parseSeverity(String value) {
        if (isAll(value)) {
            return null;
        }
        try {
            return Severity.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR,
                    "La severidad no es válida: usá INFO, WARNING, CRITICAL o ALL");
        }
    }

    private static boolean isAll(String value) {
        return value == null || value.isBlank() || ALL.equalsIgnoreCase(value.strip());
    }
}
