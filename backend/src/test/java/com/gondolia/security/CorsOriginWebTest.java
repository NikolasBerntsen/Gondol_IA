package com.gondolia.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gondolia.auth.AuthController;
import com.gondolia.auth.AuthService;
import com.gondolia.auth.dto.LoginRequest;
import com.gondolia.auth.dto.LoginResponse;
import com.gondolia.auth.dto.MeDto;
import com.gondolia.config.AppProperties;
import com.gondolia.config.ClockConfig;
import com.gondolia.config.CorsConfig;
import com.gondolia.domain.user.Role;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Política CORS del login (`app.cors.allowed-origins`).
 * <p>
 * Guarda el error que dejó el sitio publicado sin poder iniciar sesión: el navegador manda
 * {@code Origin: https://<host público>} y, si ese origen no está permitido, Spring Security corta la request con
 * {@code 403 "Invalid CORS request"} antes de llegar al controlador (la pantalla de login se ve, pero el botón
 * "Ingresar" siempre falla).
 * <p>
 * En producción hay dos defensas y esta prueba cubre la del backend:
 * <ul>
 *   <li>nginx borra el header {@code Origin} de las requests del propio frontend, que son del mismo origen y no son
 *       CORS ({@code $gondolia_upstream_origin} en {@code frontend/nginx/templates/gondolia.conf.template});</li>
 *   <li>el backend igual reconoce el origen público, que llega por {@code APP_CORS_ALLOWED_ORIGINS}
 *       ({@code docker-compose.prod.yml}).</li>
 * </ul>
 */
@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, SecurityErrorHandler.class, JwtService.class, ClockConfig.class, CorsConfig.class,
        CorsOriginWebTest.TestConfig.class})
@TestPropertySource(properties = "app.cors.allowed-origins=https://gondolia.ejemplo,http://localhost:5173")
class CorsOriginWebTest {

    private static final String PUBLIC_ORIGIN = "https://gondolia.ejemplo";
    private static final String CREDENTIALS = """
            {"email":"dueno@gondolia.app","password":"Gondolia2026!"}""";

    @TestConfiguration
    @EnableConfigurationProperties(AppProperties.class)
    static class TestConfig {
    }

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private UserAccessValidator userAccessValidator;
    @MockitoBean
    private AuthService authService;

    @BeforeEach
    void stubLogin() {
        MeDto me = new MeDto(1L, "dueno@gondolia.app", "Dueño", Role.PLATFORM_OWNER, false, null, List.of());
        when(authService.login(any(LoginRequest.class)))
                .thenReturn(new LoginResponse("jwt-de-prueba", Instant.parse("2026-09-26T00:00:00Z"), me));
    }

    @Test
    void loginFromThePublicOriginIsAllowed() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .header(HttpHeaders.ORIGIN, PUBLIC_ORIGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREDENTIALS))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, PUBLIC_ORIGIN))
                .andExpect(jsonPath("$.token").value("jwt-de-prueba"));
    }

    /** Sin el header Origin (lo que manda nginx para el mismo origen) no hay CORS que verificar. */
    @Test
    void loginWithoutOriginHeaderIsAllowed() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(CREDENTIALS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("jwt-de-prueba"));
    }

    /**
     * El origen que no está en la lista se corta con 403 y un cuerpo en texto plano ("Invalid CORS request"), no con
     * el JSON de error de la API: el frontend solo puede mostrar su mensaje genérico de 403.
     */
    @Test
    void loginFromAnUnknownOriginIsRejectedWithInvalidCorsRequest() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .header(HttpHeaders.ORIGIN, "https://gondolia.otro-host.ejemplo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREDENTIALS))
                .andExpect(status().isForbidden())
                .andExpect(content().string("Invalid CORS request"));
    }

    /** El mismo corte afecta a cualquier endpoint, no solo al login. */
    @Test
    void anyEndpointIsRejectedFromAnUnknownOrigin() throws Exception {
        mvc.perform(get("/api/auth/me").header(HttpHeaders.ORIGIN, "https://evil.ejemplo"))
                .andExpect(status().isForbidden())
                .andExpect(content().string("Invalid CORS request"));
    }
}
