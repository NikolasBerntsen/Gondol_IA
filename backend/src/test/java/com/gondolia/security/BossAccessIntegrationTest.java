package com.gondolia.security;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gondolia.domain.user.Role;
import com.gondolia.domain.user.UserRepository;
import com.gondolia.it.PostgresIntegrationTest;
import com.gondolia.it.TestData;
import java.time.LocalDate;
import java.util.List;
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
 * Regla de SPEC §3.3: <b>todo botón o enlace visible para un rol funciona para ese rol</b>. El jefe tiene la vista
 * resumida, pero desde su Inicio abre el inventario, la ficha de un producto, los vencimientos, las ventas, los
 * movimientos y las transferencias (solo lectura) y <b>decide</b> sobre las alertas y las recomendaciones de la IA.
 * Todo lo que escribe inventario, ventas o transferencias sigue siendo del administrador (y del empleado donde
 * corresponde): el jefe recibe 403 {@code FORBIDDEN}.
 * <p>
 * Sobre HTTP (MockMvc + JWT reales) contra PostgreSQL; cada prueba se revierte.
 */
@Transactional
@AutoConfigureMockMvc
// Misma configuración que PlatformApiIsolationIntegrationTest: Spring reutiliza ese contexto (y su pool chico)
// en vez de abrir otro contra la base compartida.
@TestPropertySource(properties = {"spring.datasource.hikari.maximum-pool-size=4",
        "spring.datasource.hikari.minimum-idle=0"})
class BossAccessIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;

    private TestData data;
    private long tenant;
    private long centro;
    private long norte;
    private long milk;
    private String milkBarcode;
    private long milkLot;
    private String bossToken;
    private String adminToken;
    private String employeeToken;
    private String cashierToken;

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc);
        tenant = data.tenant("Jefe con acceso");
        jdbc.update("insert into tenant_modules (tenant_id, module, enabled) values (?, 'MULTI_BRANCH', true)", tenant);
        centro = data.branch(tenant, "Centro", true);
        norte = data.branch(tenant, "Norte", true);
        milkBarcode = data.barcode();
        milk = data.product(tenant, milkBarcode, "Leche entera 1 L", "800", "1200");
        data.supplier(tenant, "La Serenísima");
        milkLot = data.lot(tenant, centro, milk, "L-100", "L100", LocalDate.now().plusDays(20), 30, "ACTIVE", 5);

        bossToken = token(data.user(tenant, Role.TENANT_BOSS, true).id());
        adminToken = token(data.user(tenant, Role.TENANT_ADMIN, true).id());
        employeeToken = token(data.user(tenant, Role.TENANT_EMPLOYEE, true, centro).id());
        cashierToken = token(data.user(tenant, Role.TENANT_CASHIER, true, centro).id());
    }

    // ------------------------------------------------------------------ ver

    @Test
    void theBossSeesTheInventoryAndTheProductDetail() throws Exception {
        mvc.perform(as(bossToken, get("/api/tenant/products").param("stockStatus", "ALL")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(milk))
                .andExpect(jsonPath("$.content[0].sellableStock").value(30));
        mvc.perform(as(bossToken, get("/api/tenant/products").param("stockStatus", "LOW")))
                .andExpect(status().isOk());
        mvc.perform(as(bossToken, get("/api/tenant/products/" + milk)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lots[0].id").value(milkLot));
        mvc.perform(as(bossToken, get("/api/tenant/products/by-barcode/" + milkBarcode)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(milk));
        mvc.perform(as(bossToken, get("/api/tenant/products/" + milk + "/movements")))
                .andExpect(status().isOk());
        mvc.perform(as(bossToken, get("/api/tenant/lots").param("productId", String.valueOf(milk))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(milkLot));
        mvc.perform(as(bossToken, get("/api/tenant/suppliers")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").exists());
    }

    @Test
    void theBossSeesExpirations() throws Exception {
        mvc.perform(as(bossToken, get("/api/tenant/expirations")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].lotId").value(milkLot));
        mvc.perform(as(bossToken, get("/api/tenant/expirations/summary")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expired").exists());
    }

    @Test
    void theBossSeesSalesMovementsAndTransfersHistory() throws Exception {
        String saleRef = field(mvc.perform(as(adminToken, post("/api/tenant/sales"))
                        .header(BranchAccessService.HEADER, centro)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":" + milk + ",\"quantity\":2}]}"))
                .andExpect(status().isOk()), "batchRef");
        String transferRef = field(mvc.perform(as(adminToken, post("/api/tenant/transfers"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromBranchId\":" + centro + ",\"toBranchId\":" + norte
                                + ",\"items\":[{\"lotId\":" + milkLot + ",\"quantity\":5}]}"))
                .andExpect(status().isOk()), "batchRef");

        mvc.perform(as(bossToken, get("/api/tenant/sales")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].batchRef").value(saleRef));
        mvc.perform(as(bossToken, get("/api/tenant/sales/" + saleRef)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sale.batchRef").value(saleRef))
                .andExpect(jsonPath("$.lines[0].lots[0].lotId").value(milkLot));
        mvc.perform(as(bossToken, get("/api/tenant/movements")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", greaterThanOrEqualTo(2))); // venta + transferencia
        mvc.perform(as(bossToken, get("/api/tenant/transfers")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].batchRef").value(transferRef));
        mvc.perform(as(bossToken, get("/api/tenant/transfers/" + transferRef)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchRef").value(transferRef));
    }

    // ------------------------------------------------------------------ decidir

    @Test
    void theBossDecidesOnRecommendations() throws Exception {
        long reorder = recommendation("REORDER", 24);
        long anomaly = recommendation("REVIEW_ANOMALY", null);

        mvc.perform(as(bossToken, post("/api/tenant/recommendations/" + reorder + "/accept"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":24}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendation.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.orderedQuantity").value(24));
        mvc.perform(as(bossToken, post("/api/tenant/recommendations/" + anomaly + "/discard"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Ya lo vi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendation.status").value("DISCARDED"));
    }

    @Test
    void theBossManagesAlerts() throws Exception {
        long first = alert("LOW_STOCK:1");
        long second = alert("LOW_STOCK:2");
        long third = alert("LOW_STOCK:3");

        mvc.perform(as(bossToken, post("/api/tenant/alerts/" + first + "/acknowledge")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"));
        mvc.perform(as(bossToken, post("/api/tenant/alerts/" + second + "/resolve")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));
        mvc.perform(as(bossToken, post("/api/tenant/alerts/" + third + "/dismiss")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISMISSED"));
    }

    @Test
    void theBossCanAskForAnAiRun() throws Exception {
        // Comercio sin sucursales activas: pasa la seguridad y el servicio responde 400 sin lanzar nada en segundo
        // plano (un análisis real escribiría en otra transacción y chocaría con la de la prueba).
        long empty = data.tenant("Sin sucursales activas");
        data.branch(empty, "Cerrada", false);
        String emptyBoss = token(data.user(empty, Role.TENANT_BOSS, true).id());

        mvc.perform(as(emptyBoss, post("/api/tenant/insights/run")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BRANCH_REQUIRED"));
        mvc.perform(as(employeeToken, post("/api/tenant/insights/run")))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ lo que el jefe no hace

    @Test
    void theBossStillCannotWriteInventorySalesOrTransfers() throws Exception {
        String product = """
                {"name":"Yerba 1 kg","costPrice":1000,"salePrice":1500,"minStock":2}""";
        List<MockHttpServletRequestBuilder> writes = List.of(
                post("/api/tenant/products").contentType(MediaType.APPLICATION_JSON).content(product),
                put("/api/tenant/products/" + milk).contentType(MediaType.APPLICATION_JSON).content(product),
                delete("/api/tenant/products/" + milk),
                post("/api/tenant/lots").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"branchId\":" + centro + ",\"productId\":" + milk + ",\"quantity\":5}"),
                put("/api/tenant/lots/" + milkLot).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lotNumber\":\"L-200\"}"),
                post("/api/tenant/suppliers").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Nuevo\"}"),
                post("/api/tenant/categories").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Nueva\"}"),
                post("/api/tenant/expirations/" + milkLot + "/discard"),
                post("/api/tenant/expirations/discard-expired"),
                post("/api/tenant/sales").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"branchId\":" + centro + ",\"items\":[{\"productId\":" + milk
                                + ",\"quantity\":1}]}"),
                get("/api/tenant/sales/import/template"),
                post("/api/tenant/movements/adjustments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lotId\":" + milkLot + ",\"type\":\"ADJUSTMENT_OUT\",\"quantity\":1}"),
                post("/api/tenant/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromBranchId\":" + centro + ",\"toBranchId\":" + norte
                                + ",\"items\":[{\"lotId\":" + milkLot + ",\"quantity\":1}]}"),
                get("/api/tenant/transfers/available-lots").param("branchId", String.valueOf(centro)),
                get("/api/tenant/users"),
                get("/api/tenant/settings"));

        for (MockHttpServletRequestBuilder write : writes) {
            mvc.perform(as(bossToken, write)).andExpect(status().isForbidden());
        }
        // Nada cambió.
        mvc.perform(as(bossToken, get("/api/tenant/products/" + milk)))
                .andExpect(jsonPath("$.sellableStock").value(30))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void theOtherRolesKeepTheirLimits() throws Exception {
        long rec = recommendation("REVIEW_ANOMALY", null);
        long alert = alert("LOW_STOCK:9");

        // El empleado sigue viendo inventario y vencimientos, pero no historiales ni decisiones.
        mvc.perform(as(employeeToken, get("/api/tenant/products"))).andExpect(status().isOk());
        mvc.perform(as(employeeToken, get("/api/tenant/expirations"))).andExpect(status().isOk());
        for (MockHttpServletRequestBuilder request : List.of(get("/api/tenant/sales"), get("/api/tenant/movements"),
                get("/api/tenant/transfers"), post("/api/tenant/recommendations/" + rec + "/discard"),
                post("/api/tenant/alerts/" + alert + "/resolve"))) {
            mvc.perform(as(employeeToken, request)).andExpect(status().isForbidden());
        }
        // El cajero solo usa el POS.
        for (MockHttpServletRequestBuilder request : List.of(get("/api/tenant/products"),
                get("/api/tenant/products/" + milk), get("/api/tenant/lots").param("productId", String.valueOf(milk)),
                get("/api/tenant/suppliers"), get("/api/tenant/expirations"), get("/api/tenant/expirations/summary"),
                get("/api/tenant/sales"))) {
            mvc.perform(as(cashierToken, request)).andExpect(status().isForbidden());
        }
    }

    // ------------------------------------------------------------------ apoyo

    private MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private String token(long userId) {
        return jwtService.issue(userRepository.findById(userId).orElseThrow());
    }

    private String field(org.springframework.test.web.servlet.ResultActions result, String name) throws Exception {
        JsonNode body = objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
        return body.get(name).asText();
    }

    private long recommendation(String type, Integer quantity) {
        Long id = jdbc.queryForObject("""
                insert into recommendations (tenant_id, branch_id, type, status, product_id, title, explanation,
                                             suggested_quantity, priority, confidence, dedupe_key)
                values (?, ?, ?, 'PENDING', ?, ?, 'Explicación de prueba', ?, 80, 0.75, ?)
                returning id
                """, Long.class, tenant, centro, type, milk, "Recomendación " + type, quantity,
                type + ":" + milk + ":" + data.suffix());
        return id == null ? 0 : id;
    }

    private long alert(String dedupeKey) {
        Long id = jdbc.queryForObject("""
                insert into alerts (tenant_id, branch_id, type, severity, status, product_id, title, message, dedupe_key)
                values (?, ?, 'LOW_STOCK', 'WARNING', 'OPEN', ?, 'Stock bajo', 'Quedan pocas unidades', ?)
                returning id
                """, Long.class, tenant, centro, milk, dedupeKey);
        return id == null ? 0 : id;
    }
}
