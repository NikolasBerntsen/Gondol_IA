package com.gondolia.platform;

import com.gondolia.common.PageResponse;
import com.gondolia.domain.tenant.TenantModule;
import com.gondolia.domain.tenant.TenantPlan;
import com.gondolia.domain.tenant.TenantStatus;
import com.gondolia.modules.TenantModuleStatus;
import com.gondolia.platform.dto.ModuleCatalogItem;
import com.gondolia.platform.dto.ModuleToggleRequest;
import com.gondolia.platform.dto.TenantModulesRow;
import com.gondolia.security.CurrentUser;
import com.gondolia.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Módulos por cliente (SPEC §14.3): catálogo con adopción, matriz clientes × módulos y activación por comercio.
 */
@Tag(name = "Consola de dueños · Módulos")
@RestController
@RequestMapping("/api/platform")
@PreAuthorize(Roles.OWNER)
@RequiredArgsConstructor
public class PlatformModulesController {

    private final PlatformModulesService modulesService;

    @Operation(summary = "Catálogo de módulos con su adopción",
            description = "Cuántos comercios ACTIVE tienen habilitado cada módulo y qué porcentaje representan.")
    @GetMapping("/modules")
    public List<ModuleCatalogItem> catalog() {
        return modulesService.catalog();
    }

    @Operation(summary = "Matriz clientes × módulos",
            description = "Una fila por comercio con los tres módulos, sus sucursales activas y la cuota estimada.")
    @GetMapping("/tenant-modules")
    public PageResponse<TenantModulesRow> matrix(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) TenantStatus status,
            @RequestParam(required = false) TenantPlan plan,
            @RequestParam(required = false) TenantModule module,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "no puede ser negativa") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "tiene que ser al menos 1")
            @Max(value = 100, message = "no puede superar 100") int size) {
        return modulesService.matrix(q, status, plan, module, sort, page, size);
    }

    @Operation(summary = "Módulos de un cliente")
    @GetMapping("/tenants/{id}/modules")
    public List<TenantModuleStatus> statuses(@PathVariable Long id) {
        return modulesService.statuses(id);
    }

    @Operation(summary = "Habilitar o deshabilitar un módulo de un cliente",
            description = "Sus usuarios reciben MODULES_CHANGED al instante. Deshabilitar MULTI_BRANCH con más de "
                    + "una sucursal activa responde 409 MODULE_IN_USE.")
    @PutMapping("/tenants/{id}/modules/{module}")
    public TenantModuleStatus setEnabled(@PathVariable Long id, @PathVariable TenantModule module,
                                         @RequestBody @Valid ModuleToggleRequest request) {
        return modulesService.setEnabled(id, module, Boolean.TRUE.equals(request.enabled()), CurrentUser.id());
    }
}
