package com.gondolia.platform.dto;

/**
 * Contraseña temporal generada para el administrador de un comercio (SPEC §6.6). Se muestra <b>una sola vez</b>:
 * no se guarda en claro y el usuario tiene que cambiarla al entrar.
 */
public record TemporaryPasswordResponse(String email, String fullName, String temporaryPassword) {
}
