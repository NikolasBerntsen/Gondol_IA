package com.gondolia.tenantadmin;

/**
 * Códigos de error propios de la administración del comercio (SPEC §6.9). Los códigos compartidos
 * ({@code BRANCH_LIMIT_REACHED}, {@code BRANCH_HAS_STOCK}, {@code VALIDATION_ERROR}…) están en
 * {@link com.gondolia.common.error.ErrorCodes}.
 */
public final class TenantAdminErrors {

    /** 409: ya existe un usuario con ese email. */
    public static final String DUPLICATE_EMAIL = "DUPLICATE_EMAIL";

    /** 409: ya existe una sucursal con ese nombre en el comercio. */
    public static final String DUPLICATE_BRANCH_NAME = "DUPLICATE_BRANCH_NAME";

    /** 409: el administrador intentó cambiar su propio rol, desactivarse o resetear su propia contraseña. */
    public static final String SELF_UPDATE_FORBIDDEN = "SELF_UPDATE_FORBIDDEN";

    /** 409: el comercio quedaría sin ningún administrador activo. */
    public static final String LAST_ACTIVE_ADMIN = "LAST_ACTIVE_ADMIN";

    /** 409: es la única sucursal activa del comercio. */
    public static final String LAST_ACTIVE_BRANCH = "LAST_ACTIVE_BRANCH";

    private TenantAdminErrors() {
    }
}
