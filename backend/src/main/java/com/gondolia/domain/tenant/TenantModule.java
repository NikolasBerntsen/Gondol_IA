package com.gondolia.domain.tenant;

/**
 * Módulos que los dueños de GondolIA habilitan por comercio (SPEC §14). Sin fila en {@code tenant_modules} o con
 * {@code enabled = false} el módulo está deshabilitado. El catálogo (nombre, descripción y adicional mensual) está en
 * {@code com.gondolia.modules.ModuleCatalog}.
 */
public enum TenantModule {
    /** Punto de venta GondolIA: cajas, turnos, cobro, tickets y anulaciones (SPEC §15). */
    POS_GONDOLIA,
    /** Integración con el POS propio del cliente: API key por sucursal, webhook, CSV y simulador (SPEC §6.4). */
    POS_INTEGRATION,
    /** Multi-sucursal: más de una sucursal, transferencias y vista consolidada. */
    MULTI_BRANCH
}
