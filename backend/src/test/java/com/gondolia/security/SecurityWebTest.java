package com.gondolia.security;

import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reglas de seguridad HTTP sin base de datos: prefijos, respuestas 401/403 en formato JSON, validación del usuario del
 * token, seguridad por método y CORS.
 */
@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, SecurityErrorHandler.class, JwtService.class, ClockConfig.class, CorsConfig.class,
        SecurityWebTest.TestConfig.class, SecurityWebTest.ProbeController.class,
        SecurityWebTest.AdminProbeController.class})
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

        @PreAuthorize(Roles.TENANT_ADMIN)
        @PostMapping("/api/tenant/admin-write")
        Map<String, String> adminWrite(@Valid @RequestBody ProbeBody body) {
            return Map.of("name", body.name());
        }

        /** Expresión que mira un argumento: la resuelve la seguridad por método, con el valor real. */
        @PreAuthorize("#id == 7")
        @PostMapping("/api/tenant/by-arg/{id}")
        Map<String, Object> byArgument(@PathVariable Long id) {
            return Map.of("id", id);
        }

        @GetMapping("/api/platform/ping")
        Map<String, String> platformPing() {
            return Map.of("ok", "true");
        }

        /** Sin {@code @PreAuthorize}: prueban solo la regla por URL de las rutas de clientes que usa soporte. */
        @GetMapping("/api/platform/tenants/{id}")
        Map<String, Object> platformTenant(@PathVariable Long id) {
            return Map.of("id", id);
        }

        @DeleteMapping("/api/platform/tenants/{id}")
        Map<String, Object> platformTenantDelete(@PathVariable Long id) {
            return Map.of("id", id);
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

    record ProbeBody(@NotBlank(message = "es obligatorio") String name) {
    }

    /** {@code @PreAuthorize} en la clase, como la mayoría de los controladores (por ejemplo, la importación). */
    @RestController
    @PreAuthorize(Roles.TENANT_ADMIN)
    static class AdminProbeController {

        @PostMapping("/api/tenant/admin-class/{id}")
        Map<String, Object> write(@PathVariable Long id, @Valid @RequestBody ProbeBody body) {
            return Map.of("id", id, "name", body.name());
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
    void supportReachesOnlyTheClientRoutesOfTheConsoleWithTheirMethods() throws Exception {
        // Primera barrera (por URL y método): soporte ve un cliente pero no lo elimina, aunque el controlador no
        // tuviera @PreAuthorize.
        mvc.perform(get("/api/platform/tenants/7").header(HttpHeaders.AUTHORIZATION, bearer(SUPPORT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7));
        mvc.perform(delete("/api/platform/tenants/7").header(HttpHeaders.AUTHORIZATION, bearer(SUPPORT)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(delete("/api/platform/tenants/7").header(HttpHeaders.AUTHORIZATION, bearer(OWNER)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/platform/tenants/7").header(HttpHeaders.AUTHORIZATION, bearer(ADMIN)))
                .andExpect(status().isForbidden());
    }

    @Test
    void methodSecurityDeniesWithJsonForbidden() throws Exception {
        mvc.perform(get("/api/tenant/admin-only").header(HttpHeaders.AUTHORIZATION, bearer(EMPLOYEE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/tenant/admin-only").header(HttpHeaders.AUTHORIZATION, bearer(ADMIN)))
                .andExpect(status().isOk());
    }

    /**
     * SPEC §3.3: un rol sin permiso recibe 403 sea cual sea el cuerpo; la validación (y el detalle de los campos) solo
     * le llega a quien puede usar el endpoint. Antes el {@code @Valid} corría antes que el {@code @PreAuthorize}.
     */
    @Test
    void roleIsCheckedBeforeTheBodyIsReadOrValidated() throws Exception {
        String employee = bearer(EMPLOYEE);
        for (String body : new String[] {"{}", "{not json", "{\"name\":\"ok\"}"}) {
            mvc.perform(post("/api/tenant/admin-write").header(HttpHeaders.AUTHORIZATION, employee)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                    .andExpect(jsonPath("$.fieldErrors").isEmpty());
        }
        mvc.perform(post("/api/tenant/admin-write").header(HttpHeaders.AUTHORIZATION, employee))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/tenant/admin-class/no-es-un-id").header(HttpHeaders.AUTHORIZATION, employee)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        String admin = bearer(ADMIN);
        mvc.perform(post("/api/tenant/admin-write").header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"));
        mvc.perform(post("/api/tenant/admin-write").header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("ok"));
        mvc.perform(post("/api/tenant/admin-class/no-es-un-id").header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(post("/api/tenant/admin-class/5").header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5));
    }

    @Test
    void expressionsOnArgumentsAreLeftToMethodSecurity() throws Exception {
        mvc.perform(post("/api/tenant/by-arg/7").header(HttpHeaders.AUTHORIZATION, bearer(EMPLOYEE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7));
        mvc.perform(post("/api/tenant/by-arg/8").header(HttpHeaders.AUTHORIZATION, bearer(EMPLOYEE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
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

    /**
     * Los mismos valores que pone nginx (que oculta los del backend): una sola vez cada uno y sin HSTS aunque la request
     * llegue como HTTPS a través del proxy.
     */
    @Test
    void securityHeadersAreSingleConsistentAndWithoutHsts() throws Exception {
        mvc.perform(get("/api/tenant/ping").secure(true).header(HttpHeaders.AUTHORIZATION, bearer(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(header().stringValues("X-Frame-Options", "DENY"))
                .andExpect(header().stringValues("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsStringIgnoringCase("no-store")))
                .andExpect(header().doesNotExist("Strict-Transport-Security"));

        mvc.perform(get("/api/tenant/ping").secure(true))
                .andExpect(status().isUnauthorized())
                .andExpect(header().stringValues("X-Frame-Options", "DENY"))
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
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
