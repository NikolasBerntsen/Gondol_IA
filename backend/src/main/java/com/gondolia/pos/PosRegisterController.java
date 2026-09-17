package com.gondolia.pos;

import com.gondolia.domain.tenant.TenantModule;
import com.gondolia.modules.RequiresModule;
import com.gondolia.pos.dto.PosRegisterDto;
import com.gondolia.pos.dto.PosRegisterRequest;
import com.gondolia.security.CurrentUser;
import com.gondolia.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cajas del POS GondolIA (SPEC §15.2). Requiere el módulo {@code POS_GONDOLIA}: listarlas puede cualquier usuario
 * del POS dentro de su alcance de sucursales; crearlas y editarlas, solo el administrador.
 */
@Tag(name = "POS · Cajas")
@RestController
@RequestMapping("/api/tenant/pos/registers")
@RequiresModule(TenantModule.POS_GONDOLIA)
@PreAuthorize(Roles.TENANT_POS)
@RequiredArgsConstructor
public class PosRegisterController {

    private final PosRegisterService registerService;

    @Operation(summary = "Cajas del alcance de sucursales",
            description = "Cada caja informa su turno abierto si lo tiene. Con includeInactive=true también trae las desactivadas.")
    @GetMapping
    public List<PosRegisterDto> list(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return registerService.list(CurrentUser.tenantId(), includeInactive);
    }

    @Operation(summary = "Ver una caja")
    @GetMapping("/{id}")
    public PosRegisterDto get(@PathVariable Long id) {
        return registerService.get(CurrentUser.tenantId(), id);
    }

    @Operation(summary = "Crear una caja", description = "Solo el administrador. El nombre es único en la sucursal.")
    @PostMapping
    @PreAuthorize(Roles.TENANT_ADMIN)
    @ResponseStatus(HttpStatus.CREATED)
    public PosRegisterDto create(@Valid @RequestBody PosRegisterRequest request) {
        return registerService.create(CurrentUser.tenantId(), request);
    }

    @Operation(summary = "Editar una caja", description = "Solo el administrador. No se puede desactivar ni mover una caja con un turno abierto.")
    @PutMapping("/{id}")
    @PreAuthorize(Roles.TENANT_ADMIN)
    public PosRegisterDto update(@PathVariable Long id, @Valid @RequestBody PosRegisterRequest request) {
        return registerService.update(CurrentUser.tenantId(), id, request);
    }
}
