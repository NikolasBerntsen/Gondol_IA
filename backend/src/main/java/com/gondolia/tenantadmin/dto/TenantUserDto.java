package com.gondolia.tenantadmin.dto;

import com.gondolia.domain.user.Role;
import java.time.Instant;
import java.util.List;

/**
 * Usuario del comercio en la pantalla de administración (SPEC §6.9). {@code branches} son las sucursales asignadas
 * (solo para empleados y cajeros; jefe y administrador acceden a todas).
 */
public record TenantUserDto(Long id, String fullName, String email, Role role, boolean active,
                            boolean mustChangePassword, Instant lastLoginAt, Instant createdAt,
                            List<AssignedBranchDto> branches) {

    /** Sucursal asignada. {@code active = false} si la sucursal fue desactivada después de asignarla. */
    public record AssignedBranchDto(Long id, String name, String code, boolean active) {
    }
}
