package com.gondolia.platform;

import com.gondolia.platform.dto.PlatformUserDto;
import com.gondolia.platform.dto.PlatformUserRequests;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Equipo de GondolIA: dueños y agentes de soporte (SPEC §6.6).
 */
@Tag(name = "Consola de dueños · Equipo")
@RestController
@RequestMapping("/api/platform/users")
@PreAuthorize(Roles.OWNER)
@RequiredArgsConstructor
public class PlatformTeamController {

    private final PlatformTeamService teamService;

    @Operation(summary = "Equipo de GondolIA", description = "Cuentas con rol de plataforma, por nombre.")
    @GetMapping
    public List<PlatformUserDto> list() {
        return teamService.list();
    }

    @Operation(summary = "Sumar a alguien al equipo")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PlatformUserDto create(@RequestBody @Valid PlatformUserRequests.Create request) {
        return teamService.create(request);
    }

    @Operation(summary = "Editar una cuenta del equipo",
            description = "Nombre y alta/baja de la cuenta. Nadie puede desactivarse a sí mismo.")
    @PutMapping("/{id}")
    public PlatformUserDto update(@PathVariable Long id, @RequestBody @Valid PlatformUserRequests.Update request) {
        return teamService.update(id, request, CurrentUser.id());
    }

    @Operation(summary = "Restablecer la contraseña de una cuenta del equipo",
            description = "La cuenta queda obligada a cambiarla al entrar y se cierran sus sesiones abiertas.")
    @PostMapping("/{id}/reset-password")
    public PlatformUserDto resetPassword(@PathVariable Long id,
                                         @RequestBody @Valid PlatformUserRequests.ResetPassword request) {
        return teamService.resetPassword(id, request);
    }
}
