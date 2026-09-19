package com.gondolia.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gondolia.domain.user.Role;
import com.gondolia.domain.user.UserRepository;
import com.gondolia.it.PostgresIntegrationTest;
import com.gondolia.it.TestData;
import com.gondolia.security.JwtService;
import java.time.LocalDate;
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
 * "Comprar N" del Inicio sobre HTTP ({@code POST /api/tenant/recommendations/reorder}): lo usan el jefe y el
 * administrador (SPEC §3.3: el botón visible funciona), el empleado recibe 403 y los errores de validación de este módulo
 * responden en castellano, también los de los parámetros de consulta.
 */
@Transactional
@AutoConfigureMockMvc
// Misma configuración que BossAccessIntegrationTest: Spring reutiliza ese contexto (y su pool chico).
@TestPropertySource(properties = {"spring.datasource.hikari.maximum-pool-size=4",
        "spring.datasource.hikari.minimum-idle=0"})
class RestockOrderWebIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private long tenant;
    private long centro;
    private long milk;
    private String bossToken;
    private String adminToken;
    private String employeeToken;

    @BeforeEach
    void setUp() {
        TestData data = new TestData(jdbc);
        tenant = data.tenant("Comprar desde el Inicio");
        centro = data.branch(tenant, "Centro", true);
        milk = data.product(tenant, data.barcode(), "Leche entera 1 L", "800", "1200");
        jdbc.update("update products set min_stock = 10 where id = ?", milk);
        data.lot(tenant, centro, milk, "L-1", "L1", LocalDate.now().plusDays(20), 2, "ACTIVE", 5);

        bossToken = token(data.user(tenant, Role.TENANT_BOSS, true).id());
        adminToken = token(data.user(tenant, Role.TENANT_ADMIN, true).id());
        employeeToken = token(data.user(tenant, Role.TENANT_EMPLOYEE, true, centro).id());
    }

    @Test
    void theBossAndTheAdminRecordTheOrderAndTheEmployeeCannot() throws Exception {
        mvc.perform(as(bossToken, order(12)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendation.type").value("REORDER"))
                .andExpect(jsonPath("$.recommendation.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.orderedQuantity").value(12))
                .andExpect(jsonPath("$.whatsappText").isNotEmpty());

        // La fila de "Artículos a reponer" lo muestra como pedido (sobrevive a recargar la página).
        mvc.perform(as(adminToken, get("/api/tenant/dashboard/reorder")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].productId").value(milk))
                .andExpect(jsonPath("$[0].orderedQuantity").value(12))
                .andExpect(jsonPath("$[0].orderedAt").isNotEmpty());

        mvc.perform(as(adminToken, order(20))).andExpect(status().isOk());
        mvc.perform(as(employeeToken, order(5))).andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("""
                select count(*) from recommendations where tenant_id = ? and type = 'REORDER' and status = 'ACCEPTED'
                """, Long.class, tenant)).isEqualTo(2);
    }

    @Test
    void validationErrorsAnswerInSpanish() throws Exception {
        mvc.perform(as(adminToken, order(0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("quantity"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("tiene que ser al menos 1"));

        mvc.perform(as(adminToken, get("/api/tenant/statistics/overview").param("days", "6")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("days"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("tiene que ser al menos 7"));
        mvc.perform(as(adminToken, get("/api/tenant/dashboard/sales-stock-trend").param("days", "0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].message").value("tiene que ser al menos 1"));
        mvc.perform(as(adminToken, get("/api/tenant/recommendations").param("size", "500")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].message").value("no puede ser mayor a 100"));
    }

    private MockHttpServletRequestBuilder order(int quantity) {
        return post("/api/tenant/recommendations/reorder")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"branchId\":%d,\"productId\":%d,\"quantity\":%d}".formatted(centro, milk, quantity));
    }

    private MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private String token(long userId) {
        return jwtService.issue(userRepository.findById(userId).orElseThrow());
    }
}
