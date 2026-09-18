package com.gondolia.tenantadmin.dto;

import com.gondolia.domain.user.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Edición de un usuario del comercio. El email no se cambia (es la identidad de la cuenta) y la contraseña se
 * cambia con el reseteo.
 */
public record UpdateUserRequest(
        @NotBlank(message = "es obligatorio")
        @Size(max = 150, message = "no puede superar los 150 caracteres")
        String fullName,

        @NotNull(message = "es obligatorio")
        Role role,

        @NotNull(message = "es obligatorio")
        Boolean active,

        List<Long> branchIds) {

    public List<Long> branchIds() {
        return branchIds == null ? List.of() : branchIds;
    }
}
