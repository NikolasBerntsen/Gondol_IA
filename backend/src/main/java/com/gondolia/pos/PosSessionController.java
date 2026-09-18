package com.gondolia.pos;

import com.gondolia.common.PageResponse;
import com.gondolia.domain.pos.PosSessionStatus;
import com.gondolia.domain.tenant.TenantModule;
import com.gondolia.modules.RequiresModule;
import com.gondolia.pos.dto.CashMovementRequest;
import com.gondolia.pos.dto.CloseSessionRequest;
import com.gondolia.pos.dto.OpenSessionRequest;
import com.gondolia.pos.dto.PosSessionReportDto;
import com.gondolia.pos.dto.PosSessionSummaryDto;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Turnos de caja del POS GondolIA (SPEC §15.2): abrir, mover efectivo, cerrar con arqueo y ver el reporte Z.
 * El administrador ve y opera todos los turnos de su alcance; el empleado y el cajero, solo los suyos.
 */
@Tag(name = "POS · Turnos de caja")
@RestController
@RequestMapping("/api/tenant/pos/sessions")
@RequiresModule(TenantModule.POS_GONDOLIA)
@PreAuthorize(Roles.TENANT_POS)
@RequiredArgsConstructor
public class PosSessionController {

    private final PosSessionService sessionService;

    @Operation(summary = "Mi turno abierto", description = "Devuelve el turno OPEN del usuario o null si no tiene ninguno.")
    @GetMapping("/current")
    public PosSessionReportDto current() {
        return sessionService.current(CurrentUser.get());
    }

    @Operation(summary = "Abrir turno",
            description = "409 REGISTER_BUSY si la caja ya tiene un turno abierto; 409 SESSION_ALREADY_OPEN si el usuario ya tiene uno.")
    @PostMapping("/open")
    public PosSessionReportDto open(@Valid @RequestBody OpenSessionRequest request) {
        return sessionService.open(CurrentUser.get(), request);
    }

    @Operation(summary = "Ingreso o retiro de efectivo", description = "Queda registrado en el arqueo del turno.")
    @PostMapping("/{id}/cash-movements")
    public PosSessionReportDto cashMovement(@PathVariable Long id, @Valid @RequestBody CashMovementRequest request) {
        return sessionService.addCashMovement(CurrentUser.get(), id, request);
    }

    @Operation(summary = "Cerrar turno con arqueo", description = "Devuelve el reporte Z con el efectivo esperado, el contado y la diferencia.")
    @PostMapping("/{id}/close")
    public PosSessionReportDto close(@PathVariable Long id, @Valid @RequestBody CloseSessionRequest request) {
        return sessionService.close(CurrentUser.get(), id, request);
    }

    @Operation(summary = "Listado de turnos",
            description = "El administrador ve todos los del alcance (o solo los suyos con mine=true); el empleado y el cajero, siempre los propios.")
    @GetMapping
    public PageResponse<PosSessionSummaryDto> list(
            @RequestParam(required = false) PosSessionStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "false") boolean mine,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "no puede ser negativa") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "tiene que ser al menos 1")
            @Max(value = 100, message = "no puede superar 100") int size) {
        return sessionService.list(CurrentUser.get(), status, from, to, mine, page, size);
    }

    @Operation(summary = "Reporte Z de un turno")
    @GetMapping("/{id}")
    public PosSessionReportDto get(@PathVariable Long id) {
        return sessionService.get(CurrentUser.get(), id);
    }
}
