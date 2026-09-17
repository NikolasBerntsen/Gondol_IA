package com.gondolia.security;

/**
 * Sucursal (y su tenant) dueña de una API key de POS válida.
 */
public record PosBranch(Long tenantId, Long branchId) {
}
