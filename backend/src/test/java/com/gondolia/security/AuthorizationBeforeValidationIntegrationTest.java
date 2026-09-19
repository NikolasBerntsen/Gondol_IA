package com.gondolia.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gondolia.domain.user.Role;
import com.gondolia.domain.user.User;
import com.gondolia.domain.user.UserRepository;
import com.gondolia.it.PostgresIntegrationTest;
import com.gondolia.it.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Los pedidos de las pruebas de punta a punta, con el contexto completo contra PostgreSQL: quien no está autorizado
 * recibe 403 (rol) o 401 {@code INVALID_API_KEY} (webhook) sea cual sea el cuerpo (SPEC §3.3, §6.4), y una key válida
 * de un comercio deshabilitado recibe 403 {@code TENANT_DISABLED} (SPEC §3.2). Cada prueba se revierte.
 */
@Transactional
@AutoConfigureMockMvc
class AuthorizationBeforeValidationIntegrationTest extends PostgresIntegrationTest {

    /** Contexto propio (agrega MockMvc): pool chico para no acaparar la base compartida. */
    @DynamicPropertySource
    static void smallPool(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
    }

    private static final String WEBHOOK = "/api/integrations/pos/sales";
    private static final String INVALID_SALE = "{\"sessionId\":999999,\"items\":[],\"payments\":[]}";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private ApiKeyService apiKeyService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private long tenant;
    private long branch;
    private AuthUser boss;
    private AuthUser admin;

    @BeforeEach
    void setUp() {
        TestData data = new TestData(jdbc);
        tenant = data.tenant("Autorización antes de validar");
        branch = data.branch(tenant, "Centro", true);
        boss = data.user(tenant, Role.TENANT_BOSS, true);
        admin = data.user(tenant, Role.TENANT_ADMIN, true);
        for (String module : new String[] {"POS_GONDOLIA", "POS_INTEGRATION"}) {
            jdbc.update("insert into tenant_modules (tenant_id, module, enabled) values (?, ?, true)", tenant, module);
        }
    }

    @Test
    void theBossGets403FromThePosWhateverTheBody() throws Exception {
        mockMvc.perform(json(post("/api/tenant/pos/sales"), boss, INVALID_SALE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());

        mockMvc.perform(json(post("/api/tenant/pos/sales"), admin, INVALID_SALE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void theBossGets403FromTheImportsWhateverTheBody() throws Exception {
        mockMvc.perform(json(post("/api/tenant/imports/999999/rows/bulk"), boss, "{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(json(post("/api/tenant/imports/999999/rows/bulk"), boss,
                        "{\"rowIds\":[1],\"action\":\"SKIP\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(json(post("/api/tenant/imports/999999/rows/bulk"), admin, "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void theWebhookAsksForTheKeyBeforeReadingTheBody() throws Exception {
        mockMvc.perform(post(WEBHOOK).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_API_KEY"))
                .andExpect(jsonPath("$.fieldErrors").isEmpty());
        mockMvc.perform(post(WEBHOOK).header(ApiKeyService.HEADER, apiKeyService.generate().rawKey())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_API_KEY"));
    }

    @Test
    void aKeyOfADisabledTenantAnswersTenantDisabled() throws Exception {
        GeneratedApiKey key = apiKeyService.generate();
        jdbc.update("update branches set pos_api_key_hash = ?, pos_api_key_prefix = ?, pos_api_key_created_at = now() "
                + "where id = ?", key.hash(), key.prefix(), branch);

        mockMvc.perform(post(WEBHOOK).header(ApiKeyService.HEADER, key.rawKey())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("items"));

        jdbc.update("update tenants set status = 'DISABLED', status_reason = 'Falta de pago' where id = ?", tenant);
        mockMvc.perform(post(WEBHOOK).header(ApiKeyService.HEADER, key.rawKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"barcode\":\"7790000000000\",\"quantity\":1}]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TENANT_DISABLED"))
                .andExpect(jsonPath("$.message").value(UserAccessValidator.MSG_TENANT_DISABLED));
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, AuthUser user, String body) {
        User stored = userRepository.findById(user.id()).orElseThrow();
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtService.issueToken(stored).token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }
}
