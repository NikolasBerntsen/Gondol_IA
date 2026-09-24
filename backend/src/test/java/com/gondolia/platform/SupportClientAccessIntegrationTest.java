package com.gondolia.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gondolia.domain.user.Role;
import com.gondolia.domain.user.UserRepository;
import com.gondolia.it.PostgresIntegrationTest;
import com.gondolia.it.TestData;
import com.gondolia.security.AuthUser;
import com.gondolia.security.JwtService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Soporte en la consola de GondolIA (SPEC §3.3): para resolver tickets <b>ve y edita</b> los datos de un cliente, sus
 * módulos y restablece la contraseña de su administrador; todo lo comercial o destructivo (alta, bloqueo, baja,
 * reactivación, eliminación, cambio de plan), las métricas, la adopción de módulos, el equipo y los avisos siguen
 * siendo del dueño y responden 403 {@code FORBIDDEN}. Lo que hace soporte queda en el historial a su nombre.
 * <p>
 * Sobre HTTP (MockMvc + JWT reales) contra PostgreSQL; cada prueba se revierte.
 */
@Transactional
@AutoConfigureMockMvc
// Misma configuración que PlatformApiIsolationIntegrationTest: Spring reutiliza ese contexto (y su pool chico)
// en vez de abrir otro contra la base compartida.
@TestPropertySource(properties = {"spring.datasource.hikari.maximum-pool-size=4",
        "spring.datasource.hikari.minimum-idle=0"})
class SupportClientAccessIntegrationTest extends PostgresIntegrationTest {

    private static final String AGENT_NAME = "Sofía Soporte";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private String suffix;
    private long tenant;
    private long cancelledTenant;
    private String tenantName;
    private String cancelledName;
    private String supportToken;
    private String ownerToken;

    @BeforeEach
    void setUp() {
        TestData data = new TestData(jdbc);
        suffix = UUID.randomUUID().toString().substring(0, 8);
        tenant = data.tenant("Kiosco Soporte", "KIOSCO", "BASICO", "ACTIVE", "FIFO");
        data.branch(tenant, "Centro", true);
        data.user(tenant, Role.TENANT_ADMIN, true);
        jdbc.update("insert into tenant_modules (tenant_id, module, enabled) values (?, 'POS_GONDOLIA', true)", tenant);
        tenantName = jdbc.queryForObject("select name from tenants where id = ?", String.class, tenant);

        cancelledTenant = data.tenant("Almacén dado de baja", "ALMACEN", "FREEMIUM", "CANCELLED", "FIFO");
        cancelledName = jdbc.queryForObject("select name from tenants where id = ?", String.class, cancelledTenant);

        AuthUser support = data.user(null, Role.SUPPORT_AGENT, true);
        jdbc.update("update users set full_name = ? where id = ?", AGENT_NAME, support.id());
        supportToken = token(support);
        ownerToken = token(data.user(null, Role.PLATFORM_OWNER, true));
    }

    // ------------------------------------------------------------------ ver

    @Test
    void supportListsAndOpensTheClientsAndTheModuleMatrix() throws Exception {
        mvc.perform(as(supportToken, get("/api/platform/tenants").param("q", "Kiosco Soporte")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(tenant));
        mvc.perform(as(supportToken, get("/api/platform/tenants/" + tenant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(tenantName))
                .andExpect(jsonPath("$.branches[0].name").value("Centro"))
                // Igual que el dueño: solo datos administrativos.
                .andExpect(jsonPath("$.products").doesNotExist())
                .andExpect(jsonPath("$.sales").doesNotExist());
        mvc.perform(as(supportToken, get("/api/platform/tenants/" + tenant + "/modules")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].module").value("POS_GONDOLIA"))
                .andExpect(jsonPath("$[0].enabled").value(true));
        mvc.perform(as(supportToken, get("/api/platform/tenant-modules").param("q", "Kiosco Soporte")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].tenantId").value(tenant))
                .andExpect(jsonPath("$.content[0].modules.POS_GONDOLIA").value(true));
    }

    // ------------------------------------------------------------------ editar

    @Test
    void supportEditsTheClientDataButNotThePlan() throws Exception {
        mvc.perform(as(supportToken, put("/api/platform/tenants/" + tenant))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(tenantName, "BASICO", "11-4000-" + suffix.substring(0, 4))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contactPhone").value("11-4000-" + suffix.substring(0, 4)))
                .andExpect(jsonPath("$.plan").value("BASICO"))
                // La edición queda en el historial a nombre del agente, con qué datos cambió.
                .andExpect(jsonPath("$.events[0].type").value("DATA_UPDATED"))
                .andExpect(jsonPath("$.events[0].reason").value("Cambios: ciudad, provincia, contacto y teléfono"))
                .andExpect(jsonPath("$.events[0].actorName").value(AGENT_NAME));

        // El plan es una decisión comercial: 403 aunque el resto de los datos sea válido.
        mvc.perform(as(supportToken, put("/api/platform/tenants/" + tenant))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(tenantName, "PROFESIONAL", "11-4000-0000")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(jdbc.queryForObject("select plan from tenants where id = ?", String.class, tenant))
                .isEqualTo("BASICO");

        // El dueño sí lo cambia.
        mvc.perform(as(ownerToken, put("/api/platform/tenants/" + tenant))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(tenantName, "PROFESIONAL", "11-4000-0000")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").value("PROFESIONAL"));

        // El formulario de soporte no manda el plan: aunque el dueño lo haya cambiado mientras editaba, guarda sin
        // 403 y deja el plan que está.
        mvc.perform(as(supportToken, put("/api/platform/tenants/" + tenant))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody(tenantName, null, "11-4000-1111")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contactPhone").value("11-4000-1111"))
                .andExpect(jsonPath("$.plan").value("PROFESIONAL"));
    }

    @Test
    void supportTogglesAModuleAndTheHistoryNamesTheAgent() throws Exception {
        mvc.perform(as(supportToken, put("/api/platform/tenants/" + tenant + "/modules/POS_GONDOLIA"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.updatedByName").value(AGENT_NAME));

        mvc.perform(as(ownerToken, get("/api/platform/tenants/" + tenant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modules").isEmpty())
                .andExpect(jsonPath("$.events[0].type").value("MODULE_DISABLED"))
                .andExpect(jsonPath("$.events[0].fromValue").value("POS_GONDOLIA"))
                .andExpect(jsonPath("$.events[0].actorName").value(AGENT_NAME));
    }

    @Test
    void supportResetsTheAdminPasswordAndTheHistoryNamesTheAgent() throws Exception {
        mvc.perform(as(supportToken, post("/api/platform/tenants/" + tenant + "/reset-admin-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.temporaryPassword").isNotEmpty());
        assertThat(jdbc.queryForObject("""
                select must_change_password from users where tenant_id = ? and role = 'TENANT_ADMIN'
                """, Boolean.class, tenant)).isTrue();

        // Con la temporal se entra a la cuenta del admin: el dueño ve en el historial quién la generó y para quién.
        String adminEmail = jdbc.queryForObject("select email from users where tenant_id = ? and role = 'TENANT_ADMIN'",
                String.class, tenant);
        mvc.perform(as(ownerToken, get("/api/platform/tenants/" + tenant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].type").value("ADMIN_PASSWORD_RESET"))
                .andExpect(jsonPath("$.events[0].reason").value("Contraseña temporal para " + adminEmail))
                .andExpect(jsonPath("$.events[0].actorName").value(AGENT_NAME));
    }

    // ------------------------------------------------------------------ solo del dueño

    @Test
    void theCommercialAndDestructiveActionsStayWithTheOwner() throws Exception {
        String reason = "{\"reason\":\"Pedido por ticket\"}";
        mvc.perform(as(supportToken, post("/api/platform/tenants/" + tenant + "/disable"))
                        .contentType(MediaType.APPLICATION_JSON).content(reason))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(as(supportToken, post("/api/platform/tenants/" + tenant + "/cancel"))
                        .contentType(MediaType.APPLICATION_JSON).content(reason))
                .andExpect(status().isForbidden());
        mvc.perform(as(supportToken, post("/api/platform/tenants/" + cancelledTenant + "/reactivate"))
                        .contentType(MediaType.APPLICATION_JSON).content(reason))
                .andExpect(status().isForbidden());
        mvc.perform(as(supportToken, post("/api/platform/tenants/" + cancelledTenant + "/enable"))
                        .contentType(MediaType.APPLICATION_JSON).content(reason))
                .andExpect(status().isForbidden());
        mvc.perform(as(supportToken, delete("/api/platform/tenants/" + cancelledTenant)
                        .param("confirmName", cancelledName)))
                .andExpect(status().isForbidden());
        mvc.perform(as(supportToken, post("/api/platform/tenants"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isForbidden());

        // Nada cambió.
        assertThat(jdbc.queryForObject("select status from tenants where id = ?", String.class, tenant))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select count(*) from tenants where id = ?", Long.class, cancelledTenant))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from tenants where name like ?", Long.class,
                "Alta de soporte " + suffix + "%")).isZero();
    }

    @Test
    void metricsModuleAdoptionTeamAndAnnouncementsStayWithTheOwner() throws Exception {
        for (String endpoint : new String[] {"/api/platform/metrics", "/api/platform/modules", "/api/platform/users",
                "/api/platform/announcements"}) {
            mvc.perform(as(supportToken, get(endpoint)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
            mvc.perform(as(ownerToken, get(endpoint))).andExpect(status().isOk());
        }
    }

    /** Cuerpo de la edición; con {@code plan} nulo no lo manda (como el formulario de soporte). */
    private String updateBody(String name, String plan, String phone) {
        return """
                {"name":"%s","businessType":"KIOSCO",%s"contactName":"Marta","contactPhone":"%s",
                 "city":"Rosario","province":"Santa Fe"}
                """.formatted(name, plan == null ? "" : "\"plan\":\"" + plan + "\",", phone);
    }

    private String createBody() {
        return """
                {"name":"Alta de soporte %1$s","businessType":"KIOSCO","plan":"FREEMIUM",
                 "boss":{"fullName":"Jefe","email":"jefe.%1$s@test.gondolia","password":"Demo2026!"},
                 "admin":{"fullName":"Admin","email":"admin.%1$s@test.gondolia","password":"Demo2026!"},
                 "employee":{"fullName":"Empleado","email":"empleado.%1$s@test.gondolia","password":"Demo2026!"}}
                """.formatted(suffix);
    }

    private MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private String token(AuthUser user) {
        return jwtService.issue(userRepository.findById(user.id()).orElseThrow());
    }
}
