package com.gondolia.it;

import com.gondolia.realtime.RealtimePublisher;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Base de las pruebas de integración contra PostgreSQL real (Flyway + Hibernate validate + consultas nativas).
 * <p>
 * Solo corren con {@code -Dgondolia.it=true} (o la variable {@code GONDOLIA_IT=true}). Base por defecto:
 * {@code jdbc:postgresql://localhost:55432/gondolia_it} con {@code postgres}/{@code dev}; se cambia con
 * {@code -Dgondolia.it.db.url=... -Dgondolia.it.db.user=... -Dgondolia.it.db.password=...}. Ejemplo:
 * <pre>
 * docker exec gondolia-dev-pg createdb -U postgres gondolia_it
 * mvn test -Dgondolia.it=true
 * </pre>
 * {@link RealtimePublisher} se reemplaza por un mock para poder verificar los push sin broker.
 */
@SpringBootTest
@PostgresIntegrationTest.EnabledWhenRequested
public abstract class PostgresIntegrationTest {

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Inherited
    @EnabledIf("com.gondolia.it.PostgresIntegrationTest#integrationTestsEnabled")
    public @interface EnabledWhenRequested {
    }

    @MockitoBean
    protected RealtimePublisher realtimePublisher;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registerDatasource(registry);
    }

    public static void registerDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> setting("gondolia.it.db.url", "jdbc:postgresql://localhost:55432/gondolia_it"));
        registry.add("spring.datasource.username", () -> setting("gondolia.it.db.user", "postgres"));
        registry.add("spring.datasource.password", () -> setting("gondolia.it.db.password", "dev"));
        // Spring cachea un contexto por combinación de configuración: con el pool de producción (20)
        // la suite completa agota max_connections=100 del Postgres compartido.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> setting("gondolia.it.db.pool", "4"));
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
        registry.add("app.seed-demo", () -> "false");
        registry.add("app.dev-fixture", () -> "false");
    }

    public static boolean integrationTestsEnabled() {
        return "true".equalsIgnoreCase(System.getProperty("gondolia.it"))
                || "true".equalsIgnoreCase(System.getenv("GONDOLIA_IT"));
    }

    private static String setting(String property, String fallback) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(property.toUpperCase().replace('.', '_'));
        }
        return value == null || value.isBlank() ? fallback : value;
    }
}
