package com.gondolia.security;

import com.gondolia.common.error.ApiException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.domain.tenant.TenantRepository;
import com.gondolia.domain.tenant.TenantStatus;
import com.gondolia.domain.user.User;
import com.gondolia.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifica que un usuario pueda operar: existe, está activo, su token no fue revocado y su comercio está habilitado.
 * Lo usan el login, el filtro JWT y el interceptor de CONNECT de STOMP.
 */
@Component
@RequiredArgsConstructor
public class UserAccessValidator {

    public static final String MSG_SESSION_INVALID = "Tu sesión expiró o ya no es válida. Iniciá sesión nuevamente";
    public static final String MSG_USER_DISABLED = "Tu usuario está deshabilitado. Comunicate con un administrador";
    public static final String MSG_TENANT_DISABLED =
            "El acceso de tu comercio está deshabilitado. Comunicate con GondolIA.";
    public static final String MSG_TENANT_CANCELLED =
            "Tu comercio está dado de baja en GondolIA. Comunicate con nosotros para reactivar el servicio.";

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;

    /**
     * Valida un token ya verificado criptográficamente.
     *
     * <p>El estado del usuario se mira <b>antes</b> que la versión de token (SPEC §5.3): desactivar un usuario también
     * incrementa {@code token_version}, y si la versión se chequeara primero, un cliente que no estaba conectado por
     * STOMP (un celular que se despierta) recibiría el genérico "tu sesión expiró" en lugar de {@code USER_DISABLED}.
     * Solo el propio usuario puede tener un JWT firmado con su id, así que decirle que está deshabilitado no filtra
     * nada.
     *
     * @throws ApiException 401 {@code USER_DISABLED} si el usuario está desactivado, 401 {@code UNAUTHORIZED} si no
     *                      existe o el token fue revocado, 403 {@code TENANT_DISABLED} / {@code TENANT_CANCELLED}
     */
    @Transactional(readOnly = true)
    public AuthUser validate(Long userId, int tokenVersion) {
        User user = userRepository.findById(userId)
                .orElseThrow(UserAccessValidator::sessionInvalid);
        requireUserActive(user);
        if (user.getTokenVersion() != tokenVersion) {
            throw sessionInvalid();
        }
        return checkAccess(user);
    }

    /**
     * Verifica estado del usuario y de su comercio (sin mirar la versión de token).
     */
    @Transactional(readOnly = true)
    public AuthUser checkAccess(User user) {
        requireUserActive(user);
        if (user.getRole().isTenantRole()) {
            TenantStatus status = user.getTenantId() == null
                    ? null
                    : tenantRepository.findStatusById(user.getTenantId()).orElse(null);
            if (status == null) {
                throw sessionInvalid();
            }
            requireTenantActive(status);
        }
        return AuthUser.from(user);
    }

    /**
     * Bloqueo de un comercio que no está {@code ACTIVE} (SPEC §3.2). Lo comparten los usuarios (JWT, STOMP, login) y
     * el webhook del POS ({@link ApiKeyService#authenticate}).
     *
     * @throws ApiException 403 {@code TENANT_DISABLED} / {@code TENANT_CANCELLED}
     */
    static void requireTenantActive(TenantStatus status) {
        switch (status) {
            case DISABLED -> throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.TENANT_DISABLED,
                    MSG_TENANT_DISABLED);
            case CANCELLED -> throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.TENANT_CANCELLED,
                    MSG_TENANT_CANCELLED);
            case ACTIVE -> {
                // acceso normal
            }
        }
    }

    private static void requireUserActive(User user) {
        if (!user.isActive()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.USER_DISABLED, MSG_USER_DISABLED);
        }
    }

    private static ApiException sessionInvalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHORIZED, MSG_SESSION_INVALID);
    }
}
