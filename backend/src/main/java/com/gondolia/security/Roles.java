package com.gondolia.security;

/**
 * Expresiones para {@code @PreAuthorize}. Ejemplo: {@code @PreAuthorize(Roles.TENANT_ADMIN)}.
 * <p>
 * Regla (SPEC §3.3): todo botón o enlace visible para un rol funciona para ese rol; lo que un rol no puede hacer no se
 * le muestra. Por eso el jefe (vista resumida) igual puede <b>ver</b> todo lo que se abre desde su menú y <b>decidir</b>
 * sobre las alertas y las recomendaciones de la IA, pero no escribe inventario, ventas ni transferencias.
 */
public final class Roles {

    public static final String OWNER = "hasRole('PLATFORM_OWNER')";
    public static final String SUPPORT = "hasRole('SUPPORT_AGENT')";
    /**
     * Equipo de GondolIA: dueños y soporte. Soporte ve y edita los datos de un cliente y sus módulos para resolver
     * tickets; lo comercial o destructivo (alta, bloqueo, baja, eliminación, cambio de plan) y las métricas siguen
     * siendo del dueño ({@link #OWNER}).
     */
    public static final String PLATFORM_ANY = "hasAnyRole('PLATFORM_OWNER','SUPPORT_AGENT')";
    public static final String TENANT_ANY =
            "hasAnyRole('TENANT_BOSS','TENANT_ADMIN','TENANT_EMPLOYEE','TENANT_CASHIER')";
    /** Punto de venta GondolIA (SPEC §15): administrador, empleado y cajero. */
    public static final String TENANT_POS = "hasAnyRole('TENANT_ADMIN','TENANT_EMPLOYEE','TENANT_CASHIER')";
    public static final String TENANT_ADMIN = "hasRole('TENANT_ADMIN')";
    /**
     * Jefe y administrador: dashboards, estadísticas, IA y alertas; <b>decisiones</b> (aceptar/descartar
     * recomendaciones, gestionar alertas, recalcular la IA) e historiales de ventas, movimientos y transferencias.
     */
    public static final String TENANT_DASHBOARD = "hasAnyRole('TENANT_BOSS','TENANT_ADMIN')";
    /** Escritura de inventario: carga de mercadería, productos, vencimientos (descartar). */
    public static final String TENANT_INVENTORY = "hasAnyRole('TENANT_ADMIN','TENANT_EMPLOYEE')";
    /** Lectura de inventario (productos, lotes, proveedores, vencimientos): el jefe mira, no escribe. */
    public static final String TENANT_INVENTORY_READ =
            "hasAnyRole('TENANT_BOSS','TENANT_ADMIN','TENANT_EMPLOYEE')";

    private Roles() {
    }
}
