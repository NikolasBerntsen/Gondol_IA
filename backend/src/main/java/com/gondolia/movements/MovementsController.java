package com.gondolia.movements;

import com.gondolia.common.PageResponse;
import com.gondolia.domain.inventory.MovementSource;
import com.gondolia.domain.inventory.MovementType;
import com.gondolia.movements.dto.MovementDtos.AdjustmentRequest;
import com.gondolia.movements.dto.MovementDtos.MovementDto;
import com.gondolia.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Historial de movimientos de stock y ajustes manuales (SPEC §6.4).
 * <p>
 * El listado lo ven jefe y administrador; los ajustes los pueden registrar administrador y empleado, con tipos
 * distintos (SPEC §3.3).
 */
@Tag(name = "Movimientos")
@RestController
@RequestMapping("/api/tenant/movements")
@RequiredArgsConstructor
public class MovementsController {

    private final MovementsService movementsService;

    @Operation(summary = "Historial de movimientos de stock")
    @PreAuthorize(Roles.TENANT_DASHBOARD)
    @GetMapping
    public PageResponse<MovementDto> list(
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) MovementType type,
            @RequestParam(required = false) MovementSource source,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long branchId,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "no puede ser negativa") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "tiene que ser al menos 1")
            @Max(value = 100, message = "no puede superar 100") int size) {
        return movementsService.list(productId, type, source, q, from, to, branchId, page, size);
    }

    @Operation(summary = "Registrar un ajuste de stock",
            description = "El administrador puede registrar cualquier tipo; el empleado, solo mermas por "
                    + "vencimiento o daño y en sus sucursales.")
    @PreAuthorize(Roles.TENANT_INVENTORY)
    @PostMapping("/adjustments")
    public MovementDto adjust(@Valid @RequestBody AdjustmentRequest request) {
        return movementsService.adjust(request);
    }
}
