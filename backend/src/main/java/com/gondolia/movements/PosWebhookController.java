package com.gondolia.movements;

import com.gondolia.common.error.ApiException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.domain.tenant.TenantModule;
import com.gondolia.modules.ModuleService;
import com.gondolia.movements.dto.PosIntegrationDtos.PosWebhookRequest;
import com.gondolia.movements.dto.PosIntegrationDtos.PosWebhookResponse;
import com.gondolia.security.ApiKeyService;
import com.gondolia.security.PosBranch;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Webhook público del POS propio del cliente (SPEC §6.4): {@code POST /api/integrations/pos/sales} con el
 * encabezado {@code X-API-Key} de la sucursal, sin JWT.
 * <p>
 * Como no hay usuario autenticado, el interceptor de {@code @RequiresModule} no puede resolver el comercio:
 * acá se chequea a mano con {@link ModuleService#require(Long, TenantModule)} después de resolver la sucursal.
 */
@Tag(name = "Integración POS (webhook)")
@RestController
@RequestMapping("/api/integrations/pos")
@RequiredArgsConstructor
public class PosWebhookController {

    private final ApiKeyService apiKeyService;
    private final ModuleService moduleService;
    private final PosIntegrationService posIntegrationService;

    @Operation(summary = "Registrar una venta del POS del cliente",
            description = "Autenticado con la API key de la sucursal (X-API-Key). Informa los códigos "
                    + "desconocidos y los faltantes de stock.")
    @PostMapping("/sales")
    public PosWebhookResponse receive(
            @RequestHeader(value = ApiKeyService.HEADER, required = false) String apiKey,
            @Valid @RequestBody PosWebhookRequest request) {
        PosBranch branch = apiKeyService.resolveBranch(apiKey)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.INVALID_API_KEY,
                        "La API key no es válida o la sucursal no está habilitada"));
        moduleService.require(branch.tenantId(), TenantModule.POS_INTEGRATION);
        return posIntegrationService.receive(branch.tenantId(), branch.branchId(), request);
    }
}
