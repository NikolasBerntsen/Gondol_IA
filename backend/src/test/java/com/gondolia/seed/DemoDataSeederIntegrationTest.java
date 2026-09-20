package com.gondolia.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gondolia.domain.support.Attachment;
import com.gondolia.domain.support.AttachmentRepository;
import com.gondolia.domain.tenant.TenantStatus;
import com.gondolia.insights.InsightsScheduler;
import com.gondolia.it.PostgresIntegrationTest;
import com.gondolia.realtime.RealtimePublisher;
import com.gondolia.recall.RecallMatchingService;
import com.gondolia.storage.AttachmentStorageService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Siembra el mundo demo completo en una base propia y recién creada ({@code <base de pruebas>_seed}, por defecto
 * {@code gondolia_it_seed}: la de las demás pruebas ya tiene comercios) arrancando la aplicación con {@code app.seed-demo=true}, y verifica el resultado con SQL y con la
 * API real: logins de la demo, aislamiento entre comercios y sucursales sobre los datos sembrados, el recall en vivo,
 * la consistencia de stock y de los arqueos del POS y la idempotencia.
 * <p>
 * Corre con {@code mvn test -Dgondolia.it=true}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@PostgresIntegrationTest.EnabledWhenRequested
class DemoDataSeederIntegrationTest {

    @DynamicPropertySource
    static void freshDatabase(DynamicPropertyRegistry registry) throws Exception {
        String baseUrl = setting("gondolia.it.db.url", "jdbc:postgresql://localhost:55432/gondolia_it");
        String user = setting("gondolia.it.db.user", "postgres");
        String password = setting("gondolia.it.db.password", "dev");
        String serverUrl = baseUrl.substring(0, baseUrl.lastIndexOf('/') + 1);
        // Derivada de la base configurada: dos suites contra el mismo servidor no se borran la base entre sí.
        String baseName = baseUrl.substring(serverUrl.length());
        int params = baseName.indexOf('?');
        String database = (params < 0 ? baseName : baseName.substring(0, params)).replaceAll("[^A-Za-z0-9_]", "")
                + "_seed";
        try (Connection connection = DriverManager.getConnection(serverUrl + "postgres", user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + database);
        }
        Path storage = Files.createTempDirectory("gondolia-seed-it");
        registry.add("spring.datasource.url", () -> serverUrl + database);
        registry.add("spring.datasource.username", () -> user);
        registry.add("spring.datasource.password", () -> password);
        registry.add("app.seed-demo", () -> "true");
        registry.add("app.dev-fixture", () -> "false");
        registry.add("app.storage.dir", storage::toString);
        // Pool chico: la base de pruebas es compartida y cada contexto de Spring guarda sus conexiones.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "5");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
    }

    private static String setting(String property, String fallback) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(property.toUpperCase().replace('.', '_'));
        }
        return value == null || value.isBlank() ? fallback : value;
    }

    @MockitoBean
    private RealtimePublisher realtimePublisher;

    /** El análisis de IA al arrancar esperaría al servicio de IA: en esta prueba no hace falta. */
    @MockitoBean
    private InsightsScheduler insightsScheduler;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private DemoDataSeeder seeder;
    @Autowired
    private RecallMatchingService recallMatchingService;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private AttachmentRepository attachmentRepository;
    @Autowired
    private AttachmentStorageService attachmentStorageService;

    // ------------------------------------------------------------------ volumen e invariantes

    @Test
    void seedsTheWholeWorldQuicklyAndOnlyOnce() {
        assertThat(seeder.lastDurationMillis()).isPositive().isLessThan(240_000);
        long tenants = count("select count(*) from tenants");
        assertThat(tenants).isEqualTo(DemoWorld.tenants().size());
        assertThat(count("select count(*) from lots")).isGreaterThan(5_000);
        assertThat(count("select count(*) from stock_movements")).isGreaterThan(100_000);
        assertThat(count("select count(*) from pos_sales")).isGreaterThan(20_000);
        assertThat(count("select count(*) from users where role in ('PLATFORM_OWNER', 'SUPPORT_AGENT')"))
                .isEqualTo(4);

        seeder.run(null);
        assertThat(count("select count(*) from tenants")).isEqualTo(tenants);
    }

    @Test
    void platformTeamIncludingTheBootstrapOwnerPredatesEveryTenant() {
        // El dueño inicial lo crea BootstrapRunner al arrancar; la siembra le da la misma antigüedad que al resto.
        assertThat(jdbc.queryForObject("select full_name from users where email = 'dueno@gondolia.app'",
                String.class)).isEqualTo("Federico Almada");
        assertThat(count("""
                select count(distinct created_at) from users where role in ('PLATFORM_OWNER', 'SUPPORT_AGENT')
                """)).isEqualTo(1);
        assertThat(count("""
                select count(*) from users where role in ('PLATFORM_OWNER', 'SUPPORT_AGENT')
                  and created_at >= (select min(created_at) from tenants)
                """)).isZero();
        // Nadie actúa antes de su alta (p. ej. el dueño dando de alta Don Pepe hace 335 días).
        assertThat(count("""
                select count(*) from tenant_events e join users u on u.id = e.actor_user_id
                where e.created_at < u.created_at
                """)).isZero();
    }

    @Test
    void elSolSellsThroughTheFourSources() {
        List<String> sources = jdbc.queryForList("""
                select distinct m.source from stock_movements m join tenants t on t.id = m.tenant_id
                where t.name = 'Minimercado El Sol' and m.type = 'SALE'
                """, String.class);
        assertThat(sources).contains("POS_GONDOLIA", "POS", "CSV", "MANUAL");
    }

    @Test
    void lotQuantitiesMatchTheirMovements() {
        assertThat(count("""
                with m as (
                  select lot_id, sum(case when type in ('ENTRY', 'TRANSFER_IN', 'ADJUSTMENT_IN', 'SALE_VOID')
                                          then quantity else -quantity end) as net
                  from stock_movements where lot_id is not null group by lot_id)
                select count(*) from lots l left join m on m.lot_id = l.id
                where l.quantity <> coalesce(m.net, 0) or l.quantity < 0
                """)).isZero();
        // Nunca se vendió un lote vencido (el vencimiento se evalúa en la fecha de la venta).
        assertThat(count("""
                select count(*) from stock_movements m join lots l on l.id = m.lot_id
                where m.type = 'SALE' and l.expiry_date < (m.occurred_at at time zone 'America/Argentina/Buenos_Aires')::date
                """)).isZero();
        // Historia de 180 días en los comercios ricos.
        assertThat(count("""
                select count(distinct (m.occurred_at at time zone 'America/Argentina/Buenos_Aires')::date)
                from stock_movements m join tenants t on t.id = m.tenant_id
                where t.name = 'Minimercado El Sol' and m.type = 'SALE'
                """)).isGreaterThanOrEqualTo(180);
    }

    @Test
    void cashSessionsAndTicketsAreConsistent() {
        assertThat(count("""
                select count(*) from pos_sessions s
                where s.status = 'CLOSED' and s.expected_cash <> s.opening_cash
                  + coalesce((select sum(p.amount) from pos_payments p join pos_sales v on v.id = p.sale_id
                              where v.session_id = s.id and v.status = 'COMPLETED' and p.method = 'CASH'), 0)
                  - coalesce((select sum(v.change_amount) from pos_sales v
                              where v.session_id = s.id and v.status = 'COMPLETED'), 0)
                  + coalesce((select sum(c.amount) from pos_cash_movements c
                              where c.session_id = s.id and c.type = 'CASH_IN'), 0)
                  - coalesce((select sum(c.amount) from pos_cash_movements c
                              where c.session_id = s.id and c.type = 'CASH_OUT'), 0)
                """)).isZero();
        assertThat(count("""
                select count(*) from pos_sessions s where s.sales_count <> (select count(*) from pos_sales v
                  where v.session_id = s.id and v.status = 'COMPLETED')
                """)).isZero();
        assertThat(count("""
                select count(*) from pos_branch_counters c
                where c.last_number <> (select max(number) from pos_sales v where v.branch_id = c.branch_id)
                """)).isZero();
        assertThat(count("select count(*) from pos_sessions where status = 'OPEN'")).isPositive();
        assertThat(count("select count(*) from pos_sessions where cash_difference <> 0")).isPositive();
        assertThat(count("select count(*) from pos_sales where status = 'VOIDED'")).isPositive();
        assertThat(count("select count(*) from (select sale_id from pos_payments group by sale_id "
                + "having count(*) > 1) x")).isPositive();
        assertThat(count("""
                select count(*) from pos_sales v where v.status = 'VOIDED' and not exists (
                  select 1 from stock_movements m where m.tenant_id = v.tenant_id and m.batch_ref = v.batch_ref
                    and m.type = 'SALE_VOID')
                """)).isZero();
    }

    @Test
    void storiesArePresent() {
        assertThat(count("""
                select count(*) from recommendations where type = 'DISCOUNT' and status = 'ACCEPTED'
                  and outcome ->> 'unitsAfter7d' is not null
                """)).isGreaterThanOrEqualTo(6);
        assertThat(count("select count(*) from recall_matches where status = 'RESOLVED'")).isEqualTo(2);
        assertThat(count("select count(*) from lots where lot_number = 'DV2603B' and status = 'RECALLED' "
                + "and quantity = 0")).isEqualTo(2);
        // El link de la campana lleva a la coincidencia, no solo a la pantalla (que sigue la sucursal del topbar).
        assertThat(count("select count(*) from notifications where type = 'RECALL_ALERT'")).isPositive();
        assertThat(count("""
                select count(*) from notifications where type = 'RECALL_ALERT'
                  and link is distinct from '/app/recalls?match=' || reference_id
                """)).isZero();
        assertThat(count("select count(*) from announcements where kind = 'GENERAL' and status = 'PUBLISHED'"))
                .isGreaterThanOrEqualTo(3);
        assertThat(count("select count(*) from import_jobs where status = 'APPLIED'")).isEqualTo(1);
        assertThat(count("""
                select count(*) from import_job_rows r where r.status = 'IMPORTED'
                """)).isEqualTo(count("select count(*) from lots where source = 'IMPORT'"));
        assertThat(count("select count(*) from support_tickets where status = 'OPEN' and assigned_to is null"))
                .isPositive();
        assertThat(count("select count(*) from tenant_events where type = 'CANCELLED'")).isEqualTo(2);
        assertThat(count("select count(*) from tenants where status = 'DISABLED'")).isEqualTo(2);

        Long attachmentId = jdbc.queryForObject(
                "select attachment_id from support_messages where attachment_id is not null limit 1", Long.class);
        Attachment attachment = attachmentRepository.findById(attachmentId).orElseThrow();
        assertThat(attachment.getContentType()).isEqualTo("image/png");
        assertThat(attachmentStorageService.load(attachment).exists()).isTrue();
    }

    // ------------------------------------------------------------------ API real sobre los datos sembrados

    @Test
    void everyDemoLoginWorksAndBlockedTenantsAreRejected() throws Exception {
        for (DemoWorld.PlatformUser user : DemoWorld.PLATFORM_USERS) {
            login(user.email(), DemoWorld.PLATFORM_PASSWORD);
        }
        for (DemoWorld.TenantSpec tenant : DemoWorld.tenants()) {
            for (DemoWorld.UserSpec user : tenant.users()) {
                if (tenant.status() == TenantStatus.ACTIVE) {
                    login(user.email(), DemoWorld.TENANT_PASSWORD);
                } else {
                    mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                                    .content(credentials(user.email(), DemoWorld.TENANT_PASSWORD)))
                            .andExpect(status().isForbidden())
                            .andExpect(jsonPath("$.code").value("TENANT_" + tenant.status().name()));
                }
            }
        }
    }

    @Test
    void seededDataIsIsolatedByTenantAndBranch() throws Exception {
        long centro = id("select b.id from branches b join tenants t on t.id = b.tenant_id "
                + "where t.name = 'Minimercado El Sol' and b.name = 'Sucursal Centro'");
        long echesortu = id("select id from branches where name = 'Sucursal Echesortu'");
        long elSolProduct = id("select p.id from products p join tenants t on t.id = p.tenant_id "
                + "where t.name = 'Minimercado El Sol' and p.barcode = '7791234500017'");

        String employee = login("empleado.echesortu@elsol.com", DemoWorld.TENANT_PASSWORD);
        mockMvc.perform(get("/api/tenant/expirations").header("Authorization", employee)
                        .header("X-Branch-Id", centro))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("BRANCH_FORBIDDEN"));
        JsonNode own = json(mockMvc.perform(get("/api/tenant/expirations").header("Authorization", employee)
                .header("X-Branch-Id", echesortu)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
        assertThat(own.path("totalElements").asInt()).isPositive();
        own.path("content").forEach(row -> assertThat(row.path("branchId").asLong()).isEqualTo(echesortu));

        String otherTenant = login("admin@vidasana.com", DemoWorld.TENANT_PASSWORD);
        mockMvc.perform(get("/api/tenant/products/" + elSolProduct).header("Authorization", otherTenant))
                .andExpect(status().isNotFound());

        String cashier = login("cajero@elsol.com", DemoWorld.TENANT_PASSWORD);
        mockMvc.perform(get("/api/tenant/dashboard/summary").header("Authorization", cashier))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/tenant/pos/sessions/current").header("Authorization", cashier))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));

        String admin = login("admin@elsol.com", DemoWorld.TENANT_PASSWORD);
        mockMvc.perform(get("/api/tenant/dashboard/summary").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branchCount").value(3))
                .andExpect(jsonPath("$.productsCount").value(66));

        // Soporte ve la foto adjunta del ticket de Don Pepe; otro comercio no.
        long attachment = id("select attachment_id from support_messages where attachment_id is not null limit 1");
        mockMvc.perform(get("/api/attachments/" + attachment)
                        .header("Authorization", login("soporte@gondolia.app", DemoWorld.PLATFORM_PASSWORD)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/attachments/" + attachment).header("Authorization", admin))
                .andExpect(status().isNotFound());
    }

    @Test
    void liveRecallReachesDonPepeAndElSolFishertonOnly() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            long owner = id("select id from users where email = 'dueno@gondolia.app'");
            Long announcement = jdbc.queryForObject("""
                    insert into announcements (kind, severity, status, title, body, recall_product_name, recall_brand,
                                               recall_barcode, recall_all_lots, recall_reason, recall_instructions,
                                               created_by, published_at)
                    values ('RECALL', 'CRITICAL', 'PUBLISHED', 'Retiro de sopa de tomate', 'Prueba', 'Sopa de tomate',
                            'La Huerta', '7791234500017', false, 'Prueba', 'Retirar', ?, now())
                    returning id""", Long.class, owner);
            jdbc.update("insert into announcement_recall_lots (announcement_id, lot_number, lot_number_normalized) "
                    + "values (?, 'L2409A', 'L2409A')", announcement);
            recallMatchingService.matchAnnouncement(announcement);
            List<Map<String, Object>> matches = jdbc.queryForList("""
                    select t.name as tenant, b.name as branch from recall_matches m
                    join tenants t on t.id = m.tenant_id join branches b on b.id = m.branch_id
                    where m.announcement_id = ? order by t.name""", announcement);
            assertThat(matches).extracting(row -> row.get("tenant") + " / " + row.get("branch"))
                    .containsExactly("Almacén Don Pepe / Sucursal Principal",
                            "Minimercado El Sol / Sucursal Fisherton");
            status.setRollbackOnly();
        });
    }

    // ------------------------------------------------------------------ utilidades

    private String login(String email, String password) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + json(body).path("token").asText();
    }

    private String credentials(String email, String password) throws Exception {
        return objectMapper.writeValueAsString(Map.of("email", email, "password", password));
    }

    private JsonNode json(String body) throws Exception {
        return objectMapper.readTree(body);
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private long id(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        assertThat(value).isNotNull();
        return value;
    }
}
