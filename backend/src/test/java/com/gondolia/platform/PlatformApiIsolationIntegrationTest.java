package com.gondolia.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gondolia.domain.tenant.TenantModule;
import com.gondolia.domain.user.Role;
import com.gondolia.domain.user.UserRepository;
import com.gondolia.it.PostgresIntegrationTest;
import com.gondolia.it.TestData;
import com.gondolia.security.AuthUser;
import com.gondolia.security.JwtService;
import java.util.List;
import java.util.Locale;
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
 * Aislamiento y privacidad de la consola de dueños (SPEC §3.4 y §6.6):
 * <ul>
 *   <li>un dueño de GondolIA recibe 403 en cualquier endpoint de comercio ({@code /api/tenant/**}) y de soporte;</li>
 *   <li>los usuarios de comercio (de cualquier comercio) y el soporte reciben 403 en {@code /api/platform/**};</li>
 *   <li>el detalle de un cliente solo trae datos administrativos: ni productos, ni stock, ni ventas, ni chats;</li>
 *   <li>las métricas y los recalls son solo cantidades: nunca dicen qué comercios están alcanzados.</li>
 * </ul>
 */
@Transactional
@AutoConfigureMockMvc
class PlatformApiIsolationIntegrationTest extends PostgresIntegrationTest {

    /** Endpoints de datos de negocio de un comercio (los implementan otros módulos). */
    private static final List<String> TENANT_ENDPOINTS = List.of(
            "/api/tenant/products", "/api/tenant/lots", "/api/tenant/sales", "/api/tenant/movements",
            "/api/tenant/alerts", "/api/tenant/insights", "/api/tenant/expirations", "/api/tenant/users",
            "/api/tenant/settings", "/api/tenant/support/tickets", "/api/tenant/pos/registers");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private TestData data;
    private String ownerToken;
    private String adminToken;
    private String otherAdminToken;
    private String supportToken;
    private long tenantA;
    private long tenantB;

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc);
        tenantA = data.tenant("Aislamiento A", "ALMACEN", "BASICO", "ACTIVE", "FIFO");
        tenantB = data.tenant("Aislamiento B", "KIOSCO", "FREEMIUM", "ACTIVE", "FIFO");
        long branchA = data.branch(tenantA, "Centro", true);
        data.branch(tenantB, "Principal", true);
        jdbc.update("insert into tenant_modules (tenant_id, module, enabled) values (?, 'POS_GONDOLIA', true)",
                tenantA);

        ownerToken = token(data.user(null, Role.PLATFORM_OWNER, true));
        adminToken = token(data.user(tenantA, Role.TENANT_ADMIN, true));
        otherAdminToken = token(data.user(tenantB, Role.TENANT_ADMIN, true));
        supportToken = token(data.user(null, Role.SUPPORT_AGENT, true));
        data.user(tenantA, Role.TENANT_EMPLOYEE, true, branchA);
    }

    @Test
    void platformOwnerCannotReachTenantData() throws Exception {
        for (String endpoint : TENANT_ENDPOINTS) {
            mvc.perform(get(endpoint).header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        mvc.perform(get("/api/support/tickets").header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void tenantUsersAndSupportCannotReachThePlatformConsole() throws Exception {
        for (String token : List.of(adminToken, otherAdminToken, supportToken)) {
            mvc.perform(get("/api/platform/metrics").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/platform/tenants").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/platform/tenants/" + tenantA).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/platform/tenant-modules").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/platform/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/platform/tenants/" + tenantB + "/disable")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"prueba\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(put("/api/platform/tenants/" + tenantA + "/modules/POS_GONDOLIA")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"enabled\":false}"))
                    .andExpect(status().isForbidden());
            mvc.perform(delete("/api/platform/tenants/" + tenantB + "?confirmName=x")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/platform/metrics")).andExpect(status().isUnauthorized());
    }

    @Test
    void tenantDetailOnlyExposesAdministrativeData() throws Exception {
        String body = mvc.perform(get("/api/platform/tenants/" + tenantA)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").exists())
                .andExpect(jsonPath("$.plan").value("BASICO"))
                .andExpect(jsonPath("$.branches[0].name").value("Centro"))
                .andExpect(jsonPath("$.modules[0]").value(TenantModule.POS_GONDOLIA.name()))
                .andExpect(jsonPath("$.usersByRole.TENANT_ADMIN").value(1))
                // Nada de negocio.
                .andExpect(jsonPath("$.products").doesNotExist())
                .andExpect(jsonPath("$.lots").doesNotExist())
                .andExpect(jsonPath("$.sales").doesNotExist())
                .andExpect(jsonPath("$.alerts").doesNotExist())
                .andExpect(jsonPath("$.stock").doesNotExist())
                .andExpect(jsonPath("$.branches[0].stock").doesNotExist())
                .andExpect(jsonPath("$.branches[0].posApiKeyHash").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        String lower = body.toLowerCase(Locale.ROOT);
        assertThat(lower).doesNotContain("\"sales", "\"inventory", "\"lots", "\"passwordhash", "\"posapikey");
    }

    @Test
    void metricsAndRecallsAreOnlyAggregates() throws Exception {
        String body = mvc.perform(get("/api/platform/metrics")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenants.total").isNumber())
                .andExpect(jsonPath("$.revenue.estimatedMrr").isNumber())
                .andExpect(jsonPath("$.recalls.affectedTenantsTotal").isNumber())
                .andExpect(jsonPath("$.recalls.tenants").doesNotExist())
                .andExpect(jsonPath("$.recalls.matches").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("Aislamiento A", "Aislamiento B");
    }

    @Test
    void ownerSeesEveryTenantButOnlyItsAdministrativeFields() throws Exception {
        mvc.perform(get("/api/platform/tenants?q=Aislamiento")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].userCount").isNumber())
                .andExpect(jsonPath("$.content[0].activeBranchCount").isNumber())
                .andExpect(jsonPath("$.content[0].monthlyFee").isNumber())
                .andExpect(jsonPath("$.content[0].products").doesNotExist());
    }

    private String token(AuthUser user) {
        return jwtService.issue(userRepository.findById(user.id()).orElseThrow());
    }
}
