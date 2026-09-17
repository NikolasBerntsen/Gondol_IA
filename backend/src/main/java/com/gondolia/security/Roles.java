package com.gondolia.security;

/**
 * Expresiones para {@code @PreAuthorize}. Ejemplo: {@code @PreAuthorize(Roles.TENANT_ADMIN)}.
 */
public final class Roles {

    public static final String OWNER = "hasRole('PLATFORM_OWNER')";
    public static final String SUPPORT = "hasRole('SUPPORT_AGENT')";
    public static final String TENANT_ANY =
            "hasAnyRole('TENANT_BOSS','TENANT_ADMIN','TENANT_EMPLOYEE','TENANT_CASHIER')";
    /** Punto de venta GondolIA (SPEC §15): administrador, empleado y cajero. */
    public static final String TENANT_POS = "hasAnyRole('TENANT_ADMIN','TENANT_EMPLOYEE','TENANT_CASHIER')";
    public static final String TENANT_ADMIN = "hasRole('TENANT_ADMIN')";
    public static final String TENANT_DASHBOARD = "hasAnyRole('TENANT_BOSS','TENANT_ADMIN')";
    public static final String TENANT_INVENTORY = "hasAnyRole('TENANT_ADMIN','TENANT_EMPLOYEE')";

    private Roles() {
    }
}
