package com.gondolia.movements;

import com.gondolia.domain.tenant.TenantModule;
import com.gondolia.modules.RequiresModule;
import com.gondolia.movements.dto.PosIntegrationDtos.PosApiKeyDto;
import com.gondolia.movements.dto.PosIntegrationDtos.PosIntegrationDto;
import com.gondolia.movements.dto.PosIntegrationDtos.SimulateRequest;
import com.gondolia.movements.dto.PosIntegrationDtos.SimulateResultDto;
import com.gondolia.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Integración con el POS propio del cliente: API key por sucursal y simulador (SPEC §6.4).
 * Rol administrador y módulo {@code POS_INTEGRATION}.
 */
@Tag(name = "Integración POS")
@RestController
@RequestMapping("/api/tenant/integrations/pos")
@PreAuthorize(Roles.TENANT_ADMIN)
@RequiresModule(TenantModule.POS_INTEGRATION)
@RequiredArgsConstructor
public class PosIntegrationController {

    private final PosIntegrationService posIntegrationService;

    @Operation(summary = "Estado de la integración por sucursal",
            description = "Indica si la sucursal ya tiene API key, su prefijo y la actividad de las últimas 24 h.")
    @GetMapping
    public List<PosIntegrationDto> status() {
        return posIntegrationService.status();
    }

    @Operation(summary = "Generar la API key de una sucursal",
            description = "Devuelve la clave en claro una sola vez: después solo queda el prefijo. "
                    + "Volver a generarla invalida la anterior.")
    @PostMapping("/{branchId}/key")
    public PosApiKeyDto generateKey(@PathVariable Long branchId) {
        return posIntegrationService.generateKey(branchId);
    }

    @Operation(summary = "Simular ventas del POS externo",
            description = "Genera ventas aleatorias por el mismo camino que el webhook, para probar la integración.")
    @PostMapping("/{branchId}/simulate")
    public SimulateResultDto simulate(@PathVariable Long branchId, @Valid @RequestBody SimulateRequest request) {
        return posIntegrationService.simulate(branchId, request.sales());
    }
}
