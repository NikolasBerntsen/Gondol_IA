package com.gondolia.movements;

import com.gondolia.common.PageResponse;
import com.gondolia.domain.tenant.TenantModule;
import com.gondolia.modules.RequiresModule;
import com.gondolia.movements.dto.TransferDtos.TransferDto;
import com.gondolia.movements.dto.TransferDtos.TransferRequest;
import com.gondolia.movements.dto.TransferDtos.TransferSummaryDto;
import com.gondolia.movements.dto.TransferDtos.TransferableLotDto;
import com.gondolia.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Transferencias de stock entre sucursales (SPEC §6.4). Rol administrador y módulo {@code MULTI_BRANCH}.
 */
@Tag(name = "Transferencias")
@RestController
@RequestMapping("/api/tenant/transfers")
@PreAuthorize(Roles.TENANT_ADMIN)
@RequiresModule(TenantModule.MULTI_BRANCH)
@RequiredArgsConstructor
public class TransfersController {

    private final TransfersService transfersService;

    @Operation(summary = "Registrar una transferencia",
            description = "Descuenta los lotes de la sucursal de origen y crea lotes equivalentes en la de destino, "
                    + "conservando número, vencimiento, costo y antigüedad (FIFO).")
    @PostMapping
    public TransferDto transfer(@Valid @RequestBody TransferRequest request) {
        return transfersService.transfer(request);
    }

    @Operation(summary = "Historial de transferencias")
    @GetMapping
    public PageResponse<TransferSummaryDto> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long branchId,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "no puede ser negativa") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "tiene que ser al menos 1")
            @Max(value = 100, message = "no puede superar 100") int size) {
        return transfersService.list(from, to, branchId, page, size);
    }

    @Operation(summary = "Lotes disponibles para transferir",
            description = "Lotes vendibles de la sucursal de origen en el orden en que se venderían.")
    @GetMapping("/available-lots")
    public PageResponse<TransferableLotDto> availableLots(
            @RequestParam @NotNull(message = "elegí la sucursal de origen") Long branchId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "no puede ser negativa") int page,
            @RequestParam(defaultValue = "50") @Min(value = 1, message = "tiene que ser al menos 1")
            @Max(value = 100, message = "no puede superar 100") int size) {
        return transfersService.availableLots(branchId, q, page, size);
    }

    @Operation(summary = "Detalle de una transferencia")
    @GetMapping("/{batchRef}")
    public TransferDto detail(@PathVariable String batchRef) {
        return transfersService.detail(batchRef);
    }
}
