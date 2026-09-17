package com.gondolia.analytics;

import com.gondolia.analytics.BranchScopeService.Scope;
import com.gondolia.analytics.dto.BranchComparisonRow;
import com.gondolia.analytics.dto.DashboardSummary;
import com.gondolia.analytics.dto.ReorderRow;
import com.gondolia.analytics.dto.SalesStockPoint;
import com.gondolia.analytics.dto.UpcomingExpirationRow;
import com.gondolia.domain.tenant.TenantSettings;
import com.gondolia.domain.tenant.TenantSettingsRepository;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consultas del Inicio del comercio (SPEC §1.1 y §6.5). Todo respeta el alcance de sucursales y las reglas de stock
 * de SPEC §4.2: vendible sin vencer, físico = ACTIVE + cuarentena, ventas netas de anulaciones.
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    /** Horizonte de "Próximos vencimientos" (SPEC §4.2: el bucket UPCOMING llega a 30 días). */
    static final int UPCOMING_HORIZON_DAYS = 30;

    static final int MAX_TREND_DAYS = 180;

    /** Ventana de ventas para estimar el ritmo diario cuando la IA todavía no sugirió una cantidad. */
    private static final int DEMAND_WINDOW_DAYS = 28;

    private final NamedParameterJdbcTemplate jdbc;
    private final TenantSettingsRepository settingsRepository;
    private final Clock clock;

    private record BranchUnits(long branchId, long units, BigDecimal amount) {
    }

    private record DayValue(LocalDate day, long value, BigDecimal amount) {
    }

    private record BranchValue(long branchId, BigDecimal value, long count) {
    }

    TenantSettings settings(Long tenantId) {
        return settingsRepository.findById(tenantId).orElseGet(() -> TenantSettings.defaultsFor(tenantId));
    }

    LocalDate today() {
        return LocalDate.now(clock);
    }

    // ------------------------------------------------------------------ resumen

    @Transactional(readOnly = true)
    public DashboardSummary summary(Long tenantId, Scope scope) {
        LocalDate today = today();
        Instant now = clock.instant();
        TenantSettings settings = settings(tenantId);
        long products = count("select count(*) from products where tenant_id = :tenantId and active",
                new MapSqlParameterSource("tenantId", tenantId));
        if (scope.isEmpty()) {
            return new DashboardSummary(scope.label(), 0, products, 0, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO,
                    0, 0, 0, 0, BigDecimal.ZERO, null, today, now);
        }
        MapSqlParameterSource params = baseParams(tenantId, scope, today)
                .addValue("warningLimit", Date.valueOf(today.plusDays(settings.getExpiryWarningDays())))
                .addValue("dayStart", AnalyticsSql.startOfDay(today, clock))
                .addValue("dayEnd", AnalyticsSql.startOfDay(today.plusDays(1), clock));

        Map<String, Object> stock = jdbc.queryForMap("""
                select
                  count(*) filter (where l.status = 'ACTIVE' and l.expiry_date is not null
                                     and l.expiry_date < :today)                                     as expired_lots,
                  count(*) filter (where l.status = 'ACTIVE' and l.expiry_date is not null
                                     and l.expiry_date >= :today
                                     and l.expiry_date <= :warningLimit)                             as expiring_lots,
                  coalesce(sum(l.quantity * coalesce(l.cost_price, p.cost_price)), 0)                 as cost_value,
                  coalesce(sum(l.quantity * p.sale_price), 0)                                        as sale_value
                from lots l
                join products p on p.id = l.product_id
                where l.tenant_id = :tenantId and l.branch_id in (:branchIds) and %s
                """.formatted(AnalyticsSql.PHYSICAL_LOT), params);

        Map<String, Object> reorder = jdbc.queryForMap(reorderCountsSql(), params);

        Map<String, Object> sales = jdbc.queryForMap("""
                select coalesce(%s, 0) as units, coalesce(%s, 0) as amount
                from stock_movements m
                where m.tenant_id = :tenantId and m.branch_id in (:branchIds) and m.type in %s
                  and m.occurred_at >= :dayStart and m.occurred_at < :dayEnd
                """.formatted(AnalyticsSql.NET_SALE_UNITS, AnalyticsSql.NET_SALE_AMOUNT, AnalyticsSql.SALE_TYPES),
                params);

        long openAlerts = count("""
                select count(*) from alerts
                where tenant_id = :tenantId and status = 'OPEN'
                  and (branch_id in (:branchIds) or branch_id is null)
                """, params);
        long pendingRecommendations = count("""
                select count(*) from recommendations
                where tenant_id = :tenantId and branch_id in (:branchIds) and status = 'PENDING'
                """, params);
        long openRecallMatches = count("""
                select count(*) from recall_matches
                where tenant_id = :tenantId and branch_id in (:branchIds) and status = 'OPEN'
                """, params);
        Instant lastRun = jdbc.queryForObject("""
                select max(coalesce(finished_at, started_at)) from ai_runs
                where tenant_id = :tenantId and branch_id in (:branchIds) and status = 'OK'
                """, params, Instant.class);

        return new DashboardSummary(
                scope.label(), scope.branchIds().size(), products,
                number(stock.get("expiring_lots")), number(stock.get("expired_lots")),
                number(reorder.get("low_stock")), number(reorder.get("out_of_stock")),
                AnalyticsSql.scaled((BigDecimal) stock.get("cost_value")),
                AnalyticsSql.scaled((BigDecimal) stock.get("sale_value")),
                openAlerts, pendingRecommendations, openRecallMatches,
                number(sales.get("units")), AnalyticsSql.scaled((BigDecimal) sales.get("amount")),
                lastRun, today, now);
    }

    // ------------------------------------------------------------------ tendencia

    /**
     * Curva de ventas netas y stock físico total de los últimos {@code days} días. El stock histórico se reconstruye
     * hacia atrás desde el stock actual aplicando los movimientos con lote (un faltante no mueve stock).
     */
    @Transactional(readOnly = true)
    public List<SalesStockPoint> salesStockTrend(Long tenantId, Scope scope, int days) {
        int span = Math.clamp(days, 1, MAX_TREND_DAYS);
        LocalDate today = today();
        if (scope.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params = baseParams(tenantId, scope, today)
                .addValue("from", AnalyticsSql.startOfDay(today.minusDays(span - 1L), clock))
                .addValue("zone", clock.getZone().getId());

        Map<LocalDate, DayValue> sales = new HashMap<>();
        jdbc.query("""
                select (m.occurred_at at time zone :zone)::date as day,
                       coalesce(%s, 0) as units, coalesce(%s, 0) as amount
                from stock_movements m
                where m.tenant_id = :tenantId and m.branch_id in (:branchIds) and m.type in %s
                  and m.occurred_at >= :from
                group by 1
                """.formatted(AnalyticsSql.NET_SALE_UNITS, AnalyticsSql.NET_SALE_AMOUNT, AnalyticsSql.SALE_TYPES),
                params, (rs, rowNum) -> new DayValue(rs.getObject("day", LocalDate.class), rs.getLong("units"),
                        AnalyticsSql.money(rs, "amount")))
                .forEach(row -> sales.put(row.day(), row));

        Map<LocalDate, Long> deltas = new HashMap<>();
        jdbc.query("""
                select (m.occurred_at at time zone :zone)::date as day, coalesce(%s, 0) as delta
                from stock_movements m
                where m.tenant_id = :tenantId and m.branch_id in (:branchIds) and m.lot_id is not null
                  and m.occurred_at >= :from
                group by 1
                """.formatted(AnalyticsSql.STOCK_DELTA), params,
                (rs, rowNum) -> new DayValue(rs.getObject("day", LocalDate.class), rs.getLong("delta"), null))
                .forEach(row -> deltas.put(row.day(), row.value()));

        long currentStock = count("""
                select coalesce(sum(l.quantity), 0) from lots l
                where l.tenant_id = :tenantId and l.branch_id in (:branchIds) and %s
                """.formatted(AnalyticsSql.PHYSICAL_LOT), params);

        LocalDate[] dates = new LocalDate[span];
        long[] stock = new long[span];
        long running = currentStock;
        for (int i = span - 1; i >= 0; i--) {
            LocalDate day = today.minusDays(span - 1L - i);
            dates[i] = day;
            stock[i] = Math.max(running, 0);
            running -= deltas.getOrDefault(day, 0L);
        }

        List<SalesStockPoint> points = new ArrayList<>(span);
        for (int i = 0; i < span; i++) {
            DayValue row = sales.get(dates[i]);
            points.add(new SalesStockPoint(dates[i], row == null ? 0 : row.value(),
                    row == null ? BigDecimal.ZERO : row.amount(), stock[i]));
        }
        return points;
    }

    // ------------------------------------------------------------------ comparación de sucursales

    @Transactional(readOnly = true)
    public List<BranchComparisonRow> branchComparison(Long tenantId, Scope scope, int days) {
        if (scope.isEmpty()) {
            return List.of();
        }
        int span = Math.clamp(days, 1, MAX_TREND_DAYS);
        LocalDate today = today();
        TenantSettings settings = settings(tenantId);
        MapSqlParameterSource params = baseParams(tenantId, scope, today)
                .addValue("from", AnalyticsSql.startOfDay(today.minusDays(span - 1L), clock))
                .addValue("warningLimit", Date.valueOf(today.plusDays(settings.getExpiryWarningDays())));

        Map<Long, BranchUnits> sales = new HashMap<>();
        jdbc.query("""
                select m.branch_id, coalesce(%s, 0) as units, coalesce(%s, 0) as amount
                from stock_movements m
                where m.tenant_id = :tenantId and m.branch_id in (:branchIds) and m.type in %s
                  and m.occurred_at >= :from
                group by m.branch_id
                """.formatted(AnalyticsSql.NET_SALE_UNITS, AnalyticsSql.NET_SALE_AMOUNT, AnalyticsSql.SALE_TYPES),
                params, (rs, rowNum) -> new BranchUnits(rs.getLong("branch_id"), rs.getLong("units"),
                        AnalyticsSql.money(rs, "amount")))
                .forEach(row -> sales.put(row.branchId(), row));

        Map<Long, BigDecimal> waste = new HashMap<>();
        jdbc.query("""
                select m.branch_id, coalesce(sum(coalesce(m.total_amount, 0)), 0) as row_value, 0 as row_count
                from stock_movements m
                where m.tenant_id = :tenantId and m.branch_id in (:branchIds)
                  and m.type in ('WASTE_EXPIRED', 'WASTE_DAMAGED') and m.occurred_at >= :from
                group by m.branch_id
                """, params, this::branchValue).forEach(row -> waste.put(row.branchId(), row.value()));

        Map<Long, BranchValue> inventory = new HashMap<>();
        jdbc.query("""
                select l.branch_id,
                       coalesce(sum(l.quantity * coalesce(l.cost_price, p.cost_price)), 0) as row_value,
                       count(*) filter (where l.status = 'ACTIVE' and l.expiry_date is not null
                                          and l.expiry_date >= :today and l.expiry_date <= :warningLimit) as row_count
                from lots l join products p on p.id = l.product_id
                where l.tenant_id = :tenantId and l.branch_id in (:branchIds) and %s
                group by l.branch_id
                """.formatted(AnalyticsSql.PHYSICAL_LOT), params, this::branchValue)
                .forEach(row -> inventory.put(row.branchId(), row));

        Map<Long, Long> lowStock = new HashMap<>();
        jdbc.query(reorderRowsSql() + """
                 )
                 select branch_id, 0 as row_value, count(*) as row_count from reorder group by branch_id
                """, params, this::branchValue).forEach(row -> lowStock.put(row.branchId(), row.count()));

        Map<Long, Long> alerts = new HashMap<>();
        jdbc.query("""
                select branch_id, 0 as row_value, count(*) as row_count from alerts
                where tenant_id = :tenantId and branch_id in (:branchIds) and status = 'OPEN'
                group by branch_id
                """, params, this::branchValue).forEach(row -> alerts.put(row.branchId(), row.count()));

        List<BranchComparisonRow> rows = new ArrayList<>();
        for (Long branchId : scope.branchIds()) {
            BranchUnits branchSales = sales.get(branchId);
            BranchValue branchInventory = inventory.get(branchId);
            rows.add(new BranchComparisonRow(branchId, scope.nameOf(branchId),
                    branchSales == null ? 0 : branchSales.units(),
                    branchSales == null ? BigDecimal.ZERO : branchSales.amount(),
                    branchInventory == null ? BigDecimal.ZERO : branchInventory.value(),
                    branchInventory == null ? 0 : branchInventory.count(),
                    lowStock.getOrDefault(branchId, 0L),
                    waste.getOrDefault(branchId, BigDecimal.ZERO),
                    alerts.getOrDefault(branchId, 0L)));
        }
        rows.sort((a, b) -> b.salesAmount().compareTo(a.salesAmount()));
        return rows;
    }

    // ------------------------------------------------------------------ vencimientos

    @Transactional(readOnly = true)
    public List<UpcomingExpirationRow> upcomingExpirations(Long tenantId, Scope scope, int limit) {
        if (scope.isEmpty()) {
            return List.of();
        }
        LocalDate today = today();
        TenantSettings settings = settings(tenantId);
        MapSqlParameterSource params = baseParams(tenantId, scope, today)
                .addValue("horizon", Date.valueOf(today.plusDays(UPCOMING_HORIZON_DAYS)))
                .addValue("rowLimit", Math.clamp(limit, 1, 100));
        return jdbc.query("""
                select l.id, l.branch_id, l.product_id, p.name as product_name, l.lot_number, l.expiry_date,
                       l.quantity
                from lots l join products p on p.id = l.product_id
                where l.tenant_id = :tenantId and l.branch_id in (:branchIds) and l.status = 'ACTIVE'
                  and l.quantity > 0 and l.expiry_date is not null and l.expiry_date <= :horizon
                order by l.expiry_date asc, l.quantity desc, l.id asc
                limit :rowLimit
                """, params, (rs, rowNum) -> {
                    LocalDate expiry = rs.getObject("expiry_date", LocalDate.class);
                    long branchId = rs.getLong("branch_id");
                    return new UpcomingExpirationRow(rs.getLong("id"), branchId, scope.nameOf(branchId),
                            rs.getLong("product_id"), rs.getString("product_name"), rs.getString("lot_number"),
                            expiry, ChronoUnit.DAYS.between(today, expiry), rs.getInt("quantity"),
                            AnalyticsSql.expiryBucket(expiry, today, settings));
                });
    }

    // ------------------------------------------------------------------ reposición

    @Transactional(readOnly = true)
    public List<ReorderRow> reorder(Long tenantId, Scope scope, int limit) {
        if (scope.isEmpty()) {
            return List.of();
        }
        LocalDate today = today();
        TenantSettings settings = settings(tenantId);
        MapSqlParameterSource params = baseParams(tenantId, scope, today)
                .addValue("from", AnalyticsSql.startOfDay(today.minusDays(DEMAND_WINDOW_DAYS - 1L), clock))
                .addValue("rowLimit", Math.clamp(limit, 1, 200));

        return jdbc.query(reorderRowsSql() + """
                 ),
                 recent as (
                   select m.branch_id, m.product_id, coalesce(%s, 0) as units
                   from stock_movements m
                   where m.tenant_id = :tenantId and m.branch_id in (:branchIds) and m.type in %s
                     and m.occurred_at >= :from
                   group by m.branch_id, m.product_id
                 )
                 select r.branch_id, r.product_id, r.product_name, r.brand, r.sellable, r.min_stock,
                        coalesce(rc.units, 0) as recent_units,
                        i.suggested_order_qty, i.predicted_stockout_date
                 from reorder r
                 left join recent rc on rc.branch_id = r.branch_id and rc.product_id = r.product_id
                 left join product_insights i on i.branch_id = r.branch_id and i.product_id = r.product_id
                 order by r.sellable asc, coalesce(rc.units, 0) desc, r.product_name asc
                 limit :rowLimit
                """.formatted(AnalyticsSql.NET_SALE_UNITS, AnalyticsSql.SALE_TYPES), params, (rs, rowNum) -> {
                    int sellable = rs.getInt("sellable");
                    int minStock = rs.getInt("min_stock");
                    Number suggested = (Number) rs.getObject("suggested_order_qty");
                    long recentUnits = rs.getLong("recent_units");
                    long branchId = rs.getLong("branch_id");
                    return new ReorderRow(rs.getLong("product_id"), rs.getString("product_name"),
                            rs.getString("brand"), branchId, scope.nameOf(branchId), sellable, minStock,
                            suggestedQuantity(suggested, sellable, minStock, recentUnits, settings),
                            AnalyticsSql.reorderStatus(sellable, minStock),
                            rs.getObject("predicted_stockout_date", LocalDate.class));
                });
    }

    /**
     * Sugerencia de compra: la de la IA si la hay; si no, cubrir la cobertura objetivo más el tiempo de reposición con
     * el ritmo de venta de las últimas 4 semanas, nunca por debajo de volver al stock mínimo.
     */
    static int suggestedQuantity(Number aiSuggestion, int sellable, int minStock, long recentUnits,
                                 TenantSettings settings) {
        if (aiSuggestion != null && aiSuggestion.intValue() > 0) {
            return aiSuggestion.intValue();
        }
        double perDay = Math.max(recentUnits, 0) / (double) DEMAND_WINDOW_DAYS;
        int horizon = settings.getTargetCoverageDays() + settings.getDefaultLeadTimeDays();
        int byDemand = (int) Math.ceil(perDay * horizon) - sellable;
        int toMinimum = Math.max(minStock - sellable, 0);
        return Math.max(Math.max(byDemand, toMinimum), 1);
    }

    // ------------------------------------------------------------------ SQL compartido

    /**
     * Productos en (o por debajo de) el mínimo en una sucursal donde el comercio los maneja (tiene o tuvo lotes ahí).
     * Devuelve el CTE abierto: hay que cerrarlo con {@code )} y la consulta final.
     */
    static String reorderRowsSql() {
        return """
                with handled as (
                  select distinct l.branch_id, l.product_id
                  from lots l
                  where l.tenant_id = :tenantId and l.branch_id in (:branchIds)
                ),
                sellable as (
                  select l.branch_id, l.product_id, sum(l.quantity) as qty
                  from lots l
                  where l.tenant_id = :tenantId and l.branch_id in (:branchIds) and %s
                  group by l.branch_id, l.product_id
                ),
                reorder as (
                  select h.branch_id, h.product_id, p.name as product_name, p.brand,
                         coalesce(s.qty, 0)::int as sellable, p.min_stock
                  from handled h
                  join products p on p.id = h.product_id and p.active
                  left join sellable s on s.branch_id = h.branch_id and s.product_id = h.product_id
                  where p.min_stock > 0 and coalesce(s.qty, 0) <= p.min_stock
                """.formatted(AnalyticsSql.SELLABLE_LOT);
    }

    private static String reorderCountsSql() {
        return reorderRowsSql() + """
                 )
                 select count(*) as low_stock, count(*) filter (where sellable = 0) as out_of_stock from reorder
                """;
    }

    MapSqlParameterSource baseParams(Long tenantId, Scope scope, LocalDate today) {
        return new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("branchIds", scope.branchIds())
                .addValue("today", Date.valueOf(today));
    }

    private BranchValue branchValue(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new BranchValue(rs.getLong("branch_id"), AnalyticsSql.money(rs, "row_value"), rs.getLong("row_count"));
    }

    private long count(String sql, MapSqlParameterSource params) {
        Long value = jdbc.queryForObject(sql, params, Long.class);
        return value == null ? 0 : value;
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0;
    }
}
