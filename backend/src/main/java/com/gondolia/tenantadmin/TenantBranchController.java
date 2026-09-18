package com.gondolia.tenantadmin;

import com.gondolia.security.Roles;
import com.gondolia.tenantadmin.dto.BranchDto;
import com.gondolia.tenantadmin.dto.BranchLimitsDto;
import com.gondolia.tenantadmin.dto.BranchRequest;
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
 * Sucursales del comercio (SPEC §6.9). El listado y el límite los puede ver cualquier usuario del comercio
 * (necesitan saber en qué sucursal trabajan); el alta, la edición y los cambios de estado son del administrador.
 */
@Tag(name = "Administración del comercio · Sucursales")
@RestController
@RequestMapping("/api/tenant/branches")
@RequiredArgsConstructor
public class TenantBranchController {

    private final TenantBranchService branchService;

    @Operation(summary = "Listar sucursales",
            description = "Devuelve las sucursales accesibles. Solo el administrador puede incluir las desactivadas.")
    @GetMapping
    @PreAuthorize(Roles.TENANT_ANY)
    public List<BranchDto> list(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return branchService.list(includeInactive);
    }

    @Operation(summary = "Límite de sucursales del plan",
            description = "Máximo efectivo: el del plan si Multi-sucursal está habilitado, si no 1.")
    @GetMapping("/limits")
    @PreAuthorize(Roles.TENANT_ANY)
    public BranchLimitsDto limits() {
        return branchService.limits();
    }

    @Operation(summary = "Crear una sucursal", description = "409 BRANCH_LIMIT_REACHED si se llegó al máximo.")
    @PostMapping
    @PreAuthorize(Roles.TENANT_ADMIN)
    @ResponseStatus(HttpStatus.CREATED)
    public BranchDto create(@Valid @RequestBody BranchRequest request) {
        return branchService.create(request);
    }

    @Operation(summary = "Editar una sucursal")
    @PutMapping("/{id}")
    @PreAuthorize(Roles.TENANT_ADMIN)
    public BranchDto update(@PathVariable Long id, @Valid @RequestBody BranchRequest request) {
        return branchService.update(id, request);
    }

    @Operation(summary = "Desactivar una sucursal",
            description = "409 BRANCH_HAS_STOCK si tiene stock; 409 LAST_ACTIVE_BRANCH si es la única activa.")
    @PostMapping("/{id}/deactivate")
    @PreAuthorize(Roles.TENANT_ADMIN)
    public BranchDto deactivate(@PathVariable Long id) {
        return branchService.deactivate(id);
    }

    @Operation(summary = "Reactivar una sucursal", description = "Respeta el máximo efectivo de sucursales activas.")
    @PostMapping("/{id}/activate")
    @PreAuthorize(Roles.TENANT_ADMIN)
    public BranchDto activate(@PathVariable Long id) {
        return branchService.activate(id);
    }
}
