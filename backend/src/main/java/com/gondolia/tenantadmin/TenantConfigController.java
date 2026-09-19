package com.gondolia.tenantadmin;

import com.gondolia.security.Roles;
import com.gondolia.tenantadmin.dto.TenantAccountDto;
import com.gondolia.tenantadmin.dto.TenantSettingsDto;
import com.gondolia.tenantadmin.dto.TenantSettingsRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Configuración y datos del comercio (SPEC §6.9). La configuración es del administrador; los datos administrativos
 * los puede mirar también el jefe.
 */
@Tag(name = "Administración del comercio · Configuración")
@RestController
@RequestMapping("/api/tenant")
@RequiredArgsConstructor
public class TenantConfigController {

    private final TenantConfigService configService;

    @Operation(summary = "Ver la configuración del comercio",
            description = "Incluye la rotación de stock (FIFO/FEFO), los umbrales de vencimiento y los parámetros de "
                    + "reposición que usa la IA.")
    @GetMapping("/settings")
    @PreAuthorize(Roles.TENANT_ADMIN)
    public TenantSettingsDto settings() {
        return configService.settings();
    }

    @Operation(summary = "Guardar la configuración del comercio",
            description = "Los días críticos tienen que ser menos que los días de aviso.")
    @PutMapping("/settings")
    @PreAuthorize(Roles.TENANT_ADMIN)
    public TenantSettingsDto updateSettings(@Valid @RequestBody TenantSettingsRequest request) {
        return configService.updateSettings(request);
    }

    @Operation(summary = "Datos del comercio",
            description = "Solo lectura: razón social, plan, estado, contacto, módulos y abono estimado.")
    @GetMapping("/account")
    @PreAuthorize(Roles.TENANT_DASHBOARD)
    public TenantAccountDto account() {
        return configService.account();
    }
}
