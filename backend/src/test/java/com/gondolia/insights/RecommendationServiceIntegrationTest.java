package com.gondolia.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gondolia.analytics.BranchScopeService.Scope;
import com.gondolia.analytics.DashboardService;
import com.gondolia.analytics.StatisticsService;
import com.gondolia.analytics.dto.ReorderRow;
import com.gondolia.common.error.ApiException;
import com.gondolia.domain.ai.RecommendationStatus;
import com.gondolia.domain.ai.RecommendationType;
import com.gondolia.domain.inventory.Lot;
import com.gondolia.domain.inventory.LotRepository;
import com.gondolia.domain.user.Role;
import com.gondolia.insights.RecommendationService.AcceptRequest;
import com.gondolia.insights.RecommendationService.RecommendationQuery;
import com.gondolia.insights.RecommendationService.RestockRequest;
import com.gondolia.insights.dto.InsightsSummaryDto;
import com.gondolia.insights.dto.RecommendationDecisionDto;
import com.gondolia.insights.dto.RecommendationDto;
import com.gondolia.it.PostgresIntegrationTest;
import com.gondolia.it.TestData;
import com.gondolia.security.AuthUser;
import com.gondolia.security.BranchAccessService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Decisiones sobre las recomendaciones de la IA (SPEC §6.5) contra PostgreSQL real: aceptar un descuento pone el
 * lote en liquidación, aceptar un retiro descarta el vencido, aceptar una reposición arma el pedido, y la medición
 * a los 7 días deja el {@code outcome} listo para volver como feedback.
 */
@SpringBootTest
@Transactional
@PostgresIntegrationTest.EnabledWhenRequested
class RecommendationServiceIntegrationTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        PostgresIntegrationTest.registerDatasource(registry);
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RecommendationService recommendationService;
    @Autowired
    private RecommendationOutcomeJob outcomeJob;
    @Autowired
    private LotRepository lotRepository;
    @Autowired
    private DashboardService dashboardService;
    @Autowired
    private StatisticsService statisticsService;
    @Autowired
    private InsightsService insightsService;
    @Autowired
    private Clock clock;

    private TestData data;
    private long tenant;
    private long otherTenant;
    private long centro;
    private long norte;
    private long otherBranch;
    private long yogur;
    private long yerba;
    private long yogurLot;
    private long expiredLot;
    private AuthUser admin;
    private AuthUser otherAdmin;

    private Scope scopeAll;
    private Scope otherScope;

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc);
        tenant = data.tenant("Recomendaciones");
        otherTenant = data.tenant("Recomendaciones ajenas");
        centro = data.branch(tenant, "Sucursal Centro", true);
        norte = data.branch(tenant, "Sucursal Norte", true);
        otherBranch = data.branch(otherTenant, "Sucursal Ajena", true);
        admin = data.user(tenant, Role.TENANT_ADMIN, true);
        otherAdmin = data.user(otherTenant, Role.TENANT_ADMIN, true);

        long supplier = data.supplier(tenant, "Distribuidora de prueba");
        jdbc.update("update suppliers set phone = '011 4555-0101', contact_name = 'Marta' where id = ?", supplier);
        yogur = data.product(tenant, data.barcode(), "Yogur bebible", "800", "1400");
        yerba = data.product(tenant, data.barcode(), "Yerba mate", "3000", "5000");
        jdbc.update("update products set supplier_id = ?, brand = 'Vaquita' where id = ?", supplier, yerba);

        LocalDate today = LocalDate.now(clock);
        yogurLot = data.lot(tenant, centro, yogur, "Y1", "Y1", today.plusDays(3), 20, "ACTIVE", 5);
        expiredLot = data.lot(tenant, centro, yogur, "Y0", "Y0", today.minusDays(2), 9, "ACTIVE", 40);

        scopeAll = new Scope(List.of(centro, norte), Map.of(centro, "Sucursal Centro", norte, "Sucursal Norte"), true);
        otherScope = new Scope(List.of(otherBranch), Map.of(otherBranch, "Sucursal Ajena"), false);
        as(admin, null);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    // ------------------------------------------------------------------ descuento

    @Test
    void acceptingADiscountPutsTheLotOnSaleFirst() {
        long id = recommendation(RecommendationType.DISCOUNT, centro, yogur, yogurLot, "DISCOUNT:" + yogurLot,
                null, new BigDecimal("20"));

        RecommendationDecisionDto decision = recommendationService.accept(tenant, id, admin.id(), scopeAll,
                new AcceptRequest("Vence pronto", null, null));

        assertThat(decision.appliedDiscountPct()).isEqualByComparingTo("20");
        assertThat(decision.message()).contains("20%");
        assertThat(decision.recommendation().status()).isEqualTo(RecommendationStatus.ACCEPTED);
        assertThat(decision.recommendation().decisionNote()).isEqualTo("Vence pronto");
        assertThat(decision.recommendation().decidedByName()).isNotBlank();

        Lot lot = lotRepository.findById(yogurLot).orElseThrow();
        assertThat(lot.getDiscountPct()).isEqualByComparingTo("20");
        assertThat(lot.getDiscountStartedAt()).isNotNull();

        // Queda la foto de las ventas previas para medir el resultado.
        assertThat(decision.recommendation().outcome().get("appliedDiscountPct").decimalValue())
                .isEqualByComparingTo("20");
        assertThat(decision.recommendation().outcome().hasNonNull("unitsBefore7d")).isTrue();
    }

    @Test
    void theAdministratorCanChooseAnotherDiscountWithinTheConfiguredCap() {
        long id = recommendation(RecommendationType.DISCOUNT, centro, yogur, yogurLot, "DISCOUNT:" + yogurLot,
                null, new BigDecimal("30"));

        RecommendationDecisionDto decision = recommendationService.accept(tenant, id, admin.id(), scopeAll,
                new AcceptRequest(null, null, 15));

        assertThat(decision.appliedDiscountPct()).isEqualByComparingTo("15");
        assertThat(lotRepository.findById(yogurLot).orElseThrow().getDiscountPct()).isEqualByComparingTo("15");
    }

    @Test
    void aDiscountOverTheCapIsRejected() {
        jdbc.update("update tenant_settings set max_discount_pct = 25 where tenant_id = ?", tenant);
        long id = recommendation(RecommendationType.DISCOUNT, centro, yogur, yogurLot, "DISCOUNT:" + yogurLot,
                null, new BigDecimal("20"));

        assertThatThrownBy(() -> recommendationService.accept(tenant, id, admin.id(), scopeAll,
                new AcceptRequest(null, null, 40)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(400);
                    assertThat(ex.getMessage()).contains("25%");
                });
        assertThat(lotRepository.findById(yogurLot).orElseThrow().getDiscountPct()).isNull();
    }

    // ------------------------------------------------------------------ retiro de vencidos

    @Test
    void acceptingARemovalDiscardsTheExpiredUnits() {
        long id = recommendation(RecommendationType.REMOVE_EXPIRED, centro, yogur, expiredLot,
                "REMOVE_EXPIRED:" + expiredLot, 9, null);

        RecommendationDecisionDto decision = recommendationService.accept(tenant, id, admin.id(), scopeAll,
                new AcceptRequest(null, null, null));

        assertThat(decision.discardedQuantity()).isEqualTo(9);
        Lot lot = lotRepository.findById(expiredLot).orElseThrow();
        assertThat(lot.getQuantity()).isZero();
        assertThat(lot.getStatus().name()).isEqualTo("EXPIRED_DISCARDED");
        assertThat(jdbc.queryForObject("""
                select count(*) from stock_movements where lot_id = ? and type = 'WASTE_EXPIRED'
                """, Long.class, expiredLot)).isEqualTo(1);
    }

    @Test
    void discardingMoreThanTheRemainderIsRejected() {
        long id = recommendation(RecommendationType.REMOVE_EXPIRED, centro, yogur, expiredLot,
                "REMOVE_EXPIRED:" + expiredLot, 9, null);

        assertThatThrownBy(() -> recommendationService.accept(tenant, id, admin.id(), scopeAll,
                new AcceptRequest(null, 50, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus().value()).isEqualTo(409));
    }

    // ------------------------------------------------------------------ reposición

    @Test
    void acceptingAReorderBuildsTheWhatsappOrder() {
        long id = recommendation(RecommendationType.REORDER, centro, yerba, null, "REORDER:" + yerba, 30, null);

        RecommendationDecisionDto decision = recommendationService.accept(tenant, id, admin.id(), scopeAll,
                new AcceptRequest(null, 40, null));

        assertThat(decision.orderedQuantity()).isEqualTo(40);
        assertThat(decision.whatsappText())
                .contains("Marta")
                .contains("Yerba mate")
                .contains("40 unidades")
                .contains("Sucursal Centro");
        // El 0 de larga distancia se saca y se agrega el código de país: wa.me lo necesita así.
        assertThat(decision.whatsappUrl()).startsWith("https://wa.me/541145550101?text=");
        assertThat(decision.message()).contains("Distribuidora de prueba");
    }

    @Test
    void comprarFromTheDashboardIsRecordedAndShownUntilTheGoodsArrive() {
        // Yerba en Centro: 2 u. y mínimo 10, sin sugerencia pendiente de la IA.
        jdbc.update("update products set min_stock = 10 where id = ?", yerba);
        long yerbaLot = data.lot(tenant, centro, yerba, "M1", "M1", null, 2, "ACTIVE", 10);

        RecommendationDecisionDto decision = recommendationService.orderRestock(tenant, admin.id(), scopeAll,
                new RestockRequest(centro, yerba, 25, null));

        assertThat(decision.orderedQuantity()).isEqualTo(25);
        assertThat(decision.whatsappText()).contains("Yerba mate").contains("25 unidades");
        assertThat(decision.recommendation().type()).isEqualTo(RecommendationType.REORDER);
        assertThat(decision.recommendation().status()).isEqualTo(RecommendationStatus.ACCEPTED);
        assertThat(decision.recommendation().decidedByName()).isNotBlank();
        assertThat(decision.recommendation().explanation()).contains("2 u.").contains("10 u.");

        // Recargar el Inicio no lo pierde: la fila lo muestra como pedido.
        ReorderRow row = yerbaRow();
        assertThat(row.orderedQuantity()).isEqualTo(25);
        assertThat(row.orderedAt()).isNotNull();

        // Es un pedido anotado a mano, no una sugerencia de la IA: no cuenta en la tasa de aceptación.
        assertThat(statisticsService.overview(tenant, scopeAll, 30).ai().recommendations().total()).isZero();

        // Entra la mercadería: la fila vuelve a ofrecer "Comprar".
        jdbc.update("""
                insert into stock_movements (tenant_id, branch_id, product_id, lot_id, type, quantity, source,
                                             batch_ref, occurred_at)
                values (?, ?, ?, ?, 'ENTRY', 5, 'MANUAL', 'E-TEST',
                        (select decided_at from recommendations where id = ?) + interval '1 minute')
                """, tenant, centro, yerba, yerbaLot, decision.recommendation().id());
        assertThat(yerbaRow().orderedQuantity()).isNull();
    }

    @Test
    void comprarAcceptsThePendingReorderOfTheAi() {
        long pending = recommendation(RecommendationType.REORDER, centro, yerba, null, "REORDER:" + yerba, 30, null);

        RecommendationDecisionDto decision = recommendationService.orderRestock(tenant, admin.id(), scopeAll,
                new RestockRequest(centro, yerba, 40, "Pedido del lunes"));

        assertThat(decision.recommendation().id()).isEqualTo(pending);
        assertThat(decision.recommendation().status()).isEqualTo(RecommendationStatus.ACCEPTED);
        assertThat(decision.orderedQuantity()).isEqualTo(40);
        assertThat(jdbc.queryForObject("select count(*) from recommendations where tenant_id = ? and type = 'REORDER'",
                Long.class, tenant)).isEqualTo(1);
    }

    @Test
    void comprarChecksTheBranchAccessAndTheProductTenant() {
        AuthUser employee = data.user(tenant, Role.TENANT_EMPLOYEE, true, norte);
        as(employee, null);
        assertThatThrownBy(() -> recommendationService.orderRestock(tenant, employee.id(), scopeAll,
                new RestockRequest(centro, yerba, 5, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("BRANCH_FORBIDDEN"));

        as(otherAdmin, null);
        assertThatThrownBy(() -> recommendationService.orderRestock(otherTenant, otherAdmin.id(), otherScope,
                new RestockRequest(otherBranch, yerba, 5, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus().value()).isEqualTo(404));
    }

    // ------------------------------------------------------------------ resumen de la IA

    @Test
    void lastRunAtIsTheLastOkRunEvenWhileANewOneIsRunning() {
        Instant finished = jdbc.queryForObject("""
                insert into ai_runs (tenant_id, branch_id, status, trigger_type, started_at, finished_at)
                values (?, ?, 'OK', 'SCHEDULED', now() - interval '2 hours', now() - interval '2 hours' + interval '5 seconds')
                returning finished_at
                """, java.sql.Timestamp.class, tenant, centro).toInstant();
        jdbc.update("""
                insert into ai_runs (tenant_id, branch_id, status, trigger_type, started_at)
                values (?, ?, 'RUNNING', 'MANUAL', now())
                """, tenant, centro);

        InsightsSummaryDto summary = insightsService.summary(tenant, scopeAll);

        assertThat(summary.running()).isTrue();
        assertThat(summary.lastRunAt()).isEqualTo(finished);
    }

    // ------------------------------------------------------------------ listado y descartes

    @Test
    void discardKeepsTheNoteAndCannotBeDecidedTwice() {
        long id = recommendation(RecommendationType.REVIEW_ANOMALY, centro, yerba, null, "REVIEW_ANOMALY:" + yerba,
                null, null);

        RecommendationDecisionDto decision = recommendationService.discard(tenant, id, admin.id(), scopeAll,
                "Fue una promo puntual");
        assertThat(decision.recommendation().status()).isEqualTo(RecommendationStatus.DISCARDED);
        assertThat(decision.recommendation().decisionNote()).isEqualTo("Fue una promo puntual");

        assertThatThrownBy(() -> recommendationService.accept(tenant, id, admin.id(), scopeAll,
                new AcceptRequest(null, null, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus().value()).isEqualTo(409));
    }

    @Test
    void theListRespectsTheBranchScopeAndTheFilters() {
        recommendation(RecommendationType.DISCOUNT, centro, yogur, yogurLot, "DISCOUNT:" + yogurLot, null,
                new BigDecimal("20"));
        recommendation(RecommendationType.REORDER, norte, yerba, null, "REORDER:" + yerba, 12, null);

        assertThat(recommendationService.list(tenant, scopeAll,
                new RecommendationQuery(RecommendationStatus.PENDING, null, null, null, 0, 20)).totalElements())
                .isEqualTo(2);

        Scope soloCentro = new Scope(List.of(centro), Map.of(centro, "Sucursal Centro"), false);
        var soloCentroPage = recommendationService.list(tenant, soloCentro,
                new RecommendationQuery(RecommendationStatus.PENDING, null, null, null, 0, 20));
        assertThat(soloCentroPage.totalElements()).isEqualTo(1);
        assertThat(soloCentroPage.content().getFirst().branchName()).isEqualTo("Sucursal Centro");

        assertThat(recommendationService.list(tenant, scopeAll,
                new RecommendationQuery(null, RecommendationType.REORDER, null, null, 0, 20))
                .content()).extracting(RecommendationDto::type).containsOnly(RecommendationType.REORDER);

        assertThat(recommendationService.pending(tenant, scopeAll, 5)).hasSize(2);
    }

    // ------------------------------------------------------------------ aislamiento

    @Test
    void anotherTenantCannotSeeOrDecideTheseRecommendations() {
        long id = recommendation(RecommendationType.DISCOUNT, centro, yogur, yogurLot, "DISCOUNT:" + yogurLot, null,
                new BigDecimal("20"));

        as(otherAdmin, null);

        assertThat(recommendationService.list(otherTenant, otherScope,
                new RecommendationQuery(null, null, null, null, 0, 20)).totalElements()).isZero();
        assertThat(recommendationService.pending(otherTenant, otherScope, 5)).isEmpty();

        assertThatThrownBy(() -> recommendationService.accept(otherTenant, id, otherAdmin.id(), otherScope,
                new AcceptRequest(null, null, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus().value()).isEqualTo(404));
        assertThatThrownBy(() -> recommendationService.discard(otherTenant, id, otherAdmin.id(), otherScope, null))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus().value()).isEqualTo(404));

        // El lote del otro comercio sigue sin descuento.
        assertThat(lotRepository.findById(yogurLot).orElseThrow().getDiscountPct()).isNull();
    }

    @Test
    void anEmployeeWithoutAccessToTheBranchCannotDecide() {
        long id = recommendation(RecommendationType.DISCOUNT, centro, yogur, yogurLot, "DISCOUNT:" + yogurLot, null,
                new BigDecimal("20"));
        AuthUser employee = data.user(tenant, Role.TENANT_EMPLOYEE, true, norte);
        as(employee, null);

        assertThatThrownBy(() -> recommendationService.accept(tenant, id, employee.id(), scopeAll,
                new AcceptRequest(null, null, null)))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("BRANCH_FORBIDDEN"));
    }

    // ------------------------------------------------------------------ medición del resultado

    @Test
    void theDailyJobMeasuresTheLiftOfAnAcceptedDiscount() {
        long id = recommendation(RecommendationType.DISCOUNT, centro, yogur, yogurLot, "DISCOUNT:" + yogurLot, null,
                new BigDecimal("20"));
        // Descuento aceptado hace 8 días: ya cumplió la ventana de medición.
        jdbc.update("update recommendations set status = 'ACCEPTED', decided_by = ?,"
                + " decided_at = now() - make_interval(days => 8) where id = ?", admin.id(), id);
        salesOn(12, 10);    // semana previa a la decisión
        salesOn(5, 25);     // semana posterior, ya con el lote en liquidación

        assertThat(outcomeJob.measure(id)).isTrue();

        var outcome = recommendationService.byId(tenant, id, scopeAll).outcome();
        assertThat(outcome.get("unitsBefore7d").asInt()).isEqualTo(10);
        assertThat(outcome.get("unitsAfter7d").asInt()).isEqualTo(25);
        assertThat(outcome.get("lift").decimalValue()).isEqualByComparingTo("2.5");
        assertThat(outcome.get("lotUnitsRemaining").asInt()).isEqualTo(20);
        assertThat(outcome.get("lotUnitsSold").asInt()).isNotNegative();
        assertThat(outcome.hasNonNull("measuredAt")).isTrue();

        // Ya medida, el barrido no la vuelve a tocar.
        assertThat(outcomeJob.measurePending()).isZero();
    }

    @Test
    void theJobOnlyMeasuresDecisionsOlderThanTheWindow() {
        long id = recommendation(RecommendationType.DISCOUNT, centro, yogur, yogurLot, "DISCOUNT:" + yogurLot, null,
                new BigDecimal("20"));
        recommendationService.accept(tenant, id, admin.id(), scopeAll, new AcceptRequest(null, null, null));

        outcomeJob.measurePending();

        assertThat(recommendationService.byId(tenant, id, scopeAll).outcome().hasNonNull("unitsAfter7d")).isFalse();
    }

    // ------------------------------------------------------------------ apoyo

    private long recommendation(RecommendationType type, long branchId, Long productId, Long lotId, String dedupeKey,
                                Integer quantity, BigDecimal discountPct) {
        Long id = jdbc.queryForObject("""
                insert into recommendations (tenant_id, branch_id, type, status, product_id, lot_id, title,
                                             explanation, suggested_quantity, suggested_discount_pct, priority,
                                             confidence, expected_impact, dedupe_key)
                values (?, ?, ?, 'PENDING', ?, ?, ?, 'Explicación de prueba', ?, ?, 80, 0.75, 1000, ?)
                returning id
                """, Long.class, tenant, branchId, type.name(), productId, lotId, "Recomendación " + type,
                quantity, discountPct, dedupeKey);
        return id == null ? 0 : id;
    }

    private ReorderRow yerbaRow() {
        return dashboardService.reorder(tenant, scopeAll, 50).stream()
                .filter(row -> row.productId().equals(yerba) && row.branchId().equals(centro))
                .findFirst().orElseThrow();
    }

    private void salesOn(int daysAgo, int quantity) {
        jdbc.update("""
                insert into stock_movements (tenant_id, branch_id, product_id, lot_id, type, quantity, unit_price,
                                             total_amount, source, batch_ref, occurred_at)
                values (?, ?, ?, ?, 'SALE', ?, 1400, ? * 1400, 'MANUAL', 'S-TEST', now() - make_interval(days => ?))
                """, tenant, centro, yogur, yogurLot, quantity, quantity, daysAgo);
    }

    private static void as(AuthUser user, String branchHeader) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(user, null, user.authorities()));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/tenant/recommendations");
        if (branchHeader != null) {
            request.addHeader(BranchAccessService.HEADER, branchHeader);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
