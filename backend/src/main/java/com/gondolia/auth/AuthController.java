package com.gondolia.auth;

import com.gondolia.auth.dto.ChangePasswordRequest;
import com.gondolia.auth.dto.LoginRequest;
import com.gondolia.auth.dto.LoginResponse;
import com.gondolia.auth.dto.MeDto;
import com.gondolia.auth.dto.TokenResponse;
import com.gondolia.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Autenticación")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "Iniciar sesión", description = "Devuelve un JWT, su vencimiento y los datos del usuario.")
    @SecurityRequirements
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @Operation(summary = "Usuario autenticado", description = "Incluye el comercio y las sucursales accesibles.")
    @GetMapping("/me")
    public MeDto me() {
        return authService.me(CurrentUser.id());
    }

    @Operation(summary = "Cambiar contraseña",
            description = "Invalida todas las sesiones abiertas y devuelve un token nuevo para la sesión actual.")
    @PostMapping("/change-password")
    public TokenResponse changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        return authService.changePassword(CurrentUser.id(), request);
    }
}
