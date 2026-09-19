package com.gondolia.movements;

import com.gondolia.common.PageResponse;
import com.gondolia.movements.dto.ExpirationDtos.BulkDiscardRequest;
import com.gondolia.movements.dto.ExpirationDtos.BulkDiscardResultDto;
import com.gondolia.movements.dto.ExpirationDtos.DiscardRequest;
import com.gondolia.movements.dto.ExpirationDtos.ExpirationBucket;
import com.gondolia.movements.dto.ExpirationDtos.ExpirationRowDto;
import com.gondolia.movements.dto.ExpirationDtos.ExpirationSummaryDto;
import com.gondolia.movements.dto.MovementDtos.MovementDto;
import com.gondolia.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Vencimientos por sucursal (SPEC §6.4). Ver: jefe, administrador y empleado (en sus sucursales). Descartar:
 * administrador y empleado.
 */
@Tag(name = "Vencimientos")
@RestController
@RequestMapping("/api/tenant/expirations")
@RequiredArgsConstructor
public class ExpirationsController {

    private final ExpirationsService expirationsService;

    @Operation(summary = "Lotes por vencer o vencidos",
            description = "Buckets Vencido / Crítico / Por vencer / Próximo según la configuración del comercio.")
    @GetMapping
    @PreAuthorize(Roles.TENANT_INVENTORY_READ)
    public PageResponse<ExpirationRowDto> list(
            @RequestParam(required = false) ExpirationBucket bucket,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long branchId,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "no puede ser negativa") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "tiene que ser al menos 1")
            @Max(value = 100, message = "no puede superar 100") int size) {
        return expirationsService.list(bucket, q, branchId, page, size);
    }

    @Operation(summary = "Resumen de vencimientos por bucket")
    @GetMapping("/summary")
    @PreAuthorize(Roles.TENANT_INVENTORY_READ)
    public ExpirationSummaryDto summary(@RequestParam(required = false) Long branchId) {
        return expirationsService.summary(branchId);
    }

    @Operation(summary = "Descartar un lote",
            description = "Registra una baja por vencimiento (WASTE_EXPIRED). Sin cantidad descarta todo el remanente.")
    @PostMapping("/{lotId}/discard")
    @PreAuthorize(Roles.TENANT_INVENTORY)
    public MovementDto discard(@PathVariable Long lotId,
                               @Valid @RequestBody(required = false) DiscardRequest request) {
        return expirationsService.discard(lotId, request);
    }

    @Operation(summary = "Descartar todos los lotes vencidos del alcance",
            description = "Da de baja todos los lotes con vencimiento anterior a hoy y remanente > 0.")
    @PostMapping("/discard-expired")
    @PreAuthorize(Roles.TENANT_INVENTORY)
    public BulkDiscardResultDto discardAllExpired(@Valid @RequestBody(required = false) BulkDiscardRequest request) {
        return expirationsService.discardAllExpired(request == null ? null : request.branchId(),
                request == null ? null : request.reason());
    }
}
