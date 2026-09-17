package com.gondolia.modules;

import com.gondolia.common.error.ErrorCodes;
import com.gondolia.common.error.ForbiddenException;
import com.gondolia.security.AuthUser;
import com.gondolia.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Aplica {@link RequiresModule} sobre {@code /api/**} (lo crea y registra {@link ModuleWebConfig}): si el comercio del
 * usuario autenticado no tiene habilitado el módulo, responde 403 {@code MODULE_DISABLED} con el formato de error
 * estándar (la excepción la traduce {@code GlobalExceptionHandler}).
 * <p>
 * La anotación del método gana sobre la de la clase. Para usuarios de plataforma ({@code PLATFORM_OWNER},
 * {@code SUPPORT_AGENT}) y para requests sin usuario autenticado —como el webhook del POS externo, que se autentica
 * con API key— el interceptor no bloquea: no hay comercio que resolver. Esos endpoints tienen que chequear el módulo
 * a mano con {@code ModuleService.isEnabled(tenantId, module)} (o {@code require(tenantId, module)}) una vez que
 * resolvieron el comercio.
 */
@Slf4j
@RequiredArgsConstructor
public class RequiresModuleInterceptor implements HandlerInterceptor {

    private final ModuleService moduleService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequiresModule required = findAnnotation(handlerMethod);
        if (required == null) {
            return true;
        }
        Optional<AuthUser> user = CurrentUser.optional().filter(AuthUser::isTenantUser);
        if (user.isEmpty()) {
            return true;
        }
        Long tenantId = user.get().tenantId();
        if (tenantId != null && !moduleService.isEnabled(tenantId, required.value())) {
            log.debug("{} {}: el comercio {} no tiene habilitado {}", request.getMethod(), request.getRequestURI(),
                    tenantId, required.value());
            throw new ForbiddenException(ErrorCodes.MODULE_DISABLED, ModuleCatalog.MSG_MODULE_DISABLED);
        }
        return true;
    }

    /** Anotación del método; si no tiene, la de la clase del controlador. */
    private static RequiresModule findAnnotation(HandlerMethod handlerMethod) {
        RequiresModule onMethod =
                AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), RequiresModule.class);
        return onMethod != null
                ? onMethod
                : AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), RequiresModule.class);
    }
}
