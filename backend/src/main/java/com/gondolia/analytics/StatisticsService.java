package com.gondolia.analytics;

import com.gondolia.analytics.BranchScopeService.Scope;
import com.gondolia.analytics.dto.StatisticsOverview;
import com.gondolia.analytics.dto.StatisticsOverview.AbcBucket;
import com.gondolia.analytics.dto.StatisticsOverview.Ai;
import com.gondolia.analytics.dto.StatisticsOverview.BranchPoint;
import com.gondolia.analytics.dto.StatisticsOverview.CategoryPoint;
import com.gondolia.analytics.dto.StatisticsOverview.DayPoint;
import com.gondolia.analytics.dto.StatisticsOverview.LostSales;
import com.gondolia.analytics.dto.StatisticsOverview.Losses;
import com.gondolia.analytics.dto.StatisticsOverview.MonthPoint;
import com.gondolia.analytics.dto.StatisticsOverview.ProductPoint;
import com.gondolia.analytics.dto.StatisticsOverview.Products;
import com.gondolia.analytics.dto.StatisticsOverview.Recommendations;
import com.gondolia.analytics.dto.StatisticsOverview.RecoveredSales;
import com.gondolia.analytics.dto.StatisticsOverview.RotationRow;
import com.gondolia.analytics.dto.StatisticsOverview.Sales;
import com.gondolia.analytics.dto.StatisticsOverview.SourcePoint;
import com.gondolia.analytics.dto.StatisticsOverview.WasteReason;
import com.gondolia.analytics.dto.StatisticsOverview.WeekPoint;
import com.gondolia.domain.tenant.TenantSettings;
import com.gondolia.domain.tenant.TenantSettingsRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Estadísticas del comercio (SPEC §6.5): ventas por día, semana, categoría, sucursal y origen; top y bottom de
 * productos; ABC por facturación; rotación; mermas por mes; ventas perdidas por faltantes; aceptación de
 * recomendaciones y ventas recuperadas con descuentos. Todo neto de anulaciones y por alcance de sucursales.
 */
@Service
@RequiredArgsConstructor
public class StatisticsService {

    static final int MIN_DAYS = 7;
    static final int MAX_DAYS = 365;
    private static final int RANKING_SIZE = 10;
    private static final int ROTATION_SIZE = 20;

    private static final String[] WEEKDAY_SHORT = {"lun", "mar", "mié", "jue", "vie", "sáb", "dom"};

    /** Costo unitario del movimiento: el del lote y, si no tiene, el del producto (SPEC §4.2). */
    private static final String UNIT_COST = "coalesce(l.cost_price, p.cost_price)";

    private static final String SALE_FROM = """
            from stock_movements m
            join products p on p.id = m.product_id
            left join lots l on l.id = m.lot_id
            where m.tenant_id = :tenantId and m.branch_id in (:branchIds) and m.type in ('SALE', 'SALE_VOID')
              and m.occurred_at >= :from and m.occurred_at < :until
            """;

    private static final String SALE_FROM_CATEGORY = """
            from stock_movements m
            join products p on p.id = m.product_id
            left join lots l on l.id = m.lot_id
            left join categories c on c.id = p.category_id
            where m.tenant_id = :tenantId and m.branch_id in (:branchIds) and m.type in ('SALE', 'SALE_VOID')
              and m.occurred_at >= :from and m.occurred_at < :until
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final TenantSettingsRepository settingsRepository;
    private final Clock clock;

    private record ProductSales(long productId, String name, String brand, String categoryName, long units,
                                BigDecimal amount, BigDecimal cost) {
    }

    @Transactional(readOnly = true)
    public StatisticsOverview overview(Long tenantId, Scope scope, int days) {
        int span = Math.clamp(days, MIN_DAYS, MAX_DAYS);
        LocalDate today = LocalDate.now(clock);
        LocalDate from = today.minusDays(span - 1L);
        if (scope.isEmpty()) {
            return empty(scope, span, from, today);
        }
        TenantSettings settings = settingsRepository.findById(tenantId)
                .orElseGet(() -> TenantSettings.defaultsFor(tenantId));
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("branchIds", scope.branchIds())
                .addValue("today", Date.valueOf(today))
                .addValue("from", AnalyticsSql.startOfDay(from, clock))
                .addValue("until", AnalyticsSql.startOfDay(today.plusDays(1), clock))
                .addValue("zone", clock.getZone().getId())
                .addValue("warningLimit", Date.valueOf(today.plusDays(settings.getExpiryWarningDays())));

        Sales sales = sales(scope, params, span);
        Products products = products(params, span);
        Losses losses = losses(params);
        Ai ai = ai(params);
        return new StatisticsOverview(scope.label(), scope.branchIds().size(), span, from, today,
                sales, products, losses, ai);
    }

    // ------------------------------------------------------------------ ventas

    private Sales sales(Scope scope, MapSqlParameterSource params, int span) {
        Map<String, Object> totals = jdbc.queryForMap("""
                select coalesce(%s, 0) as units, coalesce(%s, 0) as amount,
                       coalesce(sum(case when m.type = 'SALE' then 1 else -1 end * m.quantity * %s), 0) as cost
                %s
                """.formatted(AnalyticsSql.NET_SALE_UNITS, AnalyticsSql.NET_SALE_AMOUNT, UNIT_COST, SALE_FROM),
                params);
        long units = toLong(totals.get("units"));
        BigDecimal amount = AnalyticsSql.scaled((BigDecimal) totals.get("amount"));
        BigDecimal cost = AnalyticsSql.scaled((BigDecimal) totals.get("cost"));
        BigDecimal margin = amount.subtract(cost);

        List<DayPoint> byDay = jdbc.query("""
                select (m.occurred_at at time zone :zone)::date as day,
                       coalesce(%s, 0) as units, coalesce(%s, 0) as amount
                %s
                group by 1 order by 1
                """.formatted(AnalyticsSql.NET_SALE_UNITS, AnalyticsSql.NET_SALE_AMOUNT, SALE_FROM), params,
                (rs, rowNum) -> new DayPoint(rs.getObject("day", LocalDate.class), rs.getLong("units"),
                        AnalyticsSql.money(rs, "amount")));

        List<WeekPoint> byWeek = jdbc.query("""
                select date_trunc('week', (m.occurred_at at time zone :zone))::date as week_start,
                       coalesce(%s, 0) as units, coalesce(%s, 0) as amount
                %s
                group by 1 order by 1
                """.formatted(AnalyticsSql.NET_SALE_UNITS, AnalyticsSql.NET_SALE_AMOUNT, SALE_FROM), params,
                (rs, rowNum) -> {
                    LocalDate start = rs.getObject("week_start", LocalDate.class);
                    return new WeekPoint(start, weekLabel(start), rs.getLong("units"),
                            AnalyticsSql.money(rs, "amount"));
                });

        List<CategoryPoint> byCategory = jdbc.query("""
                select p.category_id, coalesce(c.name, 'Sin categoría') as category_name,
                       coalesce(%s, 0) as units, coalesce(%s, 0) as amount
                %s
                group by p.category_id, c.name
                """.formatted(AnalyticsSql.NET_SALE_UNITS, AnalyticsSql.NET_SALE_AMOUNT,
                        SALE_FROM.replace("left join lots l on l.id = m.lot_id",
                                "left join lots l on l.id = m.lot_id\nleft join categories c on c.id = p.category_id")),
                params, (rs, rowNum) -> new CategoryPoint((Long) rs.getObject("category_id"),
                        rs.getString("category_name"), rs.getLong("units"), AnalyticsSql.money(rs, "amount"),
                        BigDecimal.ZERO));
        byCategory = withShare(byCategory, amount, CategoryPoint::amount,
                (row, share) -> new CategoryPoint(row.categoryId(), row.categoryName(), row.units(), row.amount(),
                        share));
        byCategory.sort(Comparator.comparing(CategoryPoint::amount).reversed());

        List<BranchPoint> byBranch = jdbc.query("""
                select m.branch_id, coalesce(%s, 0) as units, coalesce(%s, 0) as amount
                %s
                group by m.branch_id
                """.formatted(AnalyticsSql.NET_SALE_UNITS, AnalyticsSql.NET_SALE_AMOUNT, SALE_FROM), params,
                (rs, rowNum) -> {
                    long branchId = rs.getLong("branch_id");
                    return new BranchPoint(branchId, scope.nameOf(branchId), rs.getLong("units"),
                            AnalyticsSql.money(rs, "amount"), BigDecimal.ZERO);
                });
        byBranch = withShare(byBranch, amount, BranchPoint::amount,
                (row, share) -> new BranchPoint(row.branchId(), row.branchName(), row.units(), row.amount(), share));
        byBranch.sort(Comparator.comparing(BranchPoint::amount).reversed());

        List<SourcePoint> bySource = jdbc.query("""
                select m.source, coalesce(%s, 0) as units, coalesce(%s, 0) as amount
                %s
                group by m.source order by 3 desc
                """.formatted(AnalyticsSql.NET_SALE_UNITS, AnalyticsSql.NET_SALE_AMOUNT, SALE_FROM), params,
                (rs, rowNum) -> new SourcePoint(rs.getString("source"), rs.getLong("units"),
                        AnalyticsSql.money(rs, "amount")));

        DayPoint best = byDay.stream().max(Comparator.comparing(DayPoint::amount)).orElse(null);
        return new Sales(units, amount, cost, margin, ratio(margin, amount),
                divide(BigDecimal.valueOf(units), span, 1), divide(amount, span, 2),
                best == null ? BigDecimal.ZERO : best.amount(), best == null ? null : best.date(),
                byDay, byWeek, byCategory, byBranch, bySource);
    }

    // ------------------------------------------------------------------ productos

    private Products products(MapSqlParameterSource params, int span) {
        List<ProductSales> rows = jdbc.query("""
                with handled as (
                  select distinct l.product_id
                  from lots l where l.tenant_id = :tenantId and l.branch_id in (:branchIds)
                ),
                sold as (
                  select m.product_id, coalesce(%s, 0) as units, coalesce(%s, 0) as amount,
                         coalesce(sum(case when m.type = 'SALE' then 1 else -1 end * m.quantity * %s), 0) as cost
                  %s
                  group by m.product_id
                ),
                candidates as (
                  select product_id from handled union select product_id from sold
                )
                select p.id, p.name, p.brand, coalesce(c.name, 'Sin categoría') as category_name,
                       coalesce(s.units, 0) as units, coalesce(s.amount, 0) as amount, coalesce(s.cost, 0) as cost
                from candidates cd
                join products p on p.id = cd.product_id and p.tenant_id = :tenantId
                left join categories c on c.id = p.category_id
                left join sold s on s.product_id = p.id
                where p.active
                """.formatted(AnalyticsSql.NET_SALE_UNITS, AnalyticsSql.NET_SALE_AMOUNT, UNIT_COST, SALE_FROM),
                params, (rs, rowNum) -> new ProductSales(rs.getLong("id"), rs.getString("name"),
                        rs.getString("brand"), rs.getString("category_name"), rs.getLong("units"),
                        AnalyticsSql.money(rs, "amount"), AnalyticsSql.money(rs, "cost")));

        List<ProductSales> ranked = new ArrayList<>(rows);
        ranked.sort(Comparator.comparing(ProductSales::amount).reversed()
                .thenComparing(Comparator.comparingLong(ProductSales::units).reversed()));
        BigDecimal totalAmount = ranked.stream().map(ProductSales::amount)
                .filter(value -> value.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<Long, String> abcClasses = abcClasses(ranked, totalAmount);
        List<ProductPoint> top = ranked.stream().filter(row -> row.amount().signum() > 0).limit(RANKING_SIZE)
                .map(row -> point(row, abcClasses)).toList();
        List<ProductPoint> bottom = ranked.stream()
                .sorted(Comparator.comparing(ProductSales::amount).thenComparing(ProductSales::name))
                .limit(RANKING_SIZE).map(row -> point(row, abcClasses)).toList();
        long withoutSales = ranked.stream().filter(row -> row.units() <= 0).count();

        List<AbcBucket> abc = abcBuckets(ranked, abcClasses, totalAmount);
        List<RotationRow> rotation = rotation(params, ranked, span);
        return new Products(top, bottom, abc, rotation, withoutSales);
    }

    private ProductPoint point(ProductSales row, Map<Long, String> abcClasses) {
        return new ProductPoint(row.productId(), row.name(), row.brand(), row.categoryName(), row.units(),
                row.amount(), row.amount().subtract(row.cost()), abcClasses.getOrDefault(row.productId(), "C"));
    }

    /** ABC por facturación acumulada: A hasta el 80 %, B hasta el 95 %, C el resto (SPEC §8.2). */
    private static Map<Long, String> abcClasses(List<ProductSales> ranked, BigDecimal totalAmount) {
        Map<Long, String> classes = new HashMap<>();
        BigDecimal cumulative = BigDecimal.ZERO;
        for (ProductSales row : ranked) {
            if (row.amount().signum() <= 0 || totalAmount.signum() == 0) {
                classes.put(row.productId(), "C");
                continue;
            }
            cumulative = cumulative.add(row.amount());
            BigDecimal share = ratio(cumulative, totalAmount);
            classes.put(row.productId(), share.compareTo(BigDecimal.valueOf(80)) <= 0 ? "A"
                    : share.compareTo(BigDecimal.valueOf(95)) <= 0 ? "B" : "C");
        }
        return classes;
    }

    private static List<AbcBucket> abcBuckets(List<ProductSales> ranked, Map<Long, String> classes,
                                              BigDecimal totalAmount) {
        Map<String, long[]> counters = new LinkedHashMap<>();
        Map<String, BigDecimal> amounts = new LinkedHashMap<>();
        for (String key : List.of("A", "B", "C")) {
            counters.put(key, new long[]{0, 0});
            amounts.put(key, BigDecimal.ZERO);
        }
        for (ProductSales row : ranked) {
            String key = classes.getOrDefault(row.productId(), "C");
            long[] counter = counters.get(key);
            counter[0]++;
            counter[1] += Math.max(row.units(), 0);
            amounts.put(key, amounts.get(key).add(row.amount().max(BigDecimal.ZERO)));
        }
        List<AbcBucket> buckets = new ArrayList<>();
        counters.forEach((key, counter) -> buckets.add(new AbcBucket(key, counter[0], counter[1],
                amounts.get(key), ratio(amounts.get(key), totalAmount))));
        return buckets;
    }

    private List<RotationRow> rotation(MapSqlParameterSource params, List<ProductSales> ranked, int span) {
        Map<Long, Integer> stock = new HashMap<>();
        jdbc.query("""
                select l.product_id, coalesce(sum(l.quantity), 0)::int as qty
                from lots l
                where l.tenant_id = :tenantId and l.branch_id in (:branchIds) and %s
                group by l.product_id
                """.formatted(AnalyticsSql.SELLABLE_LOT), params,
                (rs, rowNum) -> Map.entry(rs.getLong("product_id"), rs.getInt("qty")))
                .forEach(entry -> stock.put(entry.getKey(), entry.getValue()));

        Map<Long, String> patterns = new HashMap<>();
        jdbc.query("""
                select i.product_id, i.pattern
                from product_insights i
                where i.tenant_id = :tenantId and i.branch_id in (:branchIds) and i.pattern is not null
                order by i.updated_at asc
                """, params, (rs, rowNum) -> Map.entry(rs.getLong("product_id"), rs.getString("pattern")))
                .forEach(entry -> patterns.put(entry.getKey(), entry.getValue()));

        List<RotationRow> rows = new ArrayList<>();
        for (ProductSales row : ranked) {
            long units = Math.max(row.units(), 0);
            int sellable = stock.getOrDefault(row.productId(), 0);
            if (units == 0 && sellable == 0) {
                continue;
            }
            BigDecimal perDay = divide(BigDecimal.valueOf(units), span, 3);
            BigDecimal cover = perDay.signum() == 0 ? null
                    : BigDecimal.valueOf(sellable).divide(perDay, 1, RoundingMode.HALF_UP);
            BigDecimal turnover = sellable == 0 ? null
                    : BigDecimal.valueOf(units).divide(BigDecimal.valueOf(sellable), 2, RoundingMode.HALF_UP);
            rows.add(new RotationRow(row.productId(), row.name(), units, perDay, sellable, cover, turnover,
                    patterns.get(row.productId())));
        }
        rows.sort(Comparator.comparing(RotationRow::avgDailySales).reversed());
        return rows.stream().limit(ROTATION_SIZE).toList();
    }

    // ------------------------------------------------------------------ pérdidas

    private Losses losses(MapSqlParameterSource params) {
        Map<String, Object> waste = jdbc.queryForMap("""
                select coalesce(sum(m.quantity), 0) as units, coalesce(sum(coalesce(m.total_amount, 0)), 0) as value
                from stock_movements m
                where m.tenant_id = :tenantId and m.branch_id in (:branchIds)
                  and m.type in ('WASTE_EXPIRED', 'WASTE_DAMAGED')
                  and m.occurred_at >= :from and m.occurred_at < :until
                """, params);

        List<MonthPoint> byMonth = jdbc.query("""
                select to_char(date_trunc('month', (m.occurred_at at time zone :zone)), 'YYYY-MM') as month,
                       coalesce(sum(m.quantity), 0) as units, coalesce(sum(coalesce(m.total_amount, 0)), 0) as value
                from stock_movements m
                where m.tenant_id = :tenantId and m.branch_id in (:branchIds)
                  and m.type in ('WASTE_EXPIRED', 'WASTE_DAMAGED')
                  and m.occurred_at >= :from and m.occurred_at < :until
                group by 1 order by 1
                """, params, (rs, rowNum) -> new MonthPoint(rs.getString("month"), rs.getLong("units"),
                        AnalyticsSql.money(rs, "value")));

        List<WasteReason> byReason = jdbc.query("""
                select m.type, coalesce(sum(m.quantity), 0) as units,
                       coalesce(sum(coalesce(m.total_amount, 0)), 0) as value
                from stock_movements m
                where m.tenant_id = :tenantId and m.branch_id in (:branchIds)
                  and m.type in ('WASTE_EXPIRED', 'WASTE_DAMAGED')
                  and m.occurred_at >= :from and m.occurred_at < :until
                group by m.type order by 3 desc
                """, params, (rs, rowNum) -> new WasteReason(rs.getString("type"), rs.getLong("units"),
                        AnalyticsSql.money(rs, "value")));

        Map<String, Object> lost = jdbc.queryForMap("""
                select coalesce(%s, 0) as units,
                       coalesce(sum(case when m.type = 'SALE' then 1 else -1 end * m.quantity
                                         * coalesce(m.unit_price, p.sale_price)), 0) as amount,
                       count(*) filter (where m.type = 'SALE') as events
                from stock_movements m
                join products p on p.id = m.product_id
                where m.tenant_id = :tenantId and m.branch_id in (:branchIds) and m.type in ('SALE', 'SALE_VOID')
                  and m.lot_id is null and m.occurred_at >= :from and m.occurred_at < :until
                """.formatted(AnalyticsSql.NET_SALE_UNITS), params);

        Map<String, Object> expired = jdbc.queryForMap("""
                select count(*) as lots, coalesce(sum(l.quantity), 0) as units,
                       coalesce(sum(l.quantity * coalesce(l.cost_price, p.cost_price)), 0) as value
                from lots l join products p on p.id = l.product_id
                where l.tenant_id = :tenantId and l.branch_id in (:branchIds) and l.status = 'ACTIVE'
                  and l.quantity > 0 and l.expiry_date is not null and l.expiry_date < :today
                """, params);

        BigDecimal risk = jdbc.queryForObject("""
                select coalesce(sum(l.quantity * coalesce(l.cost_price, p.cost_price)), 0)
                from lots l join products p on p.id = l.product_id
                where l.tenant_id = :tenantId and l.branch_id in (:branchIds) and l.status = 'ACTIVE'
                  and l.quantity > 0 and l.expiry_date is not null and l.expiry_date >= :today
                  and l.expiry_date <= :warningLimit
                """, params, BigDecimal.class);

        return new Losses(toLong(waste.get("units")), AnalyticsSql.scaled((BigDecimal) waste.get("value")),
                byMonth, byReason,
                new LostSales(toLong(lost.get("units")), AnalyticsSql.scaled((BigDecimal) lost.get("amount")),
                        toLong(lost.get("events"))),
                toLong(expired.get("lots")), toLong(expired.get("units")),
                AnalyticsSql.scaled((BigDecimal) expired.get("value")), AnalyticsSql.scaled(risk));
    }

    // ------------------------------------------------------------------ IA

    private Ai ai(MapSqlParameterSource params) {
        Map<String, Object> counts = jdbc.queryForMap("""
                select count(*) as total,
                       count(*) filter (where status = 'PENDING') as pending,
                       count(*) filter (where status = 'ACCEPTED') as accepted,
                       count(*) filter (where status = 'DISCARDED') as discarded,
                       count(*) filter (where status = 'EXPIRED') as expired,
                       coalesce(sum(expected_impact) filter (where status = 'ACCEPTED'), 0) as impact
                from recommendations
                where tenant_id = :tenantId and branch_id in (:branchIds) and created_at >= :from
                """, params);
        long accepted = toLong(counts.get("accepted"));
        long discarded = toLong(counts.get("discarded"));
        long decided = accepted + discarded;
        BigDecimal acceptance = decided == 0 ? BigDecimal.ZERO
                : ratio(BigDecimal.valueOf(accepted), BigDecimal.valueOf(decided));

        Map<String, Object> recovered = jdbc.queryForMap("""
                select coalesce(%s, 0) as units, coalesce(%s, 0) as amount,
                       coalesce(sum(case when m.type = 'SALE' then 1 else -1 end * m.quantity * %s), 0) as cost,
                       coalesce(avg(m.discount_pct), 0) as avg_discount,
                       count(distinct m.lot_id) as lots
                %s
                  and m.discount_pct is not null and m.discount_pct > 0
                """.formatted(AnalyticsSql.NET_SALE_UNITS, AnalyticsSql.NET_SALE_AMOUNT, UNIT_COST, SALE_FROM),
                params);

        Instant lastRun = jdbc.queryForObject("""
                select max(coalesce(finished_at, started_at)) from ai_runs
                where tenant_id = :tenantId and branch_id in (:branchIds) and status = 'OK'
                """, params, Instant.class);
        Long insights = jdbc.queryForObject("""
                select count(*) from product_insights
                where tenant_id = :tenantId and branch_id in (:branchIds)
                """, params, Long.class);

        return new Ai(new Recommendations(toLong(counts.get("total")), toLong(counts.get("pending")), accepted,
                discarded, toLong(counts.get("expired")), acceptance,
                AnalyticsSql.scaled((BigDecimal) counts.get("impact"))),
                new RecoveredSales(toLong(recovered.get("units")),
                        AnalyticsSql.scaled((BigDecimal) recovered.get("amount")),
                        AnalyticsSql.scaled((BigDecimal) recovered.get("cost")),
                        AnalyticsSql.scaled((BigDecimal) recovered.get("avg_discount")),
                        toLong(recovered.get("lots"))),
                lastRun, insights == null ? 0 : insights);
    }

    // ------------------------------------------------------------------ utilidades

    private StatisticsOverview empty(Scope scope, int span, LocalDate from, LocalDate to) {
        return new StatisticsOverview(scope.label(), 0, span, from, to,
                new Sales(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, null, List.of(), List.of(), List.of(), List.of(), List.of()),
                new Products(List.of(), List.of(), List.of(), List.of(), 0),
                new Losses(0, BigDecimal.ZERO, List.of(), List.of(), new LostSales(0, BigDecimal.ZERO, 0),
                        0, 0, BigDecimal.ZERO, BigDecimal.ZERO),
                new Ai(new Recommendations(0, 0, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO),
                        new RecoveredSales(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0), null, 0));
    }

    private static <T> List<T> withShare(List<T> rows, BigDecimal total,
                                         java.util.function.Function<T, BigDecimal> amount,
                                         java.util.function.BiFunction<T, BigDecimal, T> rebuild) {
        List<T> result = new ArrayList<>(rows.size());
        for (T row : rows) {
            result.add(rebuild.apply(row, ratio(amount.apply(row), total)));
        }
        return result;
    }

    static BigDecimal ratio(BigDecimal part, BigDecimal total) {
        if (total == null || total.signum() == 0 || part == null) {
            return BigDecimal.ZERO;
        }
        return part.multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal divide(BigDecimal value, int divisor, int scale) {
        if (divisor == 0) {
            return BigDecimal.ZERO;
        }
        return value.divide(BigDecimal.valueOf(divisor), scale, RoundingMode.HALF_UP);
    }

    private static long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private static String weekLabel(LocalDate weekStart) {
        if (weekStart == null) {
            return "";
        }
        String day = WEEKDAY_SHORT[weekStart.getDayOfWeek().getValue() - 1];
        return "%s %02d/%02d".formatted(day, weekStart.getDayOfMonth(), weekStart.getMonthValue())
                .toLowerCase(Locale.forLanguageTag("es-AR"));
    }
}
