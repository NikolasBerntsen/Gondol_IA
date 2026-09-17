package com.gondolia.insights;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gondolia.ai.AiClient;
import com.gondolia.analytics.BranchScopeService.Scope;
import com.gondolia.common.PageResponse;
import com.gondolia.common.error.BadRequestException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.common.error.ForbiddenException;
import com.gondolia.common.error.NotFoundException;
import com.gondolia.domain.ai.AiRun;
import com.gondolia.domain.ai.AiRunStatus;
import com.gondolia.domain.ai.AiRunTrigger;
import com.gondolia.domain.ai.SalesPattern;
import com.gondolia.domain.inventory.Lot;
import com.gondolia.domain.tenant.TenantSettings;
import com.gondolia.domain.tenant.TenantSettingsRepository;
import com.gondolia.insights.InsightsRunner.BranchTarget;
import com.gondolia.insights.dto.AiRunDto;
import com.gondolia.insights.dto.InsightsRunLaunchedDto;
import com.gondolia.insights.dto.InsightsSummaryDto;
import com.gondolia.insights.dto.InsightsSummaryDto.LotRiskRow;
import com.gondolia.insights.dto.ProductInsightDetail;
import com.gondolia.insights.dto.ProductInsightDetail.HistoryPoint;
import com.gondolia.insights.dto.ProductInsightDetail.LotRow;
import com.gondolia.insights.dto.ProductInsightRow;
import com.gondolia.stock.StockService;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
 * Lectura de la inteligencia del comercio (SPEC §6.5): resumen, patrones por producto, ficha de un producto en una
 * sucursal y estado de los análisis. También dispara el recálculo manual.
 */
@Service
@RequiredArgsConstructor
public class InsightsService {

    static final int HISTORY_DAYS = 90;
    static final int TOP_RISKS = 8;
    private static final int STOCKOUT_WINDOW_DAYS = 7;
    private static final int ANOMALY_WINDOW_DAYS = 7;

    /** Cuánto dura el estado de salud de la IA en caché (evita un ida y vuelta por request). */
    private static final long HEALTH_CACHE_MS = 30_000;

    static final String MSG_BRANCH_REQUIRED = "Elegí una sucursal para ver el detalle del producto";
    static final String MSG_NOT_FOUND = "No encontramos análisis de ese producto en la sucursal";

    private final NamedParameterJdbcTemplate jdbc;
    private final InsightsRunner runner;
    private final RecommendationService recommendationService;
    private final StockService stockService;
    private final TenantSettingsRepository settingsRepository;
    private final AiClient aiClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    private volatile long healthCheckedAt;
    private volatile boolean healthy;

    /** Filtro del listado de patrones. */
    public record InsightQuery(SalesPattern pattern, String abcClass, String q, int page, int size) {
    }

    // ------------------------------------------------------------------ resumen

    @Transactional(readOnly = true)
    public InsightsSummaryDto summary(Long tenantId, Scope scope) {
        if (scope.isEmpty()) {
            return new InsightsSummaryDto(scope.label(), 0, 0, null, false, aiAvailable(), Map.of(), Map.of(), 0,
                    Map.of(), BigDecimal.ZERO, 0, 0, List.of(), List.of());
        }
        LocalDate today = LocalDate.now(clock);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("branchIds", scope.branchIds())
                .addValue("today", Date.valueOf(today))
                .addValue("stockoutLimit", Date.valueOf(today.plusDays(STOCKOUT_WINDOW_DAYS)))
                .addValue("anomalySince", Date.valueOf(today.minusDays(ANOMALY_WINDOW_DAYS)));

        long analyzed = count("""
                select count(*) from product_insights
                where tenant_id = :tenantId and branch_id in (:branchIds)
                """, params);
        Map<String, Long> patternCounts = counts("""
                select coalesce(pattern, 'DATOS_INSUFICIENTES') as key, count(*) as row_count
                from product_insights
                where tenant_id = :tenantId and branch_id in (:branchIds)
                group by 1
                """, params);
        Map<String, Long> abcCounts = counts("""
                select coalesce(abc_class, 'C') as key, count(*) as row_count
                from product_insights
                where tenant_id = :tenantId and branch_id in (:branchIds)
                group by 1
                """, params);
        long pending = count("""
                select count(*) from recommendations
                where tenant_id = :tenantId and branch_id in (:branchIds) and status = 'PENDING'
                """, params);
        Map<String, Long> byType = counts("""
                select type as key, count(*) as row_count from recommendations
                where tenant_id = :tenantId and branch_id in (:branchIds) and status = 'PENDING'
                group by 1
                """, params);
        long stockouts = count("""
                select count(*) from product_insights
                where tenant_id = :tenantId and branch_id in (:branchIds)
                  and predicted_stockout_date is not null and predicted_stockout_date <= :stockoutLimit
                """, params);
        long anomalies = count("""
                select count(*)
                from product_insights i, lateral jsonb_array_elements(i.anomalies) a
                where i.tenant_id = :tenantId and i.branch_id in (:branchIds)
                  and jsonb_typeof(i.anomalies) = 'array' and (a->>'date')::date >= :anomalySince
                """, params);
        BigDecimal atRisk = jdbc.queryForObject("""
                select coalesce(sum(((r->>'unitsAtRisk')::numeric) * coalesce(l.cost_price, p.cost_price)), 0)
                from product_insights i
                join lateral jsonb_array_elements(i.lot_risks) r on true
                join lots l on l.id = (r->>'lotId')::bigint
                join products p on p.id = i.product_id
                where i.tenant_id = :tenantId and i.branch_id in (:branchIds)
                  and jsonb_typeof(i.lot_risks) = 'array' and l.quantity > 0
                  and coalesce((r->>'unitsAtRisk')::numeric, 0) > 0
                """, params, BigDecimal.class);

        List<LotRiskRow> topRisks = jdbc.query("""
                select i.branch_id, i.product_id, p.name as product_name, l.id as lot_id, l.lot_number,
                       l.expiry_date, l.quantity,
                       (r->>'unitsAtRisk')::int as units_at_risk,
                       r->>'riskLevel' as risk_level,
                       nullif(r->>'recommendedDiscountPct', '')::int as discount_pct,
                       ((r->>'unitsAtRisk')::numeric) * coalesce(l.cost_price, p.cost_price) as value_at_risk
                from product_insights i
                join lateral jsonb_array_elements(i.lot_risks) r on true
                join lots l on l.id = (r->>'lotId')::bigint
                join products p on p.id = i.product_id
                where i.tenant_id = :tenantId and i.branch_id in (:branchIds)
                  and jsonb_typeof(i.lot_risks) = 'array' and l.quantity > 0
                  and coalesce((r->>'unitsAtRisk')::numeric, 0) > 0
                order by value_at_risk desc, l.expiry_date asc
                limit """ + TOP_RISKS, params, (rs, rowNum) -> {
                    long branchId = rs.getLong("branch_id");
                    LocalDate expiry = rs.getObject("expiry_date", LocalDate.class);
                    Number discount = (Number) rs.getObject("discount_pct");
                    return new LotRiskRow(branchId, scope.nameOf(branchId), rs.getLong("product_id"),
                            rs.getString("product_name"), rs.getLong("lot_id"), rs.getString("lot_number"), expiry,
                            expiry == null ? null : ChronoUnit.DAYS.between(today, expiry), rs.getInt("quantity"),
                            rs.getInt("units_at_risk"), rs.getString("risk_level"),
                            discount == null ? null : discount.intValue(), rs.getBigDecimal("value_at_risk"));
                });

        List<AiRunDto> runs = latestRuns(tenantId, scope);
        Instant lastRunAt = runs.stream().filter(run -> run.status() == AiRunStatus.OK)
                .map(AiRunDto::finishedAt).filter(java.util.Objects::nonNull)
                .max(Instant::compareTo).orElse(null);
        boolean running = runs.stream().anyMatch(run -> run.status() == AiRunStatus.RUNNING);

        return new InsightsSummaryDto(scope.label(), scope.branchIds().size(), analyzed, lastRunAt, running,
                aiAvailable(), patternCounts, abcCounts, pending, byType,
                atRisk == null ? BigDecimal.ZERO : atRisk.setScale(2, java.math.RoundingMode.HALF_UP),
                stockouts, anomalies, topRisks, runs);
    }

    // ------------------------------------------------------------------ listado de patrones

    @Transactional(readOnly = true)
    public PageResponse<ProductInsightRow> products(Long tenantId, Scope scope, InsightQuery query) {
        if (scope.isEmpty()) {
            return PageResponse.empty(query.page(), query.size());
        }
        LocalDate today = LocalDate.now(clock);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("branchIds", scope.branchIds())
                .addValue("today", Date.valueOf(today));
        List<String> conditions = new ArrayList<>(List.of("i.tenant_id = :tenantId",
                "i.branch_id in (:branchIds)", "p.active"));
        if (query.pattern() != null) {
            conditions.add("i.pattern = :pattern");
            params.addValue("pattern", query.pattern().name());
        }
        if (query.abcClass() != null && !query.abcClass().isBlank()) {
            conditions.add("i.abc_class = :abcClass");
            params.addValue("abcClass", query.abcClass().strip().toUpperCase(Locale.ROOT));
        }
        if (query.q() != null && !query.q().isBlank()) {
            conditions.add("(lower(p.name) like :q or lower(coalesce(p.brand, '')) like :q"
                    + " or coalesce(p.barcode, '') like :rawQ)");
            params.addValue("q", "%" + query.q().strip().toLowerCase(Locale.ROOT) + "%");
            params.addValue("rawQ", "%" + query.q().strip() + "%");
        }
        String where = "where " + String.join(" and ", conditions);

        Long total = jdbc.queryForObject("""
                select count(*) from product_insights i join products p on p.id = i.product_id
                """ + where, params, Long.class);
        if (total == null || total == 0) {
            return PageResponse.empty(query.page(), query.size());
        }
        params.addValue("rowLimit", query.size()).addValue("rowOffset", (long) query.page() * query.size());
        List<ProductInsightRow> rows = jdbc.query(selectInsightRow() + where + """
                 order by case when i.predicted_stockout_date is null then 1 else 0 end,
                          i.predicted_stockout_date asc, i.avg_daily_sales desc nulls last, p.name asc
                 limit :rowLimit offset :rowOffset
                """, params, (rs, rowNum) -> mapInsightRow(rs, scope));
        return PageResponse.of(rows, query.page(), query.size(), total);
    }

    // ------------------------------------------------------------------ ficha de producto

    @Transactional(readOnly = true)
    public ProductInsightDetail productDetail(Long tenantId, Scope scope, Long productId, Long requestedBranchId) {
        Long branchId = requestedBranchId != null ? requestedBranchId : scope.singleBranchId();
        if (branchId == null) {
            throw new BadRequestException(ErrorCodes.BRANCH_REQUIRED, MSG_BRANCH_REQUIRED);
        }
        if (!scope.branchIds().contains(branchId)) {
            throw new ForbiddenException(ErrorCodes.BRANCH_FORBIDDEN, "No tenés acceso a esa sucursal");
        }
        LocalDate today = LocalDate.now(clock);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("branchIds", List.of(branchId))
                .addValue("branchId", branchId)
                .addValue("productId", productId)
                .addValue("today", Date.valueOf(today))
                .addValue("zone", clock.getZone().getId())
                .addValue("from", today.minusDays(HISTORY_DAYS - 1L).atStartOfDay(clock.getZone()).toInstant());

        List<Object[]> base = jdbc.query(selectInsightRow()
                + " where i.tenant_id = :tenantId and i.branch_id = :branchId and i.product_id = :productId",
                params, (rs, rowNum) -> new Object[]{mapInsightRow(rs, scope), rs.getString("barcode"),
                        rs.getBigDecimal("sale_price"), rs.getBigDecimal("cost_price"),
                        rs.getBoolean("perishable"), rs.getString("forecast_method"),
                        rs.getString("weekday_profile"), rs.getString("forecast"), rs.getString("anomalies"),
                        rs.getString("lot_risks")});
        if (base.isEmpty()) {
            throw new NotFoundException(MSG_NOT_FOUND);
        }
        Object[] row = base.getFirst();
        ProductInsightRow insight = (ProductInsightRow) row[0];

        List<HistoryPoint> history = jdbc.query("""
                select (m.occurred_at at time zone :zone)::date as day,
                       sum(case when m.type = 'SALE' then m.quantity else -m.quantity end) as units,
                       sum(case when m.type = 'SALE' then coalesce(m.total_amount, 0)
                                else -coalesce(m.total_amount, 0) end) as amount
                from stock_movements m
                where m.tenant_id = :tenantId and m.branch_id = :branchId and m.product_id = :productId
                  and m.type in ('SALE', 'SALE_VOID') and m.occurred_at >= :from
                group by 1 order by 1
                """, params, (rs, rowNum) -> new HistoryPoint(rs.getObject("day", LocalDate.class),
                        rs.getLong("units"), rs.getBigDecimal("amount")));

        TenantSettings settings = settingsRepository.findById(tenantId)
                .orElseGet(() -> TenantSettings.defaultsFor(tenantId));
        List<Lot> rotation = stockService.lotsInRotationOrder(tenantId, branchId, productId);
        List<LotRow> lots = new ArrayList<>();
        for (int i = 0; i < rotation.size(); i++) {
            Lot lot = rotation.get(i);
            lots.add(new LotRow(lot.getId(), lot.getLotNumber(), lot.getExpiryDate(),
                    lot.getExpiryDate() == null ? null : ChronoUnit.DAYS.between(today, lot.getExpiryDate()),
                    lot.getQuantity(), lot.getDiscountPct(), lot.getStatus().name(), i + 1,
                    expiryBucket(lot.getExpiryDate(), today, settings)));
        }

        return new ProductInsightDetail(insight, (String) row[1], (BigDecimal) row[2], (BigDecimal) row[3],
                (Boolean) row[4], history, readJson((String) row[6]), readJson((String) row[7]), (String) row[5],
                readJson((String) row[8]), readJson((String) row[9]), lots,
                recommendationService.forProduct(tenantId, scope, branchId, productId));
    }

    // ------------------------------------------------------------------ runs

    @Transactional(readOnly = true)
    public List<AiRunDto> latestRuns(Long tenantId, Scope scope) {
        if (scope.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("branchIds", scope.branchIds());
        return jdbc.query("""
                select distinct on (r.branch_id) r.id, r.branch_id, r.status, r.trigger_type, r.model_version,
                       r.products_analyzed, r.recommendations_created, r.started_at, r.finished_at, r.error_message
                from ai_runs r
                where r.tenant_id = :tenantId and r.branch_id in (:branchIds)
                order by r.branch_id, r.started_at desc
                """, params, (rs, rowNum) -> {
                    long branchId = rs.getLong("branch_id");
                    return new AiRunDto(rs.getLong("id"), branchId, scope.nameOf(branchId),
                            AiRunStatus.valueOf(rs.getString("status")),
                            AiRunTrigger.valueOf(rs.getString("trigger_type")), rs.getString("model_version"),
                            (Integer) rs.getObject("products_analyzed"),
                            (Integer) rs.getObject("recommendations_created"),
                            rs.getObject("started_at", Instant.class), rs.getObject("finished_at", Instant.class),
                            rs.getString("error_message"));
                });
    }

    /** Lanza el recálculo de la IA para las sucursales del alcance (SPEC §6.5: asincrónico, un run por sucursal). */
    public InsightsRunLaunchedDto launch(Long tenantId, Scope scope) {
        if (scope.isEmpty()) {
            throw new BadRequestException(ErrorCodes.BRANCH_REQUIRED, "No tenés sucursales para analizar");
        }
        List<AiRunDto> launched = new ArrayList<>();
        int skipped = 0;
        for (Long branchId : scope.branchIds()) {
            BranchTarget target = new BranchTarget(tenantId, branchId, scope.nameOf(branchId));
            AiRun run = runner.startIfIdle(target, AiRunTrigger.MANUAL);
            if (run == null) {
                skipped++;
                continue;
            }
            runner.executeAsync(target, run);
            launched.add(new AiRunDto(run.getId(), branchId, scope.nameOf(branchId), AiRunStatus.RUNNING,
                    AiRunTrigger.MANUAL, null, null, null, run.getStartedAt(), null, null));
        }
        String message = launched.isEmpty()
                ? "Ya hay un análisis en curso: esperá a que termine."
                : launched.size() == 1
                        ? "Estamos analizando la sucursal. Te avisamos cuando termine."
                        : "Estamos analizando %d sucursales. Te avisamos cuando terminen.".formatted(launched.size());
        if (!launched.isEmpty() && skipped > 0) {
            message += " (%d ya estaban en curso)".formatted(skipped);
        }
        return new InsightsRunLaunchedDto(message, launched.isEmpty() ? latestRuns(tenantId, scope) : launched);
    }

    // ------------------------------------------------------------------ apoyo

    private boolean aiAvailable() {
        long now = clock.millis();
        if (now - healthCheckedAt > HEALTH_CACHE_MS) {
            healthy = aiClient.isHealthy();
            healthCheckedAt = now;
        }
        return healthy;
    }

    private static String selectInsightRow() {
        return """
                select i.product_id, p.name as product_name, p.brand, p.barcode, p.sale_price, p.cost_price,
                       p.perishable, p.min_stock, coalesce(c.name, 'Sin categoría') as category_name,
                       i.branch_id, i.pattern, i.pattern_description, i.abc_class, i.xyz_class, i.avg_daily_sales,
                       i.trend_pct, i.days_of_cover, i.predicted_stockout_date, i.reorder_point, i.safety_stock,
                       i.suggested_order_qty, i.updated_at, i.forecast_method,
                       i.weekday_profile::text as weekday_profile, i.forecast::text as forecast,
                       i.anomalies::text as anomalies, i.lot_risks::text as lot_risks,
                       coalesce((select sum(l.quantity) from lots l
                                 where l.tenant_id = i.tenant_id and l.branch_id = i.branch_id
                                   and l.product_id = i.product_id and l.status = 'ACTIVE' and l.quantity > 0
                                   and (l.expiry_date is null or l.expiry_date >= :today)), 0)::int as sellable,
                       coalesce(case when jsonb_typeof(i.anomalies) = 'array'
                                     then jsonb_array_length(i.anomalies) end, 0) as anomalies_count,
                       coalesce((select count(*) from jsonb_array_elements(
                                     case when jsonb_typeof(i.lot_risks) = 'array' then i.lot_risks
                                          else '[]'::jsonb end) r
                                 where coalesce((r->>'unitsAtRisk')::numeric, 0) > 0), 0) as lots_at_risk,
                       coalesce((select sum((r->>'unitsAtRisk')::int) from jsonb_array_elements(
                                     case when jsonb_typeof(i.lot_risks) = 'array' then i.lot_risks
                                          else '[]'::jsonb end) r
                                 where coalesce((r->>'unitsAtRisk')::numeric, 0) > 0), 0) as units_at_risk
                from product_insights i
                join products p on p.id = i.product_id
                left join categories c on c.id = p.category_id
                """;
    }

    private static ProductInsightRow mapInsightRow(java.sql.ResultSet rs, Scope scope) throws java.sql.SQLException {
        long branchId = rs.getLong("branch_id");
        String pattern = rs.getString("pattern");
        return new ProductInsightRow(rs.getLong("product_id"), rs.getString("product_name"), rs.getString("brand"),
                rs.getString("category_name"), branchId, scope.nameOf(branchId),
                pattern == null ? null : SalesPattern.valueOf(pattern), rs.getString("pattern_description"),
                rs.getString("abc_class"), rs.getString("xyz_class"), rs.getBigDecimal("avg_daily_sales"),
                rs.getBigDecimal("trend_pct"), rs.getBigDecimal("days_of_cover"),
                rs.getObject("predicted_stockout_date", LocalDate.class), (Integer) rs.getObject("reorder_point"),
                (Integer) rs.getObject("safety_stock"), (Integer) rs.getObject("suggested_order_qty"),
                rs.getInt("sellable"), rs.getInt("min_stock"), rs.getInt("anomalies_count"),
                rs.getInt("lots_at_risk"), rs.getInt("units_at_risk"), rs.getObject("updated_at", Instant.class));
    }

    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private static String expiryBucket(LocalDate expiry, LocalDate today, TenantSettings settings) {
        if (expiry == null) {
            return "OK";
        }
        long days = ChronoUnit.DAYS.between(today, expiry);
        if (days < 0) {
            return "EXPIRED";
        }
        if (days <= settings.getExpiryCriticalDays()) {
            return "CRITICAL";
        }
        if (days <= settings.getExpiryWarningDays()) {
            return "WARNING";
        }
        return days <= 30 ? "UPCOMING" : "OK";
    }

    private long count(String sql, MapSqlParameterSource params) {
        Long value = jdbc.queryForObject(sql, params, Long.class);
        return value == null ? 0 : value;
    }

    private Map<String, Long> counts(String sql, MapSqlParameterSource params) {
        Map<String, Long> result = new LinkedHashMap<>();
        jdbc.query(sql, params, (rs, rowNum) -> Map.entry(rs.getString("key"), rs.getLong("row_count")))
                .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }
}
