package com.gondolia.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank(message = "es obligatorio")
        @Size(max = 150, message = "no puede superar los 150 caracteres")
        String email,

        @NotBlank(message = "es obligatoria")
        @Size(max = 72, message = "no puede superar los 72 caracteres")
        String password) {

    @Override
    public String toString() {
        return "LoginRequest[email=" + email + "]";
    }
}
