package com.gondolia.movements;

import com.gondolia.movements.dto.PosIntegrationDtos.PosWebhookRequest;
import com.gondolia.movements.dto.PosIntegrationDtos.PosWebhookResponse;
import com.gondolia.security.ApiKeyService;
import com.gondolia.security.PosBranch;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Webhook público del POS propio del cliente (SPEC §6.4): {@code POST /api/integrations/pos/sales} con el
 * encabezado {@code X-API-Key} de la sucursal, sin JWT.
 * <p>
 * La key y el módulo {@code POS_INTEGRATION} los verifica {@link PosApiKeyInterceptor} antes de leer el cuerpo, así
 * un llamador sin key válida recibe 401 {@code INVALID_API_KEY} y nunca los errores de validación del payload.
 */
@Tag(name = "Integración POS (webhook)")
@RestController
@RequestMapping("/api/integrations/pos")
@RequiredArgsConstructor
public class PosWebhookController {

    private final PosIntegrationService posIntegrationService;

    @Operation(summary = "Registrar una venta del POS del cliente",
            description = "Autenticado con la API key de la sucursal (X-API-Key). Informa los códigos "
                    + "desconocidos y los faltantes de stock. 401 INVALID_API_KEY si la key falta, no existe o su "
                    + "sucursal está desactivada; 403 TENANT_DISABLED / TENANT_CANCELLED si el comercio está "
                    + "bloqueado; 403 MODULE_DISABLED sin POS_INTEGRATION.")
    @SecurityRequirements
    @Parameter(name = ApiKeyService.HEADER, in = ParameterIn.HEADER, required = true,
            description = "API key de la sucursal (gk_…)")
    @PostMapping("/sales")
    public PosWebhookResponse receive(
            @Parameter(hidden = true) @RequestAttribute(PosApiKeyInterceptor.BRANCH_ATTRIBUTE) PosBranch branch,
            @Valid @RequestBody PosWebhookRequest request) {
        return posIntegrationService.receive(branch.tenantId(), branch.branchId(), request);
    }
}
