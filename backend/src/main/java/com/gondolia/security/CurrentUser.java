package com.gondolia.security;

import com.gondolia.common.error.ApiException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.common.error.ForbiddenException;
import com.gondolia.domain.user.Role;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Acceso estático al usuario autenticado del request HTTP actual.
 * El {@code tenantId} de toda operación de negocio sale de acá, nunca del request.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static Optional<AuthUser> optional() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthUser user) {
            return Optional.of(user);
        }
        return Optional.empty();
    }

    /** Usuario autenticado; 401 {@code UNAUTHORIZED} si no hay sesión. */
    public static AuthUser get() {
        return optional().orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHORIZED,
                "Necesitás iniciar sesión para continuar"));
    }

    public static Long id() {
        return get().id();
    }

    public static Role role() {
        return get().role();
    }

    /** Tenant del usuario; 403 {@code NO_TENANT} si es un usuario de plataforma. */
    public static Long tenantId() {
        AuthUser user = get();
        if (!user.isTenantUser() || user.tenantId() == null) {
            throw new ForbiddenException(ErrorCodes.NO_TENANT, "Esta operación solo está disponible para usuarios de un comercio");
        }
        return user.tenantId();
    }
}
