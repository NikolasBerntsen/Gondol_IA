package com.gondolia.tenantadmin;

import com.gondolia.security.Roles;
import com.gondolia.tenantadmin.dto.CreateUserRequest;
import com.gondolia.tenantadmin.dto.ResetPasswordRequest;
import com.gondolia.tenantadmin.dto.ResetPasswordResponse;
import com.gondolia.tenantadmin.dto.TenantUserDto;
import com.gondolia.tenantadmin.dto.UpdateUserRequest;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Usuarios del comercio (SPEC §6.9). Solo el administrador: el {@code tenantId} sale del usuario autenticado.
 */
@Tag(name = "Administración del comercio · Usuarios")
@RestController
@RequestMapping("/api/tenant/users")
@PreAuthorize(Roles.TENANT_ADMIN)
@RequiredArgsConstructor
public class TenantUserController {

    private final TenantUserService userService;

    @Operation(summary = "Listar usuarios del comercio",
            description = "Incluye los desactivados y las sucursales asignadas a empleados y cajeros.")
    @GetMapping
    public List<TenantUserDto> list() {
        return userService.list();
    }

    @Operation(summary = "Crear un usuario",
            description = "Los empleados y cajeros necesitan al menos una sucursal activa asignada.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TenantUserDto create(@Valid @RequestBody CreateUserRequest request) {
        return userService.create(request);
    }

    @Operation(summary = "Editar un usuario",
            description = "No podés cambiar tu propio rol ni desactivarte, y siempre queda un administrador activo.")
    @PutMapping("/{id}")
    public TenantUserDto update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest request) {
        return userService.update(id, request);
    }

    @Operation(summary = "Resetear la contraseña de un usuario",
            description = "Sin contraseña en el cuerpo genera una temporal. Cierra las sesiones abiertas del usuario.")
    @PostMapping("/{id}/reset-password")
    public ResetPasswordResponse resetPassword(@PathVariable Long id,
                                               @Valid @RequestBody(required = false) ResetPasswordRequest request) {
        return userService.resetPassword(id, request);
    }
}
