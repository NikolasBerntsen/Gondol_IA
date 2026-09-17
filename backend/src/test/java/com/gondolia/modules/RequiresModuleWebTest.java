package com.gondolia.modules;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gondolia.config.AppProperties;
import com.gondolia.config.ClockConfig;
import com.gondolia.config.CorsConfig;
import com.gondolia.domain.tenant.TenantModule;
import com.gondolia.domain.user.Role;
import com.gondolia.domain.user.User;
import com.gondolia.security.AuthUser;
import com.gondolia.security.JwtService;
import com.gondolia.security.SecurityConfig;
import com.gondolia.security.SecurityErrorHandler;
import com.gondolia.security.UserAccessValidator;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@link RequiresModule} aplicado por {@link RequiresModuleInterceptor} sobre {@code /api/**}: 403
 * {@code MODULE_DISABLED} si el comercio no tiene el módulo, 200 si lo tiene, la anotación del método gana sobre la de
 * la clase y los usuarios de plataforma no se ven afectados.
 */
@WebMvcTest(controllers = {RequiresModuleWebTest.PosProbeController.class,
        RequiresModuleWebTest.MixedProbeController.class})
@Import({SecurityConfig.class, SecurityErrorHandler.class, JwtService.class, ClockConfig.class, CorsConfig.class,
        RequiresModuleWebTest.TestConfig.class, RequiresModuleWebTest.PosProbeController.class,
        RequiresModuleWebTest.MixedProbeController.class})
class RequiresModuleWebTest {

    private static final AuthUser ADMIN = new AuthUser(10L, "admin@prueba.com", "Admin", Role.TENANT_ADMIN, 5L);
    private static final AuthUser CASHIER = new AuthUser(11L, "cajero@prueba.com", "Cajero", Role.TENANT_CASHIER, 5L);
    private static final AuthUser OWNER = new AuthUser(1L, "dueno@gondolia.app", "Dueño", Role.PLATFORM_OWNER, null);

    @TestConfiguration
    @EnableConfigurationProperties(AppProperties.class)
    static class TestConfig {
    }

    /** Controlador con el módulo declarado en la clase (como lo hará el módulo H). */
    @RestController
    @RequiresModule(TenantModule.POS_GONDOLIA)
    static class PosProbeController {

        @GetMapping("/api/tenant/pos-probe")
        Map<String, String> pos() {
            return Map.of("ok", "true");
        }

        /** La anotación del método pisa la de la clase. */
        @RequiresModule(TenantModule.MULTI_BRANCH)
        @GetMapping("/api/tenant/pos-probe/branches")
        Map<String, String> branches() {
            return Map.of("ok", "true");
        }
    }

    /** Un endpoint sin módulo, uno con módulo propio y otro fuera de {@code /api/tenant/**}. */
    @RestController
    static class MixedProbeController {

        @GetMapping("/api/tenant/free-probe")
        Map<String, String> free() {
            return Map.of("ok", "true");
        }

        @RequiresModule(TenantModule.MULTI_BRANCH)
        @GetMapping("/api/tenant/transfers-probe")
        Map<String, String> transfers() {
            return Map.of("ok", "true");
        }

        @RequiresModule(TenantModule.POS_GONDOLIA)
        @GetMapping("/api/misc/module-probe")
        Map<String, String> misc() {
            return Map.of("ok", "true");
        }
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JwtService jwtService;
    @MockitoBean
    private UserAccessValidator userAccessValidator;
    @MockitoBean
    private ModuleService moduleService;

    @Test
    void disabledModuleAnswers403WithItsCodeAndMessage() throws Exception {
        when(moduleService.isEnabled(5L, TenantModule.POS_GONDOLIA)).thenReturn(false);

        mvc.perform(get("/api/tenant/pos-probe").header(HttpHeaders.AUTHORIZATION, bearer(CASHIER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MODULE_DISABLED"))
                .andExpect(jsonPath("$.message").value(ModuleCatalog.MSG_MODULE_DISABLED))
                .andExpect(jsonPath("$.path").value("/api/tenant/pos-probe"))
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void enabledModuleLetsTheRequestThrough() throws Exception {
        when(moduleService.isEnabled(5L, TenantModule.POS_GONDOLIA)).thenReturn(true);

        mvc.perform(get("/api/tenant/pos-probe").header(HttpHeaders.AUTHORIZATION, bearer(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value("true"));
    }

    @Test
    void endpointsWithoutTheAnnotationNeverCheckModules() throws Exception {
        mvc.perform(get("/api/tenant/free-probe").header(HttpHeaders.AUTHORIZATION, bearer(ADMIN)))
                .andExpect(status().isOk());

        verify(moduleService, never()).isEnabled(any(), any());
    }

    @Test
    void methodAnnotationChecksItsOwnModule() throws Exception {
        when(moduleService.isEnabled(5L, TenantModule.MULTI_BRANCH)).thenReturn(false);

        mvc.perform(get("/api/tenant/transfers-probe").header(HttpHeaders.AUTHORIZATION, bearer(ADMIN)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MODULE_DISABLED"));

        when(moduleService.isEnabled(5L, TenantModule.MULTI_BRANCH)).thenReturn(true);
        mvc.perform(get("/api/tenant/transfers-probe").header(HttpHeaders.AUTHORIZATION, bearer(ADMIN)))
                .andExpect(status().isOk());
    }

    @Test
    void methodAnnotationWinsOverTheOneOnTheClass() throws Exception {
        when(moduleService.isEnabled(5L, TenantModule.POS_GONDOLIA)).thenReturn(true);
        when(moduleService.isEnabled(5L, TenantModule.MULTI_BRANCH)).thenReturn(false);

        mvc.perform(get("/api/tenant/pos-probe/branches").header(HttpHeaders.AUTHORIZATION, bearer(ADMIN)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MODULE_DISABLED"));

        when(moduleService.isEnabled(5L, TenantModule.MULTI_BRANCH)).thenReturn(true);
        mvc.perform(get("/api/tenant/pos-probe/branches").header(HttpHeaders.AUTHORIZATION, bearer(ADMIN)))
                .andExpect(status().isOk());
    }

    @Test
    void platformUsersAreNotAffectedByModules() throws Exception {
        when(moduleService.isEnabled(any(), any())).thenReturn(false);

        mvc.perform(get("/api/misc/module-probe").header(HttpHeaders.AUTHORIZATION, bearer(OWNER)))
                .andExpect(status().isOk());

        verify(moduleService, never()).isEnabled(any(), any());
    }

    private String bearer(AuthUser authUser) {
        when(userAccessValidator.validate(eq(authUser.id()), anyInt())).thenReturn(authUser);
        return "Bearer " + jwtService.issue(user(authUser));
    }

    private static User user(AuthUser authUser) {
        User user = new User();
        user.setId(authUser.id());
        user.setEmail(authUser.email());
        user.setFullName(authUser.fullName());
        user.setRole(authUser.role());
        user.setTenantId(authUser.tenantId());
        user.setTokenVersion(0);
        return user;
    }
}
