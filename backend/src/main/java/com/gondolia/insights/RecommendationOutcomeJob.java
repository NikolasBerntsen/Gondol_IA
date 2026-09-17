package com.gondolia.insights;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gondolia.domain.ai.Recommendation;
import com.gondolia.domain.ai.RecommendationRepository;
import com.gondolia.domain.inventory.Lot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Medición del resultado de las recomendaciones de descuento aceptadas (SPEC §6.5): siete días después de la
 * decisión compara las ventas del producto antes y después, y guarda el {@code outcome}
 * ({@code unitsBefore7d}, {@code unitsAfter7d}, {@code lift}, {@code lotUnitsSold}, {@code lotUnitsRemaining}).
 * <p>
 * Ese {@code outcome} viaja como {@code feedback} en el siguiente análisis (§8.2), así la IA ajusta la elasticidad
 * por categoría con lo que realmente pasó en el comercio.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecommendationOutcomeJob {

    /** Corre todos los días a las 02:30, antes del análisis de IA de las 03:00. */
    static final String CRON = "0 30 2 * * *";

    private final RecommendationRepository recommendationRepository;
    private final RecommendationService recommendationService;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    @Scheduled(cron = CRON)
    public void daily() {
        int measured = measurePending();
        if (measured > 0) {
            log.info("Se midió el resultado de {} recomendaciones de descuento", measured);
        }
    }

    /** Mide todas las recomendaciones de descuento aceptadas que ya cumplieron la ventana. Devuelve cuántas. */
    @Transactional
    public int measurePending() {
        List<Long> ids = jdbc.query("""
                select r.id from recommendations r
                where r.type = 'DISCOUNT' and r.status = 'ACCEPTED' and r.decided_at is not null
                  and (r.outcome is null or r.outcome->>'unitsAfter7d' is null)
                  and (r.decided_at at time zone :zone)::date <= :limit
                order by r.decided_at
                limit 500
                """, new MapSqlParameterSource("zone", clock.getZone().getId())
                .addValue("limit", java.sql.Date.valueOf(
                        LocalDate.now(clock).minusDays(RecommendationService.OUTCOME_WINDOW_DAYS))),
                (rs, rowNum) -> rs.getLong("id"));
        int measured = 0;
        for (Long id : ids) {
            if (measure(id)) {
                measured++;
            }
        }
        return measured;
    }

    /** Mide una recomendación puntual. {@code false} si ya no existe o no se pudo medir. */
    @Transactional
    public boolean measure(Long recommendationId) {
        Recommendation recommendation = recommendationRepository.findById(recommendationId).orElse(null);
        if (recommendation == null || recommendation.getDecidedAt() == null) {
            return false;
        }
        LocalDate decided = recommendation.getDecidedAt().atZone(clock.getZone()).toLocalDate();
        int window = RecommendationService.OUTCOME_WINDOW_DAYS;

        JsonNode previous = recommendation.getOutcome();
        ObjectNode outcome = previous != null && previous.isObject()
                ? ((ObjectNode) previous).deepCopy()
                : recommendationService.mapper().createObjectNode();

        int before = outcome.hasNonNull(RecommendationService.FIELD_UNITS_BEFORE)
                ? outcome.get(RecommendationService.FIELD_UNITS_BEFORE).asInt()
                : recommendationService.unitsSold(recommendation.getBranchId(), recommendation.getProductId(),
                        decided.minusDays(window), decided);
        int after = recommendationService.unitsSold(recommendation.getBranchId(), recommendation.getProductId(),
                decided, decided.plusDays(window));

        outcome.put(RecommendationService.FIELD_UNITS_BEFORE, before);
        outcome.put(RecommendationService.FIELD_UNITS_AFTER, after);
        if (before > 0) {
            outcome.put(RecommendationService.FIELD_LIFT, BigDecimal.valueOf(after)
                    .divide(BigDecimal.valueOf(before), 3, RoundingMode.HALF_UP));
        } else {
            outcome.putNull(RecommendationService.FIELD_LIFT);
        }

        Lot lot = recommendationService.lot(recommendation.getTenantId(), recommendation.getLotId()).orElse(null);
        if (lot != null) {
            int atAccept = outcome.hasNonNull(RecommendationService.FIELD_LOT_UNITS_AT_ACCEPT)
                    ? outcome.get(RecommendationService.FIELD_LOT_UNITS_AT_ACCEPT).asInt()
                    : lot.getInitialQuantity();
            outcome.put(RecommendationService.FIELD_LOT_UNITS_SOLD, Math.max(atAccept - lot.getQuantity(), 0));
            outcome.put(RecommendationService.FIELD_LOT_UNITS_REMAINING, lot.getQuantity());
        }
        outcome.put(RecommendationService.FIELD_MEASURED_AT, clock.instant().toString());

        recommendation.setOutcome(outcome);
        recommendationRepository.saveAndFlush(recommendation);
        return true;
    }
}
