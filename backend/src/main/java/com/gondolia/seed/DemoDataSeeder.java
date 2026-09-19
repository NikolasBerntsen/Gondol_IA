package com.gondolia.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gondolia.security.ApiKeyService;
import com.gondolia.seed.DemoWorldBuilder.Platform;
import com.gondolia.seed.DemoWorldBuilder.TenantRecord;
import com.gondolia.storage.AttachmentStorageService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Datos de demostración (SPEC §11, módulo G). Corre al arrancar, después del dueño inicial y del fixture de
 * desarrollo, solo si {@code app.seed-demo=true} y la base todavía no tiene comercios; así es idempotente: una vez
 * sembrado (o si ya hay comercios reales) no hace nada.
 * <p>
 * Todo se escribe en <strong>una sola transacción</strong> (si algo falla no queda un mundo a medias) y con escritura en
 * bloque ({@code COPY}), así los 180 días de historia de los comercios ricos entran en segundos, muy por debajo de la
 * ventana de salud del contenedor. Las alertas y las recomendaciones pendientes <em>no</em> se siembran: las generan
 * el motor de alertas y la IA al arrancar (módulo B) sobre estos datos.
 * <p>
 * El mundo (quién es quién, qué mostrar y el recall en vivo) está documentado en {@code docs/datos-demo.md}.
 */
@Slf4j
@Order(30)
@Component
@ConditionalOnProperty(name = "app.seed-demo", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final PasswordEncoder passwordEncoder;
    private final ApiKeyService apiKeyService;
    private final AttachmentStorageService attachmentStorageService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public DemoDataSeeder(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                          PasswordEncoder passwordEncoder, ApiKeyService apiKeyService,
                          AttachmentStorageService attachmentStorageService, ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setTimeout(600);
        this.passwordEncoder = passwordEncoder;
        this.apiKeyService = apiKeyService;
        this.attachmentStorageService = attachmentStorageService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        Long tenants = jdbc.queryForObject("select count(*) from tenants", Long.class);
        if (tenants != null && tenants > 0) {
            log.info("Datos demo: la base ya tiene {} comercios, no se siembra nada", tenants);
            return;
        }
        long started = System.nanoTime();
        log.info("Datos demo: sembrando el mundo de GondolIA (180 días de historia)…");
        Summary summary;
        try {
            summary = transaction.execute(status -> seed());
        } catch (RuntimeException e) {
            // La transacción se revirtió entera: la aplicación arranca igual (sin datos demo) y el error queda en el log.
            log.error("No se pudieron cargar los datos demo; la base quedó sin cambios", e);
            return;
        }
        long millis = (System.nanoTime() - started) / 1_000_000;
        lastDurationMillis = millis;
        if (summary != null) {
            log.info("Datos demo listos en {} ms: {} comercios, {} lotes, {} movimientos, {} tickets del POS, "
                            + "{} turnos de caja ({} filas en bloque)", millis, summary.tenants(), summary.lots(),
                    summary.movements(), summary.sales(), summary.sessions(), summary.bulkRows());
        }
    }

    private volatile long lastDurationMillis;

    /** Duración de la última siembra en milisegundos (0 si en este arranque no se sembró nada). */
    public long lastDurationMillis() {
        return lastDurationMillis;
    }

    /** Cantidades sembradas (para el log y las pruebas). */
    public record Summary(int tenants, int lots, int movements, int sales, int sessions, long bulkRows) {
    }

    /** Siembra el mundo completo en la transacción actual. Visible para las pruebas de integración. */
    Summary seed() {
        SeedJdbc db = new SeedJdbc(jdbc);
        DemoWorldBuilder world = new DemoWorldBuilder(db, passwordEncoder, apiKeyService, objectMapper, clock);
        DemoStories stories = new DemoStories(db, world, objectMapper, attachmentStorageService);

        Platform platform = world.platform();
        DemoStories.Announcements announcements = stories.announcements(platform);

        List<TenantRecord> tenants = new ArrayList<>();
        for (DemoWorld.TenantSpec spec : DemoWorld.tenants()) {
            tenants.add(world.tenant(spec, platform));
        }

        SimOutput out = new SimOutput();
        StoreSimulator simulator = new StoreSimulator(world.zone(), world.today(), world.now(), out);
        for (TenantRecord tenant : tenants) {
            if (tenant.spec.historyDays() <= 0) {
                continue;
            }
            simulator.simulate(world.simulationRun(tenant, announcements.oldRecallId()),
                    StoreSimulator.seedOf(tenant.spec.key(), world.today()));
        }
        world.writeSimulation(out);

        stories.recommendations(out);
        stories.recallMatches(tenants, out, announcements);
        for (TenantRecord tenant : tenants) {
            stories.importJob(tenant, out);
        }
        stories.announcementNotifications(tenants, announcements);
        stories.support(tenants, platform);

        return new Summary(tenants.size(), out.lots.size(), out.movements.size(), out.sales.size(),
                out.sessions.size(), db.copiedRows());
    }
}
