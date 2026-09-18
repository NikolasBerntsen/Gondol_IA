package com.gondolia.platform.dto;

import java.time.Instant;

/**
 * Sucursal vista por los dueños de GondolIA: <b>solo datos administrativos</b> (SPEC §6.6). Nunca stock, ventas
 * ni la API key del POS.
 */
public record TenantBranchDto(Long id, String name, String code, String city, boolean active, Instant createdAt) {
}
