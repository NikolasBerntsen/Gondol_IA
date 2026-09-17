package com.gondolia.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gondolia.domain.support.TicketCategory;
import com.gondolia.domain.support.TicketChannel;
import com.gondolia.domain.support.TicketPriority;
import com.gondolia.domain.user.Role;
import com.gondolia.domain.user.User;
import com.gondolia.domain.user.UserRepository;
import com.gondolia.it.PostgresIntegrationTest;
import com.gondolia.it.TestData;
import com.gondolia.security.AuthUser;
import com.gondolia.security.JwtService;
import com.gondolia.support.dto.CreateTicketRequest;
import com.gondolia.support.dto.TicketDetail;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Permisos y aislamiento de los endpoints de soporte sobre la aplicación completa (SPEC §3.4):
 * un comercio nunca ve los tickets de otro, los usuarios de comercio no entran a la consola de agentes y los agentes
 * no entran a los endpoints de comercio.
 */
@AutoConfigureMockMvc
@Transactional
class SupportApiIsolationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private SupportService supportService;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    private String adminToken;
    private String otherAdminToken;
    private String agentToken;
    private long ticketId;

    @BeforeEach
    void setUp() {
        TestData data = new TestData(jdbc);
        long tenant = data.tenant("Comercio Uno", "ALMACEN", "BASICO", "ACTIVE", "FIFO");
        long otherTenant = data.tenant("Comercio Dos", "KIOSCO", "FREEMIUM", "ACTIVE", "FIFO");
        data.branch(tenant, "Centro", true);
        data.branch(otherTenant, "Unica", true);
        AuthUser admin = data.user(tenant, Role.TENANT_ADMIN, true);
        AuthUser otherAdmin = data.user(otherTenant, Role.TENANT_ADMIN, true);
        AuthUser agent = data.user(null, Role.SUPPORT_AGENT, true);

        adminToken = token(admin);
        otherAdminToken = token(otherAdmin);
        agentToken = token(agent);

        TicketDetail ticket = supportService.createTicket(admin, new CreateTicketRequest("Falla el escáner",
                TicketCategory.TECNICO, TicketPriority.ALTA, TicketChannel.TICKET, "No lee los códigos"));
        ticketId = ticket.id();
    }

    @Test
    void anonymousRequestsAreRejected() throws Exception {
        mockMvc.perform(get("/api/tenant/support/tickets"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(get("/api/support/tickets"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tenantUsersCannotEnterTheSupportConsole() throws Exception {
        mockMvc.perform(get("/api/support/tickets").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/support/stats").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void supportAgentsCannotEnterTenantEndpoints() throws Exception {
        mockMvc.perform(get("/api/tenant/support/tickets").header(HttpHeaders.AUTHORIZATION, "Bearer " + agentToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void anotherTenantCannotReadOrAnswerTheTicket() throws Exception {
        mockMvc.perform(get("/api/tenant/support/tickets/" + ticketId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherAdminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        mockMvc.perform(multipart("/api/tenant/support/tickets/" + ticketId + "/messages")
                        .param("body", "Hola")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherAdminToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/tenant/support/tickets/" + ticketId + "/close")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherAdminToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/tenant/support/tickets")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == %d)]".formatted(ticketId)).isEmpty());
    }

    @Test
    void ownerTenantAndAgentCanSeeTheTicket() throws Exception {
        mockMvc.perform(get("/api/tenant/support/tickets/" + ticketId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("Falla el escáner"))
                .andExpect(jsonPath("$.messages.length()").value(1));

        mockMvc.perform(get("/api/support/tickets/" + ticketId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + agentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantName").value("Comercio Uno " + tenantSuffix()))
                .andExpect(jsonPath("$.unreadCount").value(1));
    }

    @Test
    void validationErrorsComeBackWithFieldMessages() throws Exception {
        String body = objectMapper.writeValueAsString(new CreateTicketRequest("  ", null, null, null, "Hola"));

        mockMvc.perform(post("/api/tenant/support/tickets")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("subject"));
    }

    @Test
    void emptyMessagesAreRejected() throws Exception {
        mockMvc.perform(multipart("/api/tenant/support/tickets/" + ticketId + "/messages")
                        .param("body", "   ")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMPTY_MESSAGE"));
    }

    private String token(AuthUser user) {
        User entity = userRepository.findById(user.id()).orElseThrow();
        return jwtService.issue(entity);
    }

    private String tenantSuffix() {
        return jdbc.queryForObject("select name from tenants where id = (select tenant_id from support_tickets "
                + "where id = ?)", String.class, ticketId).replace("Comercio Uno ", "");
    }
}
