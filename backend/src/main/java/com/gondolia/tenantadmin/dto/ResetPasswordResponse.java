package com.gondolia.tenantadmin.dto;

/**
 * Resultado del reseteo: la contraseña queda visible <b>una sola vez</b> para dictársela al usuario. El usuario
 * tiene que cambiarla al entrar y todas sus sesiones abiertas se cierran.
 */
public record ResetPasswordResponse(Long userId, String email, String fullName, String temporaryPassword,
                                    boolean generated) {
}
