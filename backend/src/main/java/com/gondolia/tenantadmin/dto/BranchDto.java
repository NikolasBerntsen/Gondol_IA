package com.gondolia.tenantadmin.dto;

import java.time.Instant;

/**
 * Sucursal del comercio (SPEC §6.9). {@code employeeCount} son los empleados y cajeros activos asignados;
 * {@code hasStock} indica stock físico (lotes {@code ACTIVE} o {@code RECALLED} con remanente), que impide
 * desactivarla (409 {@code BRANCH_HAS_STOCK}).
 */
public record BranchDto(Long id, String name, String code, String address, String city, String province, String phone,
                        boolean active, long employeeCount, boolean hasStock, Instant createdAt) {
}
