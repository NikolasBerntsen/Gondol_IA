package com.gondolia.security;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.function.Supplier;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.authorization.method.PreAuthorizeAuthorizationManager;
import org.springframework.security.config.core.GrantedAuthorityDefaults;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.util.SimpleMethodInvocation;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Evalúa el {@code @PreAuthorize} del controlador (del método o, si no tiene, de la clase) <b>antes</b> de que Spring
 * MVC convierta y valide los argumentos.
 * <p>
 * La seguridad por método de {@code @EnableMethodSecurity} corre recién al invocar el método del controlador, cuando
 * el cuerpo ya se leyó y se aplicó {@code @Valid}: un rol sin permiso con un cuerpo inválido recibía 400
 * {@code VALIDATION_ERROR} con el detalle de los campos, en lugar del 403 {@code FORBIDDEN} que promete la matriz de
 * permisos (SPEC §3.3). Un {@link HandlerInterceptor} corre después de elegir el controlador y antes de resolver sus
 * argumentos, así que el rol se decide primero. La seguridad por método queda como segunda barrera (y es la única que
 * sirve fuera de los controladores HTTP).
 * <p>
 * Usa el mismo {@link PreAuthorizeAuthorizationManager} que la seguridad por método, con los argumentos en
 * {@code null}: por eso solo aplica a expresiones que no miran los parámetros (todas las de {@link Roles}); las que
 * usan {@code #param} quedan a cargo de la seguridad por método, que conoce los valores. Una denegación lanza
 * {@link AuthorizationDeniedException}, que {@code GlobalExceptionHandler} traduce a 403 {@code FORBIDDEN}.
 */
public class PreAuthorizeBeforeBindingInterceptor implements HandlerInterceptor {

    private final PreAuthorizeAuthorizationManager authorizationManager;

    public PreAuthorizeBeforeBindingInterceptor(PreAuthorizeAuthorizationManager authorizationManager) {
        this.authorizationManager = authorizationManager;
    }

    /**
     * Interceptor con el mismo manejador de expresiones que arma {@code @EnableMethodSecurity}: el bean
     * {@link MethodSecurityExpressionHandler} si existe; si no, el de Spring con la jerarquía de roles y el prefijo de
     * roles de la aplicación, si los hay.
     */
    public static PreAuthorizeBeforeBindingInterceptor create(ApplicationContext context) {
        MethodSecurityExpressionHandler expressionHandler =
                context.getBeanProvider(MethodSecurityExpressionHandler.class).getIfAvailable(() -> {
                    DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
                    handler.setApplicationContext(context);
                    context.getBeanProvider(RoleHierarchy.class).ifAvailable(handler::setRoleHierarchy);
                    context.getBeanProvider(GrantedAuthorityDefaults.class)
                            .ifAvailable(defaults -> handler.setDefaultRolePrefix(defaults.getRolePrefix()));
                    return handler;
                });
        PreAuthorizeAuthorizationManager manager = new PreAuthorizeAuthorizationManager();
        manager.setExpressionHandler(expressionHandler);
        manager.setApplicationContext(context);
        return new PreAuthorizeBeforeBindingInterceptor(manager);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // En el re-despacho ASYNC el método ya se invocó (y se autorizó): solo se escribe su resultado.
        if (request.getDispatcherType() == DispatcherType.ASYNC
                || !(handler instanceof HandlerMethod handlerMethod) || !appliesTo(handlerMethod)) {
            return true;
        }
        // Como en la invocación real, "this" es el controlador y no el proxy de la seguridad por método.
        Object controller = handlerMethod.getBean();
        Object target = AopProxyUtils.getSingletonTarget(controller);
        MethodInvocation invocation = new SimpleMethodInvocation(target != null ? target : controller,
                handlerMethod.getMethod(), new Object[handlerMethod.getMethod().getParameterCount()]);
        AuthorizationResult result = authorizationManager.authorize(authentication(), invocation);
        if (result != null && !result.isGranted()) {
            throw new AuthorizationDeniedException("Access Denied", result);
        }
        return true;
    }

    /** Tiene {@code @PreAuthorize} (método o clase) y la expresión no depende de los argumentos. */
    static boolean appliesTo(HandlerMethod handlerMethod) {
        PreAuthorize annotation =
                AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), PreAuthorize.class);
        if (annotation == null) {
            annotation = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), PreAuthorize.class);
        }
        return annotation != null && !annotation.value().contains("#");
    }

    /** Igual que la seguridad por método: sin {@code Authentication} en el contexto no hay nada que evaluar. */
    private static Supplier<Authentication> authentication() {
        return () -> {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null) {
                throw new AuthenticationCredentialsNotFoundException(
                        "An Authentication object was not found in the SecurityContext");
            }
            return authentication;
        };
    }
}
