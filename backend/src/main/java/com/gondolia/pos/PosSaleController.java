package com.gondolia.pos;

import com.gondolia.common.PageResponse;
import com.gondolia.domain.pos.PosSaleStatus;
import com.gondolia.domain.tenant.TenantModule;
import com.gondolia.modules.RequiresModule;
import com.gondolia.pos.dto.PosSaleDto;
import com.gondolia.pos.dto.PosSaleRequest;
import com.gondolia.pos.dto.PosSaleSummaryDto;
import com.gondolia.pos.dto.PosTicketDto;
import com.gondolia.pos.dto.VoidSaleRequest;
import com.gondolia.security.CurrentUser;
import com.gondolia.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cobro, historial, ticket y anulación del POS GondolIA (SPEC §15.2).
 */
@Tag(name = "POS · Ventas")
@RestController
@RequestMapping("/api/tenant/pos/sales")
@RequiresModule(TenantModule.POS_GONDOLIA)
@PreAuthorize(Roles.TENANT_POS)
@RequiredArgsConstructor
public class PosSaleController {

    private final PosSaleService saleService;

    @Operation(summary = "Cobrar una venta",
            description = "409 INSUFFICIENT_STOCK (con details por producto) salvo allowShortage; 400 PAYMENT_INSUFFICIENT si los pagos no cubren el total; 409 PRODUCT_RECALLED si el producto está en cuarentena.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PosSaleDto create(@Valid @RequestBody PosSaleRequest request) {
        return saleService.create(CurrentUser.get(), request);
    }

    @Operation(summary = "Historial de ventas del POS",
            description = "Alcance de sucursales. El empleado y el cajero solo ven las ventas de sus turnos.")
    @GetMapping
    public PageResponse<PosSaleSummaryDto> list(
            @RequestParam(required = false) Long sessionId,
            @RequestParam(required = false) PosSaleStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean mine,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "no puede ser negativa") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "tiene que ser al menos 1")
            @Max(value = 100, message = "no puede superar 100") int size) {
        return saleService.list(CurrentUser.get(), sessionId, status, from, to, q, mine, page, size);
    }

    @Operation(summary = "Ver una venta")
    @GetMapping("/{id}")
    public PosSaleDto get(@PathVariable Long id) {
        return saleService.get(CurrentUser.get(), id);
    }

    @Operation(summary = "Datos del ticket de 80 mm", description = "Comprobante no fiscal, listo para imprimir.")
    @GetMapping("/{id}/ticket")
    public PosTicketDto ticket(@PathVariable Long id) {
        return saleService.ticket(CurrentUser.get(), id);
    }

    @Operation(summary = "Anular una venta",
            description = "El administrador puede anular cualquier venta de su alcance; el empleado y el cajero, solo las de su turno abierto. 409 ALREADY_VOIDED si ya estaba anulada.")
    @PostMapping("/{id}/void")
    public PosSaleDto voidSale(@PathVariable Long id, @Valid @RequestBody VoidSaleRequest request) {
        return saleService.voidSale(CurrentUser.get(), id, request);
    }
}
