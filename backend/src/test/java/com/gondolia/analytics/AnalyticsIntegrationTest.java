package com.gondolia.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.gondolia.analytics.BranchScopeService.Scope;
import com.gondolia.analytics.dto.BranchComparisonRow;
import com.gondolia.analytics.dto.DashboardSummary;
import com.gondolia.analytics.dto.ReorderRow;
import com.gondolia.analytics.dto.SalesStockPoint;
import com.gondolia.analytics.dto.StatisticsOverview;
import com.gondolia.analytics.dto.UpcomingExpirationRow;
import com.gondolia.domain.user.Role;
import com.gondolia.it.PostgresIntegrationTest;
import com.gondolia.it.TestData;
import com.gondolia.security.AuthUser;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inicio y estadísticas (SPEC §6.5) contra PostgreSQL real: KPIs, tendencia, comparación de sucursales, artículos a
 * reponer y el panorama de estadísticas, siempre con las ventas <strong>netas</strong> de anulaciones.
 * <p>
 * Incluye el aislamiento: el alcance de un comercio nunca ve datos de otro ni de una sucursal fuera del alcance.
 */
@SpringBootTest
@Transactional
@PostgresIntegrationTest.EnabledWhenRequested
class AnalyticsIntegrationTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        PostgresIntegrationTest.registerDatasource(registry);
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DashboardService dashboardService;
    @Autowired
    private StatisticsService statisticsService;
    @Autowired
    private Clock clock;

    private TestData data;
    private long tenant;
    private long otherTenant;
    private long centro;
    private long norte;
    private long otherBranch;
    private long leche;
    private long yogur;
    private long yerba;

    private Scope scopeAll;
    private Scope scopeCentro;
    private Scope scopeOther;

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc);
        tenant = data.tenant("Analítica");
        otherTenant = data.tenant("Analítica ajena");
        centro = data.branch(tenant, "Sucursal Centro", true);
        norte = data.branch(tenant, "Sucursal Norte", true);
        otherBranch = data.branch(otherTenant, "Sucursal Ajena", true);
        AuthUser admin = data.user(tenant, Role.TENANT_ADMIN, true);
        assertThat(admin.tenantId()).isEqualTo(tenant);

        leche = data.product(tenant, data.barcode(), "Leche entera", "1000", "1600");
        yogur = data.product(tenant, data.barcode(), "Yogur bebible", "800", "1400");
        yerba = data.product(tenant, data.barcode(), "Yerba mate", "3000", "5000");
        jdbc.update("update products set min_stock = 10 where id in (?, ?, ?)", leche, yogur, yerba);

        LocalDate today = LocalDate.now(clock);
        // Centro: leche con stock de sobra, yogur por vencer, yerba bajo mínimo.
        long lecheLot = data.lot(tenant, centro, leche, "L1", "L1", today.plusDays(60), 80, "ACTIVE", 20);
        long yogurLot = data.lot(tenant, centro, yogur, "Y1", "Y1", today.plusDays(3), 24, "ACTIVE", 5);
        data.lot(tenant, centro, yerba, "M1", "M1", null, 4, "ACTIVE", 30);
        // Centro: un lote ya vencido.
        data.lot(tenant, centro, yogur, "Y0", "Y0", today.minusDays(2), 6, "ACTIVE", 40);
        // Norte: solo leche.
        long norteLot = data.lot(tenant, norte, leche, "L2", "L2", today.plusDays(90), 50, "ACTIVE", 10);
        // Comercio ajeno: un montón de stock que nunca tiene que aparecer.
        long otherProduct = data.product(otherTenant, data.barcode(), "Producto ajeno", "5000", "9000");
        data.lot(otherTenant, otherBranch, otherProduct, "A1", "A1", today.plusDays(10), 999, "ACTIVE", 1);

        // Ventas: 10 días en Centro y 5 en Norte, con una anulación que tiene que restar.
        for (int day = 0; day < 10; day++) {
            sale(centro, leche, lecheLot, 4, "1600", day);
            sale(centro, yogur, yogurLot, 2, "1400", day);
        }
        for (int day = 0; day < 5; day++) {
            sale(norte, leche, norteLot, 3, "1600", day);
        }
        voidSale(centro, leche, lecheLot, 4, "1600", 1);
        // Faltante de stock (venta perdida estimada).
        shortage(centro, yerba, 5, "5000", 2);
        // Merma por vencimiento.
        waste(centro, yogur, yogurLot, 3, "800", 4);
        // Venta ajena: no puede sumar en ningún total del comercio bajo prueba.
        sale(otherBranch, otherProduct, null, 50, "9000", 1, otherTenant);

        Map<Long, String> names = Map.of(centro, "Sucursal Centro", norte, "Sucursal Norte");
        scopeAll = new Scope(List.of(centro, norte), names, true);
        scopeCentro = new Scope(List.of(centro), names, false);
        scopeOther = new Scope(List.of(otherBranch), Map.of(otherBranch, "Sucursal Ajena"), false);
    }

    // ------------------------------------------------------------------ resumen

    @Test
    void summaryCountsOnlyTheScopeAndNetsVoidedSales() {
        DashboardSummary all = dashboardService.summary(tenant, scopeAll);

        assertThat(all.scope()).isEqualTo("ALL");
        assertThat(all.branchCount()).isEqualTo(2);
        assertThat(all.productsCount()).isEqualTo(3);
        // 80 - 40 vendidas + 4 anuladas = 44 en Centro; 50 - 15 = 35 en Norte.
        assertThat(all.inventoryCostValue()).isPositive();
        assertThat(all.expiredCount()).isEqualTo(1);
        assertThat(all.expiringSoonCount()).isEqualTo(1);
        // Yerba con 4 unidades y mínimo 10 → bajo; en Norte yogur y yerba no existen, así que no cuentan.
        assertThat(all.lowStockCount()).isGreaterThanOrEqualTo(1);
        assertThat(all.today()).isEqualTo(LocalDate.now(clock));

        DashboardSummary soloCentro = dashboardService.summary(tenant, scopeCentro);
        assertThat(soloCentro.scope()).isEqualTo("BRANCH");
        assertThat(soloCentro.branchCount()).isEqualTo(1);
        assertThat(soloCentro.inventoryCostValue()).isLessThan(all.inventoryCostValue());
    }

    @Test
    void summaryOfAnotherTenantNeverLeaksIntoThisOne() {
        DashboardSummary mine = dashboardService.summary(tenant, scopeAll);
        // El producto ajeno cuesta 5.000 × 999: si se colara, el valor de inventario se dispararía.
        assertThat(mine.inventoryCostValue()).isLessThan(new BigDecimal("1000000"));

        // Y con el tenant correcto pero una sucursal ajena en el alcance, no hay datos.
        DashboardSummary cruzado = dashboardService.summary(tenant, scopeOther);
        assertThat(cruzado.inventoryCostValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(cruzado.inventorySaleValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(cruzado.todaySalesUnits()).isZero();
        assertThat(cruzado.expiringSoonCount()).isZero();
        assertThat(cruzado.lowStockCount()).isZero();
        // El catálogo sí es del comercio (SPEC §1.1: "Productos registrados"), no de la sucursal.
        assertThat(cruzado.productsCount()).isEqualTo(3);
    }

    @Test
    void emptyScopeAnswersZeroInsteadOfFailing() {
        DashboardSummary empty = dashboardService.summary(tenant, new Scope(List.of(), Map.of(), true));
        assertThat(empty.branchCount()).isZero();
        assertThat(empty.inventoryCostValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(empty.openAlertsCount()).isZero();
        // El catálogo del comercio se informa igual: no depende de las sucursales.
        assertThat(empty.productsCount()).isEqualTo(3);
        assertThat(dashboardService.reorder(tenant, new Scope(List.of(), Map.of(), true), 8)).isEmpty();
        assertThat(dashboardService.salesStockTrend(tenant, new Scope(List.of(), Map.of(), true), 30)).isEmpty();
    }

    // ------------------------------------------------------------------ tendencia y comparación

    @Test
    void salesStockTrendHasOnePointPerDayAndDiscountsVoids() {
        List<SalesStockPoint> trend = dashboardService.salesStockTrend(tenant, scopeCentro, 30);

        assertThat(trend).hasSize(30);
        assertThat(trend.getLast().date()).isEqualTo(LocalDate.now(clock));
        assertThat(trend).isSortedAccordingTo((a, b) -> a.date().compareTo(b.date()));

        long vendidas = trend.stream().mapToLong(SalesStockPoint::salesUnits).sum();
        // 10 días × (4 leche + 2 yogur) = 60, menos las 4 anuladas, más las 5 del faltante (también son ventas).
        assertThat(vendidas).isEqualTo(61);
        assertThat(trend.getLast().stockUnits()).isPositive();
    }

    @Test
    void branchComparisonHasOneRowPerBranchOfTheScope() {
        List<BranchComparisonRow> rows = dashboardService.branchComparison(tenant, scopeAll, 30);

        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(BranchComparisonRow::branchName)
                .containsExactlyInAnyOrder("Sucursal Centro", "Sucursal Norte");
        BranchComparisonRow centroRow = rows.stream().filter(r -> r.branchId().equals(centro)).findFirst().orElseThrow();
        BranchComparisonRow norteRow = rows.stream().filter(r -> r.branchId().equals(norte)).findFirst().orElseThrow();
        assertThat(centroRow.salesUnits()).isEqualTo(61);
        assertThat(norteRow.salesUnits()).isEqualTo(15);
        assertThat(centroRow.wasteValue()).isPositive();

        assertThat(dashboardService.branchComparison(tenant, scopeCentro, 30)).hasSize(1);
    }

    // ------------------------------------------------------------------ listas del Inicio

    @Test
    void upcomingExpirationsBringsTheClosestLotsWithTheirBranch() {
        List<UpcomingExpirationRow> rows = dashboardService.upcomingExpirations(tenant, scopeAll, 8);

        assertThat(rows).isNotEmpty();
        assertThat(rows.getFirst().branchName()).isEqualTo("Sucursal Centro");
        assertThat(rows).isSortedAccordingTo((a, b) -> a.expiryDate().compareTo(b.expiryDate()));
        assertThat(rows).allSatisfy(row -> assertThat(row.branchId()).isIn(centro, norte));
    }

    @Test
    void reorderListsOneRowPerProductAndBranchBelowMinimum() {
        List<ReorderRow> rows = dashboardService.reorder(tenant, scopeAll, 20);

        assertThat(rows).isNotEmpty();
        ReorderRow yerbaRow = rows.stream().filter(r -> r.productId().equals(yerba)).findFirst().orElseThrow();
        assertThat(yerbaRow.branchId()).isEqualTo(centro);
        assertThat(yerbaRow.sellableStock()).isEqualTo(4);
        assertThat(yerbaRow.minStock()).isEqualTo(10);
        assertThat(yerbaRow.status()).isIn("CRITICO", "BAJO", "SIN_STOCK");
        assertThat(yerbaRow.suggestedQuantity()).isPositive();
        assertThat(rows).allSatisfy(row -> assertThat(row.branchId()).isIn(centro, norte));
    }

    // ------------------------------------------------------------------ estadísticas

    @Test
    void statisticsOverviewNetsVoidsAndSeparatesLossesFromSales() {
        StatisticsOverview stats = statisticsService.overview(tenant, scopeAll, 90);

        assertThat(stats.scope()).isEqualTo("ALL");
        assertThat(stats.days()).isEqualTo(90);
        assertThat(stats.to()).isEqualTo(LocalDate.now(clock));

        // 61 unidades netas en Centro + 15 en Norte.
        assertThat(stats.sales().units()).isEqualTo(76);
        assertThat(stats.sales().amount()).isPositive();
        assertThat(stats.sales().byDay()).isNotEmpty();
        assertThat(stats.sales().byBranch()).hasSize(2);
        assertThat(stats.sales().byCategory()).isNotEmpty();

        assertThat(stats.products().top()).isNotEmpty();
        assertThat(stats.products().top().getFirst().productId()).isEqualTo(leche);
        assertThat(stats.products().abc()).isNotEmpty();
        assertThat(stats.products().rotation()).isNotEmpty();

        assertThat(stats.losses().wasteUnits()).isEqualTo(3);
        assertThat(stats.losses().wasteValue()).isPositive();
        assertThat(stats.losses().wasteByMonth()).isNotEmpty();
        assertThat(stats.losses().lostSales().units()).isEqualTo(5);
        assertThat(stats.losses().lostSales().estimatedAmount()).isPositive();
        assertThat(stats.losses().expiredLots()).isEqualTo(1);

        assertThat(stats.ai().recommendations().total()).isZero();
        assertThat(stats.ai().recommendations().acceptanceRatePct()).isNotNull();
    }

    @Test
    void statisticsOfOneBranchIgnoreTheOtherBranchesAndTenants() {
        StatisticsOverview centroStats = statisticsService.overview(tenant, scopeCentro, 90);

        assertThat(centroStats.scope()).isEqualTo("BRANCH");
        assertThat(centroStats.sales().units()).isEqualTo(61);
        assertThat(centroStats.sales().byBranch()).hasSize(1);
        assertThat(centroStats.sales().byBranch().getFirst().branchName()).isEqualTo("Sucursal Centro");

        // Con el alcance apuntando a una sucursal de otro comercio no sale ni una venta.
        StatisticsOverview cruzado = statisticsService.overview(tenant, scopeOther, 90);
        assertThat(cruzado.sales().units()).isZero();
        assertThat(cruzado.products().top()).isEmpty();
    }

    // ------------------------------------------------------------------ apoyo

    private void sale(long branchId, long productId, Long lotId, int quantity, String price, int daysAgo) {
        sale(branchId, productId, lotId, quantity, price, daysAgo, tenant);
    }

    private void sale(long branchId, long productId, Long lotId, int quantity, String price, int daysAgo,
                      long tenantId) {
        movement(tenantId, branchId, productId, lotId, "SALE", quantity, price, daysAgo, "S-TEST-" + daysAgo);
    }

    private void voidSale(long branchId, long productId, Long lotId, int quantity, String price, int daysAgo) {
        movement(tenant, branchId, productId, lotId, "SALE_VOID", quantity, price, daysAgo, "S-TEST-" + daysAgo);
    }

    private void shortage(long branchId, long productId, int quantity, String price, int daysAgo) {
        movement(tenant, branchId, productId, null, "SALE", quantity, price, daysAgo, "S-FALTA");
    }

    private void waste(long branchId, long productId, Long lotId, int quantity, String cost, int daysAgo) {
        movement(tenant, branchId, productId, lotId, "WASTE_EXPIRED", quantity, cost, daysAgo, "A-TEST");
    }

    private void movement(long tenantId, long branchId, long productId, Long lotId, String type, int quantity,
                          String price, int daysAgo, String batchRef) {
        jdbc.update("""
                insert into stock_movements (tenant_id, branch_id, product_id, lot_id, type, quantity, unit_price,
                                             total_amount, source, batch_ref, occurred_at)
                values (?, ?, ?, ?, ?, ?, ?::numeric, ?::numeric * ?, 'MANUAL', ?,
                        now() - make_interval(days => ?) )
                """, tenantId, branchId, productId, lotId, type, quantity, price, price, quantity, batchRef, daysAgo);
    }
}
