package com.gondolia.imports;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gondolia.common.PageResponse;
import com.gondolia.common.error.NotFoundException;
import com.gondolia.config.AppProperties;
import com.gondolia.config.ClockConfig;
import com.gondolia.config.CorsConfig;
import com.gondolia.domain.user.Role;
import com.gondolia.domain.user.User;
import com.gondolia.imports.dto.ImportDtos;
import com.gondolia.modules.ModuleService;
import com.gondolia.security.AuthUser;
import com.gondolia.security.JwtService;
import com.gondolia.security.SecurityConfig;
import com.gondolia.security.SecurityErrorHandler;
import com.gondolia.security.UserAccessValidator;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Seguridad HTTP de la importación masiva (SPEC §3.3): solo el administrador del comercio entra a
 * {@code /api/tenant/imports/**}; el resto de los roles recibe 403 sin llegar al servicio.
 */
@WebMvcTest(controllers = ImportController.class)
@Import({SecurityConfig.class, SecurityErrorHandler.class, JwtService.class, ClockConfig.class, CorsConfig.class,
        ImportControllerWebTest.TestConfig.class})
class ImportControllerWebTest {

    private static final AuthUser ADMIN = new AuthUser(10L, "admin@prueba.com", "Admin", Role.TENANT_ADMIN, 5L);

    @TestConfiguration
    @EnableConfigurationProperties(AppProperties.class)
    static class TestConfig {
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JwtService jwtService;
    @MockitoBean
    private UserAccessValidator userAccessValidator;
    @MockitoBean
    private ModuleService moduleService;
    @MockitoBean
    private ImportService importService;
    @MockitoBean
    private ImportFileService fileService;

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"TENANT_BOSS", "TENANT_EMPLOYEE", "TENANT_CASHIER"})
    void onlyTheAdminCanUseTheImports(Role role) throws Exception {
        AuthUser user = new AuthUser(20L, "otro@prueba.com", "Otro", role, 5L);

        mvc.perform(get("/api/tenant/imports").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
        mvc.perform(get("/api/tenant/imports/template").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/tenant/imports/export").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/tenant/imports/1/apply").header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(importService, fileService);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"PLATFORM_OWNER", "SUPPORT_AGENT"})
    void platformUsersNeverReachTheTenantImports(Role role) throws Exception {
        AuthUser user = new AuthUser(1L, "equipo@gondolia.app", "Equipo", role, null);

        mvc.perform(get("/api/tenant/imports").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(importService, fileService);
    }

    @Test
    void anonymousRequestsAreRejected() throws Exception {
        mvc.perform(get("/api/tenant/imports/fields")).andExpect(status().isUnauthorized());

        verifyNoInteractions(importService, fileService);
    }

    @Test
    void theAdminSeesTheHistory() throws Exception {
        when(importService.list(0, 20)).thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        mvc.perform(get("/api/tenant/imports").header(HttpHeaders.AUTHORIZATION, bearer(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void anImportOfAnotherTenantAnswers404() throws Exception {
        when(importService.get(99L)).thenThrow(new NotFoundException("La importación no existe."));

        mvc.perform(get("/api/tenant/imports/99").header(HttpHeaders.AUTHORIZATION, bearer(ADMIN)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("La importación no existe."));
    }

    @Test
    void theTemplateIsServedAsAnAttachment() throws Exception {
        when(fileService.template("csv")).thenReturn(new ImportFileService.GeneratedFile(
                "gondolia-plantilla-productos.csv", "text/csv; charset=UTF-8",
                "Nombre\n".getBytes(StandardCharsets.UTF_8)));

        mvc.perform(get("/api/tenant/imports/template").param("format", "csv")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("gondolia-plantilla-productos.csv")));
    }

    @Test
    void theMappingBodyIsValidated() throws Exception {
        mvc.perform(put("/api/tenant/imports/1/mapping").header(HttpHeaders.AUTHORIZATION, bearer(ADMIN))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"columnMapping\":{\"name\":\"Producto\"},\"options\":{\"dateFormat\":\"DD/MM/AAAA\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(importService);
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
