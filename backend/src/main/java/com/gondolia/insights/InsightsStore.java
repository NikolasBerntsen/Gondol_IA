package com.gondolia.insights;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gondolia.ai.dto.AnalyzeRequest;
import com.gondolia.ai.dto.AnalyzeResponse;
import com.gondolia.ai.dto.AnalyzeSettings;
import com.gondolia.ai.dto.DailySale;
import com.gondolia.ai.dto.FeedbackInput;
import com.gondolia.ai.dto.FeedbackOutcome;
import com.gondolia.ai.dto.LotInput;
import com.gondolia.ai.dto.ProductAnalysis;
import com.gondolia.ai.dto.ProductInput;
import com.gondolia.ai.dto.RecommendationResult;
import com.gondolia.domain.ai.AiRun;
import com.gondolia.domain.ai.AiRunRepository;
import com.gondolia.domain.ai.AiRunStatus;
import com.gondolia.domain.ai.AiRunTrigger;
import com.gondolia.domain.ai.ProductInsight;
import com.gondolia.domain.ai.ProductInsightRepository;
import com.gondolia.domain.ai.Recommendation;
import com.gondolia.domain.ai.RecommendationRepository;
import com.gondolia.domain.ai.RecommendationStatus;
import com.gondolia.domain.ai.RecommendationType;
import com.gondolia.domain.inventory.LotStatus;
import com.gondolia.domain.tenant.TenantSettings;
import com.gondolia.domain.tenant.TenantSettingsRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Acceso a datos del análisis de IA (SPEC §6.5, §8.2): arma el {@link AnalyzeRequest} de una sucursal, guarda los
 * {@code product_insights}, hace el upsert de las recomendaciones y cierra el {@code ai_run}.
 * <p>
 * Cada método corre en su propia transacción corta: la llamada HTTP a la IA (hasta 120 s) queda <strong>fuera</strong>
 * de cualquier transacción.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InsightsStore {

    /** Días de historia de ventas que se mandan a la IA (SPEC §6.5). */
    static final int SALES_HISTORY_DAYS = 180;

    /** Horizonte de pronóstico pedido a la IA. */
    static final int FORECAST_HORIZON_DAYS = 14;

    /** Ventana de recomendaciones decididas que se mandan como feedback. */
    static final int FEEDBACK_DAYS = 90;

    private static final int MAX_FEEDBACK_ROWS = 200;

    /** Campo propio dentro de {@code recommendations.outcome} con el descuento realmente aplicado. */
    static final String APPLIED_DISCOUNT_FIELD = "appliedDiscountPct";

    private final NamedParameterJdbcTemplate jdbc;
    private final AiRunRepository runRepository;
    private final ProductInsightRepository insightRepository;
    private final RecommendationRepository recommendationRepository;
    private final TenantSettingsRepository settingsRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** Entrada del análisis: el pedido a la IA más los ids válidos para validar la respuesta. */
    public record AnalyzeInput(AnalyzeRequest request, Set<Long> productIds, Set<Long> lotIds) {
    }

    /** Resultado de guardar la respuesta de la IA. */
    public record ApplyResult(int productsAnalyzed, int recommendationsCreated, int recommendationsUpdated,
                              int recommendationsExpired) {
    }

    // ------------------------------------------------------------------ ciclo de vida del run

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AiRun startRun(long tenantId, long branchId, AiRunTrigger trigger) {
        AiRun run = new AiRun();
        run.setTenantId(tenantId);
        run.setBranchId(branchId);
        run.setStatus(AiRunStatus.RUNNING);
        run.setTriggerType(trigger);
        run.setStartedAt(clock.instant());
        return runRepository.saveAndFlush(run);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failRun(Long runId, String message) {
        runRepository.findById(runId).ifPresent(run -> {
            run.setStatus(AiRunStatus.ERROR);
            run.setErrorMessage(message == null ? "Error desconocido" : message.substring(0,
                    Math.min(message.length(), 500)));
            run.setFinishedAt(clock.instant());
            runRepository.save(run);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishRun(Long runId, AnalyzeResponse response, ApplyResult result) {
        runRepository.findById(runId).ifPresent(run -> {
            run.setStatus(AiRunStatus.OK);
            run.setModelVersion(response.modelVersion());
            run.setProductsAnalyzed(result.productsAnalyzed());
            run.setRecommendationsCreated(result.recommendationsCreated());
            run.setSummary(response.summary() == null ? null : objectMapper.valueToTree(response.summary()));
            run.setFinishedAt(clock.instant());
            runRepository.save(run);
        });
    }

    /** Marca como ERROR los runs que quedaron RUNNING más de una hora (p. ej. tras un reinicio). */
    @Transactional
    public int closeStaleRuns() {
        return jdbc.update("""
                update ai_runs set status = 'ERROR', finished_at = :now,
                       error_message = 'El análisis quedó interrumpido'
                where status = 'RUNNING' and started_at < :limit
                """, new MapSqlParameterSource("now", clock.instant())
                .addValue("limit", clock.instant().minusSeconds(3600)));
    }

    // ------------------------------------------------------------------ entrada

    /** Arma el pedido de análisis de una sucursal con 180 días de ventas, lotes vivos y feedback. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public AnalyzeInput buildInput(long tenantId, long branchId, String branchName) {
        LocalDate today = LocalDate.now(clock);
        TenantSettings settings = settingsRepository.findById(tenantId)
                .orElseGet(() -> TenantSettings.defaultsFor(tenantId));
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("branchId", branchId)
                .addValue("today", java.sql.Date.valueOf(today))
                .addValue("zone", clock.getZone().getId())
                .addValue("from", today.minusDays(SALES_HISTORY_DAYS - 1L).atStartOfDay(clock.getZone()).toInstant())
                .addValue("feedbackSince", today.minusDays(FEEDBACK_DAYS).atStartOfDay(clock.getZone()).toInstant());

        Map<Long, List<DailySale>> salesByProduct = dailySales(params);
        Map<Long, List<LotInput>> lotsByProduct = new LinkedHashMap<>();
        Set<Long> lotIds = new HashSet<>();
        jdbc.query("""
                select l.id, l.product_id, l.lot_number, l.expiry_date, l.received_at, l.quantity, l.discount_pct,
                       l.status
                from lots l
                where l.tenant_id = :tenantId and l.branch_id = :branchId and l.quantity > 0
                  and l.status in ('ACTIVE', 'RECALLED')
                order by l.product_id, l.received_at, l.id
                """, params, (rs, rowNum) -> Map.entry(rs.getLong("product_id"), new LotInput(rs.getLong("id"),
                        rs.getString("lot_number"), rs.getObject("expiry_date", LocalDate.class),
                        rs.getObject("received_at", Instant.class), rs.getInt("quantity"),
                        rs.getBigDecimal("discount_pct"), LotStatus.valueOf(rs.getString("status")))))
                .forEach(entry -> {
                    lotsByProduct.computeIfAbsent(entry.getKey(), key -> new ArrayList<>()).add(entry.getValue());
                    lotIds.add(entry.getValue().lotId());
                });

        List<ProductInput> products = jdbc.query("""
                with handled as (
                  select distinct l.product_id from lots l
                  where l.tenant_id = :tenantId and l.branch_id = :branchId
                ),
                sold as (
                  select distinct m.product_id from stock_movements m
                  where m.tenant_id = :tenantId and m.branch_id = :branchId and m.type in ('SALE', 'SALE_VOID')
                    and m.occurred_at >= :from
                ),
                sellable as (
                  select l.product_id, sum(l.quantity) as qty from lots l
                  where l.tenant_id = :tenantId and l.branch_id = :branchId and l.status = 'ACTIVE'
                    and l.quantity > 0 and (l.expiry_date is null or l.expiry_date >= :today)
                  group by l.product_id
                ),
                candidates as (select product_id from handled union select product_id from sold)
                select p.id, p.name, coalesce(c.name, 'Sin categoría') as category, p.sale_price, p.cost_price,
                       p.min_stock, p.perishable, p.created_at, s.lead_time_days,
                       coalesce(sb.qty, 0)::int as sellable
                from candidates cd
                join products p on p.id = cd.product_id and p.tenant_id = :tenantId and p.active
                left join categories c on c.id = p.category_id
                left join suppliers s on s.id = p.supplier_id
                left join sellable sb on sb.product_id = p.id
                order by p.id
                """, params, (rs, rowNum) -> {
                    long productId = rs.getLong("id");
                    Number leadTime = (Number) rs.getObject("lead_time_days");
                    return new ProductInput(productId, rs.getString("name"), rs.getString("category"),
                            rs.getBigDecimal("sale_price"), rs.getBigDecimal("cost_price"), rs.getInt("min_stock"),
                            rs.getInt("sellable"), rs.getBoolean("perishable"),
                            leadTime == null ? settings.getDefaultLeadTimeDays() : leadTime.intValue(),
                            rs.getObject("created_at", Instant.class).atZone(clock.getZone()).toLocalDate(),
                            salesByProduct.getOrDefault(productId, List.of()),
                            lotsByProduct.getOrDefault(productId, List.of()));
                });

        AnalyzeSettings analyzeSettings = new AnalyzeSettings(settings.getStockRotation(),
                settings.getExpiryWarningDays(), settings.getExpiryCriticalDays(), settings.getDefaultLeadTimeDays(),
                settings.getTargetCoverageDays(), settings.getServiceLevel(), settings.getMaxDiscountPct(),
                FORECAST_HORIZON_DAYS);

        AnalyzeRequest request = new AnalyzeRequest(tenantId, branchId, branchName, today, analyzeSettings,
                products, feedback(params));
        Set<Long> productIds = new HashSet<>();
        products.forEach(product -> productIds.add(product.productId()));
        return new AnalyzeInput(request, productIds, lotIds);
    }

    private Map<Long, List<DailySale>> dailySales(MapSqlParameterSource params) {
        Map<Long, List<DailySale>> sales = new LinkedHashMap<>();
        jdbc.query("""
                select m.product_id, (m.occurred_at at time zone :zone)::date as day,
                       sum(case when m.type = 'SALE' then m.quantity else -m.quantity end) as units,
                       avg(m.discount_pct) filter (where m.discount_pct is not null) as discount
                from stock_movements m
                where m.tenant_id = :tenantId and m.branch_id = :branchId and m.type in ('SALE', 'SALE_VOID')
                  and m.occurred_at >= :from
                group by 1, 2
                order by 1, 2
                """, params, (rs, rowNum) -> Map.entry(rs.getLong("product_id"),
                        new DailySale(rs.getObject("day", LocalDate.class), Math.max(rs.getInt("units"), 0),
                                rs.getBigDecimal("discount"))))
                .forEach(entry -> {
                    if (entry.getValue().quantity() > 0) {
                        sales.computeIfAbsent(entry.getKey(), key -> new ArrayList<>()).add(entry.getValue());
                    }
                });
        return sales;
    }

    private List<FeedbackInput> feedback(MapSqlParameterSource params) {
        return jdbc.query("""
                select r.id, r.type, r.product_id, coalesce(c.name, 'Sin categoría') as category, r.status,
                       r.suggested_discount_pct, r.outcome::text as outcome
                from recommendations r
                left join products p on p.id = r.product_id
                left join categories c on c.id = p.category_id
                where r.tenant_id = :tenantId and r.branch_id = :branchId and r.type = 'DISCOUNT'
                  and r.status in ('ACCEPTED', 'DISCARDED') and r.decided_at >= :feedbackSince
                order by r.decided_at desc
                limit """ + MAX_FEEDBACK_ROWS, params, (rs, rowNum) -> {
                    JsonNode outcome = readJson(rs.getString("outcome"));
                    BigDecimal discount = rs.getBigDecimal("suggested_discount_pct");
                    if (outcome != null && outcome.hasNonNull(APPLIED_DISCOUNT_FIELD)) {
                        discount = outcome.get(APPLIED_DISCOUNT_FIELD).decimalValue();
                    }
                    return new FeedbackInput(rs.getLong("id"), RecommendationType.valueOf(rs.getString("type")),
                            (Long) rs.getObject("product_id"), rs.getString("category"),
                            RecommendationStatus.valueOf(rs.getString("status")), discount, toOutcome(outcome));
                });
    }

    private FeedbackOutcome toOutcome(JsonNode node) {
        if (node == null || !node.hasNonNull("unitsAfter7d")) {
            return null;
        }
        return new FeedbackOutcome(intOrNull(node, "unitsBefore7d"), intOrNull(node, "unitsAfter7d"),
                node.hasNonNull("lift") ? node.get("lift").asDouble() : null,
                intOrNull(node, "lotUnitsSold"), intOrNull(node, "lotUnitsRemaining"));
    }

    // ------------------------------------------------------------------ salida

    /**
     * Guarda los patrones por producto, hace el upsert de las recomendaciones PENDING (dedupe por sucursal) y expira
     * las que ya no aplican.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ApplyResult applyResults(AiRun run, AnalyzeResponse response, AnalyzeInput input) {
        long tenantId = run.getTenantId();
        long branchId = run.getBranchId();
        int analyzed = 0;
        for (ProductAnalysis analysis : response.products()) {
            if (analysis.productId() == null || !input.productIds().contains(analysis.productId())) {
                continue;
            }
            ProductInsight insight = insightRepository
                    .findByTenantIdAndBranchIdAndProductId(tenantId, branchId, analysis.productId())
                    .orElseGet(ProductInsight::new);
            insight.setTenantId(tenantId);
            insight.setBranchId(branchId);
            insight.setProductId(analysis.productId());
            insight.setRunId(run.getId());
            insight.setPattern(analysis.pattern());
            insight.setPatternDescription(trim(analysis.patternDescription(), 300));
            insight.setAbcClass(trim(analysis.abcClass(), 1));
            insight.setXyzClass(trim(analysis.xyzClass(), 1));
            insight.setAvgDailySales(decimal(analysis.avgDailySales()));
            insight.setTrendPct(decimal(analysis.trendPct()));
            insight.setWeekdayProfile(objectMapper.valueToTree(analysis.weekdayProfile()));
            insight.setForecast(objectMapper.valueToTree(analysis.forecast()));
            insight.setForecastMethod(trim(analysis.forecastMethod(), 30));
            insight.setDaysOfCover(decimal(analysis.daysOfCover()));
            insight.setPredictedStockoutDate(analysis.predictedStockoutDate());
            insight.setReorderPoint(analysis.reorderPoint());
            insight.setSafetyStock(analysis.safetyStock());
            insight.setSuggestedOrderQty(analysis.suggestedOrderQty());
            insight.setAnomalies(objectMapper.valueToTree(analysis.anomalies()));
            insight.setLotRisks(objectMapper.valueToTree(analysis.lotRisks()));
            insightRepository.save(insight);
            analyzed++;
        }

        Set<String> keys = new HashSet<>();
        int created = 0;
        int updated = 0;
        for (RecommendationResult result : response.recommendations()) {
            if (result.type() == null || result.dedupeKey() == null || result.dedupeKey().isBlank()) {
                continue;
            }
            if (result.productId() != null && !input.productIds().contains(result.productId())) {
                continue;
            }
            if (result.lotId() != null && !input.lotIds().contains(result.lotId())) {
                continue;
            }
            String dedupeKey = trim(result.dedupeKey(), 150);
            keys.add(dedupeKey);
            Optional<Recommendation> existing = recommendationRepository
                    .findByBranchIdAndDedupeKeyAndStatus(branchId, dedupeKey, RecommendationStatus.PENDING);
            Recommendation recommendation = existing.orElseGet(Recommendation::new);
            boolean isNew = existing.isEmpty();
            recommendation.setTenantId(tenantId);
            recommendation.setBranchId(branchId);
            recommendation.setRunId(run.getId());
            recommendation.setType(result.type());
            recommendation.setStatus(RecommendationStatus.PENDING);
            recommendation.setProductId(result.productId());
            recommendation.setLotId(result.lotId());
            recommendation.setTitle(trim(result.title(), 200));
            recommendation.setExplanation(result.explanation() == null ? "" : result.explanation());
            recommendation.setSuggestedQuantity(result.suggestedQuantity());
            recommendation.setSuggestedDiscountPct(result.suggestedDiscountPct());
            recommendation.setSuggestedDate(result.suggestedDate());
            recommendation.setPriority(Math.clamp(result.priority(), 1, 100));
            recommendation.setConfidence(result.confidence() == null ? null
                    : BigDecimal.valueOf(Math.clamp(result.confidence(), 0d, 1d)).setScale(3,
                            java.math.RoundingMode.HALF_UP));
            recommendation.setExpectedImpact(result.expectedImpact());
            recommendation.setDedupeKey(dedupeKey);
            recommendationRepository.save(recommendation);
            if (isNew) {
                created++;
            } else {
                updated++;
            }
        }

        int expired = 0;
        for (Recommendation pending : recommendationRepository.findByTenantIdAndBranchIdAndStatus(tenantId, branchId,
                RecommendationStatus.PENDING)) {
            if (keys.contains(pending.getDedupeKey())) {
                continue;
            }
            pending.setStatus(RecommendationStatus.EXPIRED);
            pending.setDecidedAt(clock.instant());
            recommendationRepository.save(pending);
            expired++;
        }
        return new ApplyResult(analyzed, created, updated, expired);
    }

    // ------------------------------------------------------------------ utilidades

    JsonNode readJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.debug("JSON ilegible: {}", e.getMessage());
            return null;
        }
    }

    private static Integer intOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asInt() : null;
    }

    private static BigDecimal decimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
