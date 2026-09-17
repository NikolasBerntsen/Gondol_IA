package com.gondolia.security;

import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gondolia.auth.AuthController;
import com.gondolia.auth.AuthService;
import com.gondolia.auth.dto.LoginResponse;
import com.gondolia.auth.dto.MeDto;
import com.gondolia.common.error.ApiException;
import com.gondolia.config.AppProperties;
import com.gondolia.config.ClockConfig;
import com.gondolia.config.CorsConfig;
import com.gondolia.domain.user.Role;
import com.gondolia.domain.user.User;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reglas de seguridad HTTP sin base de datos: prefijos, respuestas 401/403 en formato JSON, validación del usuario del
 * token, seguridad por método y CORS.
 */
@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, SecurityErrorHandler.class, JwtService.class, ClockConfig.class, CorsConfig.class,
        SecurityWebTest.TestConfig.class, SecurityWebTest.ProbeController.class})
class SecurityWebTest {

    private static final AuthUser OWNER = new AuthUser(1L, "dueno@gondolia.app", "Dueño", Role.PLATFORM_OWNER, null);
    private static final AuthUser SUPPORT = new AuthUser(2L, "soporte@gondolia.app", "Soporte", Role.SUPPORT_AGENT, null);
    private static final AuthUser ADMIN = new AuthUser(10L, "admin@prueba.com", "Admin", Role.TENANT_ADMIN, 5L);
    private static final AuthUser EMPLOYEE =
            new AuthUser(11L, "empleado@prueba.com", "Empleado", Role.TENANT_EMPLOYEE, 5L);

    @TestConfiguration
    @EnableConfigurationProperties(AppProperties.class)
    static class TestConfig {
    }

    @RestController
    static class ProbeController {

        @GetMapping("/api/tenant/ping")
        Map<String, Object> tenantPing() {
            return Map.of("tenantId", CurrentUser.tenantId());
        }

        @PreAuthorize(Roles.TENANT_ADMIN)
        @GetMapping("/api/tenant/admin-only")
        Map<String, String> adminOnly() {
            return Map.of("ok", "true");
        }

        @GetMapping("/api/platform/ping")
        Map<String, String> platformPing() {
            return Map.of("ok", "true");
        }

        @GetMapping("/api/support/ping")
        Map<String, String> supportPing() {
            return Map.of("ok", "true");
        }

        @GetMapping("/api/misc/ping")
        Map<String, Object> miscPing() {
            return Map.of("userId", CurrentUser.id());
        }
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JwtService jwtService;
    @MockitoBean
    private UserAccessValidator userAccessValidator;
    @MockitoBean
    private AuthService authService;

    @Test
    void missingTokenIsUnauthorizedJson() throws Exception {
        mvc.perform(get("/api/tenant/ping"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.path").value("/api/tenant/ping"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.fieldErrors").isArray());

        mvc.perform(get("/api/misc/ping")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me")).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void invalidTokenIsUnauthorizedJson() throws Exception {
        mvc.perform(get("/api/tenant/ping").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-valid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(userAccessValidator);
    }

    @Test
    void prefixRulesByRole() throws Exception {
        mvc.perform(get("/api/tenant/ping").header(HttpHeaders.AUTHORIZATION, bearer(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(5));
        mvc.perform(get("/api/tenant/ping").header(HttpHeaders.AUTHORIZATION, bearer(OWNER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.path").value("/api/tenant/ping"));
        mvc.perform(get("/api/tenant/ping").header(HttpHeaders.AUTHORIZATION, bearer(SUPPORT)))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/platform/ping").header(HttpHeaders.AUTHORIZATION, bearer(OWNER)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/platform/ping").header(HttpHeaders.AUTHORIZATION, bearer(ADMIN)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/platform/ping").header(HttpHeaders.AUTHORIZATION, bearer(SUPPORT)))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/support/ping").header(HttpHeaders.AUTHORIZATION, bearer(SUPPORT)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/support/ping").header(HttpHeaders.AUTHORIZATION, bearer(EMPLOYEE)))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/misc/ping").header(HttpHeaders.AUTHORIZATION, bearer(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(1));
    }

    @Test
    void methodSecurityDeniesWithJsonForbidden() throws Exception {
        mvc.perform(get("/api/tenant/admin-only").header(HttpHeaders.AUTHORIZATION, bearer(EMPLOYEE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/tenant/admin-only").header(HttpHeaders.AUTHORIZATION, bearer(ADMIN)))
                .andExpect(status().isOk());
    }

    @Test
    void validatorErrorsKeepTheirCode() throws Exception {
        String token = jwtService.issue(user(ADMIN, 0));
        when(userAccessValidator.validate(eq(ADMIN.id()), anyInt())).thenThrow(new ApiException(HttpStatus.FORBIDDEN,
                "TENANT_DISABLED", UserAccessValidator.MSG_TENANT_DISABLED));

        mvc.perform(get("/api/tenant/ping").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_DISABLED"))
                .andExpect(jsonPath("$.message").value(UserAccessValidator.MSG_TENANT_DISABLED));

        when(userAccessValidator.validate(eq(ADMIN.id()), anyInt())).thenThrow(new ApiException(HttpStatus.UNAUTHORIZED,
                "USER_DISABLED", UserAccessValidator.MSG_USER_DISABLED));
        mvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("USER_DISABLED"));
    }

    @Test
    void loginIsPublicAndIgnoresStaleTokens() throws Exception {
        MeDto me = new MeDto(1L, "dueno@gondolia.app", "Dueño", Role.PLATFORM_OWNER, false, null, List.of());
        when(authService.login(any())).thenReturn(new LoginResponse("jwt", Instant.parse("2026-09-18T00:00:00Z"), me));

        mvc.perform(post("/api/auth/login")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer expired-or-garbage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"DUENO@gondolia.app\",\"password\":\"Gondolia2026!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("jwt"))
                .andExpect(jsonPath("$.expiresAt").value("2026-09-18T00:00:00Z"))
                .andExpect(jsonPath("$.user.role").value("PLATFORM_OWNER"))
                .andExpect(jsonPath("$.user.tenant").isEmpty())
                .andExpect(jsonPath("$.user.branches").isArray());
        verifyNoInteractions(userAccessValidator);
    }

    @Test
    void loginValidationErrorsUseStandardFormat() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Revisá los datos ingresados"))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("email")))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("password")));

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void corsPreflightAllowsBranchHeaderFromViteDevServer() throws Exception {
        mvc.perform(options("/api/tenant/ping")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,x-branch-id"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        containsStringIgnoringCase("x-branch-id")));

        mvc.perform(options("/api/tenant/ping")
                        .header(HttpHeaders.ORIGIN, "http://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden());
    }

    private String bearer(AuthUser authUser) {
        when(userAccessValidator.validate(eq(authUser.id()), anyInt())).thenReturn(authUser);
        return "Bearer " + jwtService.issue(user(authUser, 0));
    }

    private static User user(AuthUser authUser, int tokenVersion) {
        User user = new User();
        user.setId(authUser.id());
        user.setEmail(authUser.email());
        user.setFullName(authUser.fullName());
        user.setRole(authUser.role());
        user.setTenantId(authUser.tenantId());
        user.setTokenVersion(tokenVersion);
        return user;
    }
}
