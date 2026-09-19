package com.gondolia.movements;

import com.gondolia.domain.tenant.TenantModule;
import com.gondolia.modules.ModuleService;
import com.gondolia.security.ApiKeyService;
import com.gondolia.security.PosBranch;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Autentica el webhook del POS propio del cliente ({@code /api/integrations/pos/**}, sin JWT) con el encabezado
 * {@code X-API-Key} y exige {@code POS_INTEGRATION}, <b>antes</b> de que Spring MVC lea y valide el cuerpo: sin key
 * válida la respuesta es siempre 401 {@code INVALID_API_KEY}, sea cual sea el payload (SPEC §6.4); si la key es de un
 * comercio bloqueado, 403 {@code TENANT_DISABLED} / {@code TENANT_CANCELLED} (SPEC §3.2); si el comercio no tiene el
 * módulo, 403 {@code MODULE_DISABLED}. Lo registra {@link PosWebhookWebConfig}.
 * <p>
 * La sucursal resuelta queda en el atributo {@link #BRANCH_ATTRIBUTE} del request, que el controlador recibe con
 * {@code @RequestAttribute}.
 */
@RequiredArgsConstructor
public class PosApiKeyInterceptor implements HandlerInterceptor {

    /** Atributo del request con la {@link PosBranch} autenticada. */
    public static final String BRANCH_ATTRIBUTE = "com.gondolia.movements.PosApiKeyInterceptor.branch";

    private final ApiKeyService apiKeyService;
    private final ModuleService moduleService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod)) {
            // Ruta sin controlador (recursos estáticos, 404): no hay operación que autenticar.
            return true;
        }
        PosBranch branch = apiKeyService.authenticate(request.getHeader(ApiKeyService.HEADER));
        moduleService.require(branch.tenantId(), TenantModule.POS_INTEGRATION);
        request.setAttribute(BRANCH_ATTRIBUTE, branch);
        return true;
    }
}
