package com.gondolia.tenantadmin.dto;

import com.gondolia.domain.user.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Alta de un usuario del comercio. {@code branchIds} es obligatorio para {@code TENANT_EMPLOYEE} y
 * {@code TENANT_CASHIER} (SPEC §3.5) y se ignora para jefe y administrador.
 */
public record CreateUserRequest(
        @NotBlank(message = "es obligatorio")
        @Size(max = 150, message = "no puede superar los 150 caracteres")
        String fullName,

        @NotBlank(message = "es obligatorio")
        @Email(message = "no tiene un formato válido")
        @Size(max = 150, message = "no puede superar los 150 caracteres")
        String email,

        @NotBlank(message = "es obligatoria")
        @Size(min = 8, max = 72, message = "tiene que tener entre 8 y 72 caracteres")
        String password,

        @NotNull(message = "es obligatorio")
        Role role,

        List<Long> branchIds) {

    public List<Long> branchIds() {
        return branchIds == null ? List.of() : branchIds;
    }
}
