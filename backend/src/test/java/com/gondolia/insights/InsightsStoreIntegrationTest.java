package com.gondolia.insights;

import static org.assertj.core.api.Assertions.assertThat;

import com.gondolia.ai.dto.AnalyzeResponse;
import com.gondolia.ai.dto.ProductInput;
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
import java.util.stream.IntStream;
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
    private StockoutCalendar stockoutCalendar;
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

    @Test
    void theRequestToTheAiCarriesTheDaysWithoutStock() {
        // Leche que vendía 5 u/día: el último lote entró hace 10 días y se terminó hace 7 (el proveedor no volvió).
        TestData data = new TestData(jdbc);
        long milk = data.product(tenant, data.barcode(), "Leche descremada 1 L", "980", "1450");
        long milkLot = data.lot(tenant, centro, milk, "LD1", "LD1", LocalDate.now(clock).plusDays(20), 20, "ACTIVE",
                10);
        movement(milk, milkLot, "ENTRY", 20, 10);
        for (int daysAgo = 10; daysAgo >= 7; daysAgo--) {
            movement(milk, milkLot, "SALE", 5, daysAgo);
        }
        jdbc.update("update lots set quantity = 0, status = 'DEPLETED' where id = ?", milkLot);

        AnalyzeInput input = store.buildInput(tenant, centro, "Sucursal Centro");

        LocalDate today = LocalDate.now(clock);
        List<LocalDate> expected = IntStream.rangeClosed(1, 6)
                .mapToObj(daysAgo -> today.minusDays(7 - daysAgo)).toList();
        ProductInput milkInput = input.request().products().stream().filter(item -> item.productId() == milk)
                .findFirst().orElseThrow();
        assertThat(milkInput.sellableStock()).isZero();
        assertThat(milkInput.stockoutDays()).containsExactlyElementsOf(expected);
        // El pan (siempre con stock) no informa faltantes.
        assertThat(input.request().products().stream().filter(item -> item.productId() == product)
                .findFirst().orElseThrow().stockoutDays()).isEmpty();

        // La ficha del producto usa el mismo cálculo, filtrado por producto.
        Map<Long, List<LocalDate>> byProduct = stockoutCalendar.stockoutDays(tenant, centro, milk, today.minusDays(89), today.minusDays(1),
                clock.getZone(), Map.of(milk, Set.of(today.minusDays(10), today.minusDays(9), today.minusDays(8),
                        today.minusDays(7))));
        assertThat(byProduct).containsOnlyKeys(milk);
        assertThat(byProduct.get(milk)).containsExactlyElementsOf(expected);
    }

    // ------------------------------------------------------------------ apoyo

    private void movement(long productId, long lotId, String type, int quantity, int daysAgo) {
        jdbc.update("""
                insert into stock_movements (tenant_id, branch_id, product_id, lot_id, type, quantity, unit_price,
                                             total_amount, source, batch_ref, occurred_at)
                values (?, ?, ?, ?, ?, ?, 1450, ? * 1450, 'MANUAL', 'S-TEST', now() - make_interval(days => ?))
                """, tenant, centro, productId, lotId, type, quantity, quantity, daysAgo);
    }

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
