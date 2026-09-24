package com.gondolia.domain.tenant;

public enum TenantEventType {
    CREATED,
    PLAN_CHANGED,
    DISABLED,
    ENABLED,
    CANCELLED,
    REACTIVATED,
    DELETED,
    /** Módulo habilitado; {@code from_value} = nombre del {@link TenantModule} (SPEC §14.1). */
    MODULE_ENABLED,
    /** Módulo deshabilitado; {@code from_value} = nombre del {@link TenantModule} (SPEC §14.1). */
    MODULE_DISABLED,
    /** Edición de los datos administrativos; {@code reason} = qué datos cambiaron (no sus valores). */
    DATA_UPDATED,
    /** Contraseña temporal para el administrador del comercio; {@code reason} = a qué cuenta. */
    ADMIN_PASSWORD_RESET
}
