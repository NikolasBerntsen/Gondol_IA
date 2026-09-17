package com.gondolia.platform.dto;

import jakarta.validation.constraints.NotNull;

/** Habilitar o deshabilitar un módulo de un comercio (SPEC §14.3). */
public record ModuleToggleRequest(@NotNull(message = "indicá si el módulo queda habilitado") Boolean enabled) {
}
