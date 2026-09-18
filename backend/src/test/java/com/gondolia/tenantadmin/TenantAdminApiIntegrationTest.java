package com.gondolia.tenantadmin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gondolia.domain.user.Role;
import com.gondolia.domain.user.User;
import com.gondolia.domain.user.UserRepository;
import com.gondolia.it.PostgresIntegrationTest;
import com.gondolia.it.TestData;
import com.gondolia.security.AuthUser;
import com.gondolia.security.BranchAccessService;
import com.gondolia.security.JwtService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Endpoints de {@code /api/tenant/users}, {@code /api/tenant/branches} y {@code /api/tenant/settings} sobre HTTP:
 * permisos por rol, aislamiento entre comercios (404 con ids ajenos), alcance por sucursal del cajero y errores de
 * validación con {@code fieldErrors}. Cada prueba se revierte.
 */
@Transactional
@AutoConfigureMockMvc
class TenantAdminApiIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private TestData data;
    private long tenant;
    private long otherTenant;
    private long centro;
    private long norte;
    private long otherBranch;
    private AuthUser admin;
    private AuthUser cashier;
    private AuthUser employee;
    private AuthUser foreignAdmin;
    private AuthUser foreignEmployee;

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc);
        tenant = data.tenant("Comercio de la prueba");
        otherTenant = data.tenant("Comercio ajeno");
        centro = data.branch(tenant, "Centro", true);
        norte = data.branch(tenant, "Norte", true);
        otherBranch = data.branch(otherTenant, "Ajena", true);
        admin = data.user(tenant, Role.TENANT_ADMIN, true);
        cashier = data.user(tenant, Role.TENANT_CASHIER, true, centro);
        employee = data.user(tenant, Role.TENANT_EMPLOYEE, true, centro, norte);
        foreignAdmin = data.user(otherTenant, Role.TENANT_ADMIN, true);
        foreignEmployee = data.user(otherTenant, Role.TENANT_EMPLOYEE, true, otherBranch);
    }

    // ------------------------------------------------------------------ permisos por rol

    @Test
    void onlyTheAdminManagesUsersAndSettings() throws Exception {
        mockMvc.perform(get("/api/tenant/users").headers(auth(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].email", Matchers.hasItem(admin.email())));

        mockMvc.perform(get("/api/tenant/users").headers(auth(employee)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(get("/api/tenant/settings").headers(auth(cashier)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/tenant/settings").headers(auth(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockRotation").value("FIFO"));
    }

    // ------------------------------------------------------------------ aislamiento entre comercios

    @Test
    void usersOfAnotherTenantAreInvisibleAndUnreachable() throws Exception {
        mockMvc.perform(get("/api/tenant/users").headers(auth(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].email", Matchers.not(Matchers.hasItem(foreignEmployee.email()))));

        mockMvc.perform(put("/api/tenant/users/" + foreignEmployee.id())
                        .headers(auth(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Robado","role":"TENANT_EMPLOYEE","active":false,"branchIds":[]}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        mockMvc.perform(post("/api/tenant/users/" + foreignEmployee.id() + "/reset-password")
                        .headers(auth(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void branchesOfAnotherTenantAreInvisibleAndUnreachable() throws Exception {
        mockMvc.perform(get("/api/tenant/branches?includeInactive=true").headers(auth(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", Matchers.not(Matchers.hasItem((int) otherBranch))));

        mockMvc.perform(put("/api/tenant/branches/" + otherBranch)
                        .headers(auth(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Robada\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/tenant/branches/" + otherBranch + "/deactivate").headers(auth(admin)))
                .andExpect(status().isNotFound());

        // El administrador del otro comercio tampoco alcanza las nuestras.
        mockMvc.perform(post("/api/tenant/branches/" + centro + "/deactivate").headers(auth(foreignAdmin)))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ alcance por sucursal

    @Test
    void cashierOnlySeesTheAssignedBranches() throws Exception {
        mockMvc.perform(get("/api/tenant/branches").headers(auth(cashier)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(centro));

        // Un encabezado de sucursal ajena no le abre la puerta a otro comercio.
        mockMvc.perform(get("/api/tenant/branches")
                        .headers(auth(cashier))
                        .header(BranchAccessService.HEADER, String.valueOf(otherBranch)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(centro));

        mockMvc.perform(get("/api/tenant/branches").headers(auth(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    // ------------------------------------------------------------------ validación

    @Test
    void reportsFieldErrorsWhenCreatingAUser() throws Exception {
        mockMvc.perform(post("/api/tenant/users")
                        .headers(auth(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"","email":"no-es-un-email","password":"corta","role":"TENANT_CASHIER"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[*].field",
                        Matchers.hasItems("fullName", "email", "password")));
    }

    @Test
    void cashierWithoutBranchesIsRejected() throws Exception {
        mockMvc.perform(post("/api/tenant/users")
                        .headers(auth(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Carla Cajera","email":"carla.%s@prueba.com","password":"Demo2026!",
                                 "role":"TENANT_CASHIER","branchIds":[]}""".formatted(data.suffix())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ------------------------------------------------------------------ helpers

    private HttpHeaders auth(AuthUser user) {
        User stored = userRepository.findById(user.id()).orElseThrow();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.issueToken(stored).token());
        return headers;
    }
}
