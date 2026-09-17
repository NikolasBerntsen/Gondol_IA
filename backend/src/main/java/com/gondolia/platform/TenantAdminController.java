package com.gondolia.platform;

import com.gondolia.common.PageResponse;
import com.gondolia.domain.tenant.BusinessType;
import com.gondolia.domain.tenant.TenantModule;
import com.gondolia.domain.tenant.TenantPlan;
import com.gondolia.domain.tenant.TenantStatus;
import com.gondolia.platform.dto.CreateTenantRequest;
import com.gondolia.platform.dto.TemporaryPasswordResponse;
import com.gondolia.platform.dto.TenantDetail;
import com.gondolia.platform.dto.TenantStatusRequest;
import com.gondolia.platform.dto.TenantSummary;
import com.gondolia.platform.dto.UpdateTenantRequest;
import com.gondolia.security.CurrentUser;
import com.gondolia.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * Comercios clientes vistos por los dueños de GondolIA (SPEC §6.6): alta, edición, bloqueo, baja, eliminación y
 * reseteo de la contraseña del administrador. <b>Solo datos administrativos</b> (SPEC §3.4.3).
 */
@Tag(name = "Consola de dueños · Clientes")
@RestController
@RequestMapping("/api/platform/tenants")
@PreAuthorize(Roles.OWNER)
@RequiredArgsConstructor
public class TenantAdminController {

    private final TenantAdminService tenantAdminService;

    @Operation(summary = "Listado de clientes",
            description = "Filtros por texto (nombre, razón social, contacto, ciudad o CUIT), estado, plan, rubro y "
                    + "módulo habilitado. Orden: name, createdAt, lastActivityAt, status, plan, city, "
                    + "activeBranchCount o userCount.")
    @GetMapping
    public PageResponse<TenantSummary> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) TenantStatus status,
            @RequestParam(required = false) TenantPlan plan,
            @RequestParam(required = false) BusinessType businessType,
            @RequestParam(required = false) TenantModule module,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "no puede ser negativa") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "tiene que ser al menos 1")
            @Max(value = 100, message = "no puede superar 100") int size) {
        return tenantAdminService.list(q, status, plan, businessType, module, sort, page, size);
    }

    @Operation(summary = "Detalle administrativo de un cliente",
            description = "Datos, sucursales (nombre, ciudad y estado), usuarios, módulos e historial de eventos.")
    @GetMapping("/{id}")
    public TenantDetail detail(@PathVariable Long id) {
        return tenantAdminService.detail(id);
    }

    @Operation(summary = "Alta de un cliente",
            description = "Crea el comercio, su configuración, la primera sucursal, el jefe, el administrador y el "
                    + "empleado (asignado a esa sucursal) y los módulos indicados o el preset del plan.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TenantDetail create(@RequestBody @Valid CreateTenantRequest request) {
        return tenantAdminService.create(request, CurrentUser.id());
    }

    @Operation(summary = "Editar los datos y el plan de un cliente",
            description = "Cambiar el plan registra PLAN_CHANGED. Bajar a un plan con menos sucursales que las "
                    + "activas responde 409 BRANCH_LIMIT_REACHED.")
    @PutMapping("/{id}")
    public TenantDetail update(@PathVariable Long id, @RequestBody @Valid UpdateTenantRequest request) {
        return tenantAdminService.update(id, request, CurrentUser.id());
    }

    @Operation(summary = "Deshabilitar el acceso de un cliente",
            description = "Sus usuarios quedan bloqueados (403 TENANT_DISABLED) y se cierran sus sesiones abiertas.")
    @PostMapping("/{id}/disable")
    public TenantSummary disable(@PathVariable Long id, @RequestBody @Valid TenantStatusRequest request) {
        return tenantAdminService.disable(id, request.reason().strip(), CurrentUser.id());
    }

    @Operation(summary = "Volver a habilitar un cliente")
    @PostMapping("/{id}/enable")
    public TenantSummary enable(@PathVariable Long id,
                                @RequestBody(required = false) TenantStatusRequest request) {
        return tenantAdminService.enable(id, TenantStatusRequest.optional(request == null ? null : request.reason()),
                CurrentUser.id());
    }

    @Operation(summary = "Dar de baja a un cliente",
            description = "Cuenta como churn. Sus usuarios quedan bloqueados (403 TENANT_CANCELLED).")
    @PostMapping("/{id}/cancel")
    public TenantSummary cancel(@PathVariable Long id, @RequestBody @Valid TenantStatusRequest request) {
        return tenantAdminService.cancel(id, request.reason().strip(), CurrentUser.id());
    }

    @Operation(summary = "Reactivar a un cliente dado de baja")
    @PostMapping("/{id}/reactivate")
    public TenantSummary reactivate(@PathVariable Long id,
                                    @RequestBody(required = false) TenantStatusRequest request) {
        return tenantAdminService.reactivate(id,
                TenantStatusRequest.optional(request == null ? null : request.reason()), CurrentUser.id());
    }

    @Operation(summary = "Eliminar definitivamente a un cliente",
            description = "Solo comercios dados de baja y escribiendo el nombre exacto en confirmName. Borra sus "
                    + "usuarios, sucursales y datos; queda el historial sin comercio asociado.")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, @RequestParam(required = false) String confirmName) {
        tenantAdminService.delete(id, confirmName, CurrentUser.id());
    }

    @Operation(summary = "Restablecer la contraseña del administrador del cliente",
            description = "Devuelve una contraseña temporal que se muestra una sola vez; el administrador tiene que "
                    + "cambiarla al entrar y se cierran sus sesiones abiertas.")
    @PostMapping("/{id}/reset-admin-password")
    public TemporaryPasswordResponse resetAdminPassword(@PathVariable Long id) {
        return tenantAdminService.resetAdminPassword(id, CurrentUser.id());
    }
}
