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
import com.gondolia.domain.tenant.TenantModule;
import com.gondolia.domain.tenant.TenantStatus;
import com.gondolia.insights.InsightsScheduler;
import com.gondolia.it.PostgresIntegrationTest;
import com.gondolia.realtime.RealtimePublisher;
import com.gondolia.recall.RecallMatchingService;
import com.gondolia.storage.AttachmentStorageService;
import jakarta.persistence.EntityManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Siembra el mundo demo completo en una base propia y recién creada ({@code <base de pruebas>_seed}, por defecto
 * {@code gondolia_it_seed}: la de las demás pruebas ya tiene comercios) arrancando la aplicación con {@code app.seed-demo=true}, y verifica el resultado con SQL y con la
 * API real: logins de la demo, aislamiento entre comercios y sucursales sobre los datos sembrados, el recall pendiente
 * del dulce de leche, el recall en vivo, la consistencia de stock y de los arqueos del POS y la idempotencia.
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
    private EntityManager entityManager;
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

    /**
     * Los módulos que quedan en la base son los documentados (SPEC §11, {@code docs/datos-demo.md} §2) y en el orden
     * de alta, que es el ID con el que se restauran desde la consola de dueños. Las filas de {@code tenant_modules}
     * las escribe el seeder a partir de los eventos {@code MODULE_ENABLED}, así que esto también verifica que los
     * eventos y el conjunto {@code modules} de cada comercio no se hayan separado.
     */
    @Test
    void enabledModulesMatchTheDocumentedMatrix() {
        Map<String, Set<TenantModule>> seeded = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                select t.name as name,
                       coalesce(string_agg(m.module, ',' order by m.module) filter (where m.enabled), '') as modules
                from tenants t left join tenant_modules m on m.tenant_id = t.id
                group by t.id, t.name order by t.id
                """)) {
            String modules = (String) row.get("modules");
            Set<TenantModule> enabled = EnumSet.noneOf(TenantModule.class);
            for (String module : modules.isEmpty() ? new String[0] : modules.split(",")) {
                enabled.add(TenantModule.valueOf(module));
            }
            seeded.put((String) row.get("name"), enabled);
        }
        assertThat(seeded).containsExactlyEntriesOf(DemoModuleMatrix.DOCUMENTED);
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

    // ------------------------------------------------------------------ recall pendiente del dulce de leche

    /**
     * El recall del dulce de leche queda como si el barrido en vivo lo hubiera detectado ayer y nadie lo hubiera
     * atendido (datos-demo §5.1): coincidencias abiertas, lotes en cuarentena con su stock, alertas abiertas,
     * notificaciones sin leer y ningún retiro.
     */
    @Test
    void dulceDeLecheRecallIsPendingWhereItWasDetected() {
        long recall = pendingRecallId();
        Map<String, Object> announcement = jdbc.queryForMap("""
                select status, severity, affected_tenants_count, recipients_count,
                       published_at > now() - interval '2 days' as recent
                from announcements where id = ?""", recall);
        assertThat(announcement).containsEntry("status", "PUBLISHED").containsEntry("severity", "CRITICAL")
                .containsEntry("affected_tenants_count", 2).containsEntry("recent", true);
        assertThat(((Number) announcement.get("recipients_count")).intValue()).isPositive();

        // Dos coincidencias sin confirmar ni resolver, con el lote en cuarentena y todo su stock.
        List<Map<String, Object>> matches = jdbc.queryForList("""
                select t.name as tenant, b.name as branch, m.status, m.quantity_at_match, m.acknowledged_at,
                       m.resolved_at, m.resolution, l.lot_number, l.status as lot_status, l.quantity as lot_quantity
                from recall_matches m join tenants t on t.id = m.tenant_id join branches b on b.id = m.branch_id
                join lots l on l.id = m.lot_id
                where m.announcement_id = ? order by t.name""", recall);
        assertThat(matches).extracting(row -> row.get("tenant") + " / " + row.get("branch"))
                .containsExactly("Almacén Don Pepe / Sucursal Principal", "Minimercado El Sol / Sucursal Centro");
        assertThat(matches).allSatisfy(row -> {
            assertThat(row).containsEntry("status", "OPEN").containsEntry("lot_number", "DV2603B")
                    .containsEntry("lot_status", "RECALLED");
            assertThat(row.get("acknowledged_at")).isNull();
            assertThat(row.get("resolved_at")).isNull();
            assertThat(row.get("resolution")).isNull();
            int atMatch = ((Number) row.get("quantity_at_match")).intValue();
            assertThat(atMatch).isPositive();
            assertThat(((Number) row.get("lot_quantity")).intValue()).isEqualTo(atMatch);
        });
        assertThat(count("select count(*) from recall_matches")).isEqualTo(2);

        // Alerta CRITICAL abierta por lote, con la misma clave y el mismo texto que abre RecallMatchingService.
        assertThat(count("select count(*) from alerts where type = 'RECALL_MATCH'")).isEqualTo(2);
        assertThat(count("""
                select count(*) from alerts a join recall_matches m
                  on m.announcement_id = a.announcement_id and m.lot_id = a.lot_id and m.branch_id = a.branch_id
                where a.type = 'RECALL_MATCH' and a.status = 'OPEN' and a.severity = 'CRITICAL'
                  and a.dedupe_key = 'RECALL:' || m.announcement_id || ':' || m.lot_id
                  and a.handled_by is null and a.resolved_at is null and a.created_at = m.matched_at
                  and a.message like '%Quedó en cuarentena y no se puede vender. Motivo: %'
                """)).isEqualTo(2);

        // Campana: la alerta sin leer, con el link a la coincidencia (no solo a la pantalla, que sigue la sucursal
        // del topbar), para cada usuario con acceso a la sucursal alcanzada y para nadie más.
        long recallAlerts = count("select count(*) from notifications where type = 'RECALL_ALERT'");
        assertThat(recallAlerts).isPositive();
        assertThat(count("""
                select count(*) from notifications n join recall_matches m on m.id = n.reference_id
                where n.type = 'RECALL_ALERT' and n.reference_type = 'RECALL_MATCH' and n.severity = 'CRITICAL'
                  and n.link = '/app/recalls?match=' || m.id and n.read_at is null and n.created_at = m.matched_at
                """)).isEqualTo(recallAlerts);
        Map<String, Long> alertsByUser = new LinkedHashMap<>();
        jdbc.query("""
                select u.email, count(n.id) as alerts from users u join tenants t on t.id = u.tenant_id
                left join notifications n on n.user_id = u.id and n.type = 'RECALL_ALERT'
                where t.name in ('Almacén Don Pepe', 'Minimercado El Sol') group by u.email
                """, (RowCallbackHandler) rs -> alertsByUser.put(rs.getString("email"), rs.getLong("alerts")));
        assertThat(alertsByUser).containsExactlyInAnyOrderEntriesOf(Map.ofEntries(
                Map.entry("jefe@donpepe.com", 1L), Map.entry("admin@donpepe.com", 1L),
                Map.entry("empleado@donpepe.com", 1L), Map.entry("cajero@donpepe.com", 1L),
                Map.entry("jefe@elsol.com", 1L), Map.entry("admin@elsol.com", 1L),
                Map.entry("empleado@elsol.com", 1L), Map.entry("cajero@elsol.com", 1L),
                Map.entry("empleado.echesortu@elsol.com", 0L), Map.entry("cajero.fisherton@elsol.com", 0L),
                Map.entry("cajera.tarde@elsol.com", 0L)));
        // Ninguno de ellos confirmó el diálogo ni abrió el aviso (los que entraron hoy lo cerraron con "Ver detalle",
        // que no confirma): el aviso del recall sigue sin leer.
        assertThat(count("""
                select count(*) from notifications n where n.type = 'ANNOUNCEMENT' and n.reference_id = ?
                  and n.read_at is null
                  and n.user_id in (select user_id from notifications where type = 'RECALL_ALERT')
                """, recall)).isEqualTo(recallAlerts);
        assertThat(count("""
                select count(*) from announcement_reads r where r.announcement_id = ?
                  and r.user_id in (select user_id from notifications where type = 'RECALL_ALERT')
                """, recall)).isZero();

        // Nadie lo retiró ni lo devolvió, y desde la coincidencia el producto no se vendió más en esas sucursales
        // (el POS GondolIA lo bloquea mientras haya unidades en cuarentena).
        assertThat(count("""
                select count(*) from stock_movements
                where type = 'RECALL_REMOVAL' or reason ilike '%recall #%'
                """)).isZero();
        assertThat(count("""
                select count(*) from stock_movements s join recall_matches m on m.lot_id = s.lot_id
                where s.occurred_at > m.matched_at
                """)).isZero();
        assertThat(count("""
                select count(*) from stock_movements s join recall_matches m
                  on m.branch_id = s.branch_id and m.product_id = s.product_id
                where s.type = 'SALE' and s.occurred_at > m.matched_at
                """)).isZero();
    }

    @Test
    void pendingRecallReachesOnlyTheUsersOfTheAffectedBranches() throws Exception {
        String barcode = DemoCatalog.byKey(DemoScenarios.PENDING_RECALL_PRODUCT).barcode();
        // Lo que pide el diálogo de Seguridad alimentaria al entrar: las coincidencias OPEN de todas sus sucursales.
        mockMvc.perform(get("/api/tenant/recall-matches").param("status", "OPEN")
                        .header("Authorization", login("cajero@elsol.com", DemoWorld.TENANT_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].branchName").value("Sucursal Centro"))
                .andExpect(jsonPath("$[0].lotNumber").value(DemoScenarios.PENDING_RECALL_LOT))
                .andExpect(jsonPath("$[0].barcode").value(barcode))
                .andExpect(jsonPath("$[0].status").value("OPEN"));
        // El control del recall en vivo (datos-demo §5.2) no tiene el lote: no le salta nada al entrar.
        mockMvc.perform(get("/api/tenant/recall-matches").param("status", "OPEN")
                        .header("Authorization", login("empleado.echesortu@elsol.com", DemoWorld.TENANT_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        // El Inicio de Don Pepe lo pide como urgente y el POS bloquea el producto (aunque haya otros lotes).
        mockMvc.perform(get("/api/tenant/dashboard/summary")
                        .header("Authorization", login("admin@donpepe.com", DemoWorld.TENANT_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openRecallMatchesCount").value(1));
        mockMvc.perform(get("/api/tenant/pos/products/lookup").param("code", barcode)
                        .header("Authorization", login("cajero@donpepe.com", DemoWorld.TENANT_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasRecalledStock").value(true))
                .andExpect(jsonPath("$.activeRecall.lotNumbers[0]").value(DemoScenarios.PENDING_RECALL_LOT));
    }

    /** Se resuelve en vivo como en la demo ("Entendido" → devolver al proveedor); la prueba lo revierte al final. */
    @Test
    void pendingRecallCanBeResolvedLive() throws Exception {
        TransactionStatus transaction = transactionManager.getTransaction(new DefaultTransactionDefinition());
        try {
            long match = id("""
                    select m.id from recall_matches m join tenants t on t.id = m.tenant_id
                    where t.name = 'Almacén Don Pepe'""");
            long lot = id("select lot_id from recall_matches where id = ?", match);
            long quantity = count("select quantity from lots where id = ?", lot);
            String admin = login("admin@donpepe.com", DemoWorld.TENANT_PASSWORD);
            mockMvc.perform(post("/api/tenant/recall-matches/" + match + "/acknowledge")
                            .header("Authorization", admin))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"));
            mockMvc.perform(post("/api/tenant/recall-matches/" + match + "/resolve").header("Authorization", admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("resolution", "RETURNED_TO_SUPPLIER",
                                    "note", "Lo retira el distribuidor"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("RESOLVED"))
                    .andExpect(jsonPath("$.currentQuantity").value(0))
                    .andExpect(jsonPath("$.acknowledgedByName").value("Marta Fernández"));
            entityManager.flush();
            assertThat(count("select quantity from lots where id = ?", lot)).isZero();
            assertThat(count("""
                    select count(*) from alerts where type = 'RECALL_MATCH' and lot_id = ? and status = 'RESOLVED'
                    """, lot)).isEqualTo(1);
            assertThat(count("""
                    select count(*) from stock_movements where lot_id = ? and type = 'ADJUSTMENT_OUT' and quantity = ?
                      and reason like 'Devolución al proveedor por recall #% · Lo retira el distribuidor'
                    """, lot, quantity)).isEqualTo(1);
        } finally {
            transactionManager.rollback(transaction);
        }
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

    /** La vista previa del recall en vivo que arma el dueño en la demo (datos-demo §5.2): 2 comercios y 2 lotes. */
    @Test
    void liveRecallPreviewShowsTwoStoresAndTwoLots() throws Exception {
        mockMvc.perform(post("/api/platform/announcements/recall-preview")
                        .header("Authorization", login("dueno@gondolia.app", DemoWorld.PLATFORM_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("barcode", "7791234500017",
                                "lotNumbers", List.of(DemoScenarios.LIVE_RECALL_LOT), "allLots", false))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedTenantsCount").value(2))
                .andExpect(jsonPath("$.affectedLotsCount").value(2));
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

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private long pendingRecallId() {
        return id("select id from announcements where kind = 'RECALL' and title like ?",
                "%lote " + DemoScenarios.PENDING_RECALL_LOT);
    }

    private long id(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        assertThat(value).isNotNull();
        return value;
    }
}
