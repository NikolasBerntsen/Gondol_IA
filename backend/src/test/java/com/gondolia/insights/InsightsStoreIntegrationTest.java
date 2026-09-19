package com.gondolia.insights;

import static org.assertj.core.api.Assertions.assertThat;

import com.gondolia.ai.dto.AnalyzeResponse;
import com.gondolia.ai.dto.RecommendationResult;
import com.gondolia.analytics.BranchScopeService.Scope;
import com.gondolia.domain.ai.AiRun;
import com.gondolia.domain.ai.AiRunTrigger;
import com.gondolia.domain.ai.RecommendationType;
import com.gondolia.domain.user.Role;
import com.gondolia.insights.InsightsStore.AnalyzeInput;
import com.gondolia.insights.InsightsStore.ApplyResult;
import com.gondolia.it.PostgresIntegrationTest;
import com.gondolia.it.TestData;
import com.gondolia.security.AuthUser;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Upsert de las recomendaciones de la IA contra PostgreSQL real. Sin {@code @Transactional}: {@link InsightsStore}
 * abre sus propias transacciones ({@code REQUIRES_NEW}), así que los datos se confirman y se borran al final.
 */
@SpringBootTest
@PostgresIntegrationTest.EnabledWhenRequested
class InsightsStoreIntegrationTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        PostgresIntegrationTest.registerDatasource(registry);
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private InsightsStore store;
    @Autowired
    private RecommendationService recommendationService;
    @Autowired
    private Clock clock;

    private long tenant;
    private long centro;
    private long norte;
    private long product;
    private long lot;
    private AuthUser admin;
    private Scope scope;

    @BeforeEach
    void setUp() {
        TestData data = new TestData(jdbc);
        tenant = data.tenant("Silencio de descartes");
        centro = data.branch(tenant, "Sucursal Centro", true);
        norte = data.branch(tenant, "Sucursal Norte", true);
        admin = data.user(tenant, Role.TENANT_ADMIN, true);
        product = data.product(tenant, data.barcode(), "Pan lactal blanco", "900", "1500");
        lot = data.lot(tenant, centro, product, "DT257", "DT257", LocalDate.now(clock).plusDays(4), 20, "ACTIVE", 3);
        scope = new Scope(List.of(centro, norte), Map.of(centro, "Sucursal Centro", norte, "Sucursal Norte"), true);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(admin, null, admin.authorities()));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(
                new MockHttpServletRequest("POST", "/api/tenant/recommendations")));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        jdbc.update("delete from tenants where id = ?", tenant);
    }

    @Test
    void aDiscardedRecommendationIsNotSuggestedAgainDuringTheQuietPeriod() {
        ApplyResult first = apply(centro, discount(10));
        assertThat(first.recommendationsCreated()).isEqualTo(1);
        long id = pendingIds(centro).getFirst();

        recommendationService.discard(tenant, id, admin.id(), scope, "No lo quiero rebajar");

        // El análisis siguiente (un "Recalcular IA" 30 s después) vuelve a sugerir el mismo lote: no se recrea.
        ApplyResult second = apply(centro, discount(10));
        assertThat(second.recommendationsCreated()).isZero();
        assertThat(pendingIds(centro)).isEmpty();
        assertThat(statusOf(id)).isEqualTo("DISCARDED");

        // El silencio es por sucursal: la misma clave en otra sucursal sí se crea.
        assertThat(apply(norte, discount(10)).recommendationsCreated()).isEqualTo(1);

        // Pasado el período, si la situación sigue, la IA la vuelve a proponer.
        jdbc.update("update recommendations set decided_at = ? where id = ?",
                Timestamp.from(Instant.now(clock).minusSeconds((InsightsStore.DISCARD_QUIET_DAYS * 24L + 1) * 3600)),
                id);
        ApplyResult later = apply(centro, discount(15));
        assertThat(later.recommendationsCreated()).isEqualTo(1);
        assertThat(pendingIds(centro)).hasSize(1).doesNotContain(id);
    }

    @Test
    void anAcceptedRecommendationDoesNotSilenceTheNextSuggestion() {
        apply(centro, discount(10));
        long id = pendingIds(centro).getFirst();
        jdbc.update("update recommendations set status = 'ACCEPTED', decided_at = now() where id = ?", id);

        assertThat(apply(centro, discount(15)).recommendationsCreated()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ apoyo

    private ApplyResult apply(long branchId, RecommendationResult result) {
        AiRun run = store.startRun(tenant, branchId, AiRunTrigger.MANUAL);
        AnalyzeInput input = new AnalyzeInput(null, Set.of(product), Set.of(lot));
        return store.applyResults(run, new AnalyzeResponse("test", Instant.now(clock), List.of(), List.of(result),
                null), input);
    }

    private RecommendationResult discount(int pct) {
        return new RecommendationResult(RecommendationType.DISCOUNT, product, lot, 80, 0.8,
                "Aplicar %d%% de descuento a Pan lactal blanco (lote DT257)".formatted(pct), "Explicación de prueba",
                null, BigDecimal.valueOf(pct), LocalDate.now(clock), new BigDecimal("1000"), "DISCOUNT:" + lot);
    }

    private List<Long> pendingIds(long branchId) {
        return jdbc.queryForList("select id from recommendations where branch_id = ? and status = 'PENDING'",
                Long.class, branchId);
    }

    private String statusOf(long id) {
        return jdbc.queryForObject("select status from recommendations where id = ?", String.class, id);
    }
}
