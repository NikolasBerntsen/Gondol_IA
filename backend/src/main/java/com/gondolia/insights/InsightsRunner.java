package com.gondolia.insights;

import com.gondolia.ai.AiClient;
import com.gondolia.ai.dto.AnalyzeResponse;
import com.gondolia.domain.ai.AiRun;
import com.gondolia.domain.ai.AiRunRepository;
import com.gondolia.domain.ai.AiRunStatus;
import com.gondolia.domain.ai.AiRunTrigger;
import com.gondolia.domain.common.Severity;
import com.gondolia.domain.notification.NotificationType;
import com.gondolia.domain.user.Role;
import com.gondolia.insights.InsightsStore.AnalyzeInput;
import com.gondolia.insights.InsightsStore.ApplyResult;
import com.gondolia.notification.NotificationDraft;
import com.gondolia.notification.NotificationService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Ejecuta el análisis de IA de una sucursal (SPEC §6.5): arma el pedido, llama al servicio de IA <strong>fuera de
 * toda transacción</strong> y guarda patrones y recomendaciones. Una sucursal no se analiza dos veces a la vez.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InsightsRunner {

    private final InsightsStore store;
    private final AiClient aiClient;
    private final AiRunRepository runRepository;
    private final NotificationService notificationService;
    private final NamedParameterJdbcTemplate jdbc;

    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    /** Sucursal a analizar. */
    public record BranchTarget(long tenantId, long branchId, String branchName) {
    }

    /** Qué pasó con el análisis de una sucursal. */
    public record RunOutcome(long branchId, Long runId, AiRunStatus status, String message, ApplyResult result) {
    }

    /**
     * Reserva la sucursal y crea el {@code ai_run} en {@code RUNNING}. Devuelve {@code null} si ya se está
     * analizando (así el botón "Recalcular IA" no encola dos veces lo mismo).
     */
    public AiRun startIfIdle(BranchTarget target, AiRunTrigger trigger) {
        if (!running.add(target.branchId())) {
            return null;
        }
        try {
            return store.startRun(target.tenantId(), target.branchId(), trigger);
        } catch (RuntimeException e) {
            running.remove(target.branchId());
            throw e;
        }
    }

    /** Continúa en segundo plano un análisis ya reservado con {@link #startIfIdle}. */
    @Async
    public void executeAsync(BranchTarget target, AiRun run) {
        execute(target, run);
    }

    /** Analiza una sucursal de punta a punta. Nunca lanza: los errores quedan en el {@code ai_run}. */
    public RunOutcome run(BranchTarget target, AiRunTrigger trigger) {
        AiRun run = startIfIdle(target, trigger);
        if (run == null) {
            return new RunOutcome(target.branchId(), null, AiRunStatus.RUNNING,
                    "Ya hay un análisis en curso para esta sucursal", null);
        }
        return execute(target, run);
    }

    /** Analiza varias sucursales, una después de la otra (tareas programadas). */
    public void runAll(List<BranchTarget> targets, AiRunTrigger trigger) {
        targets.forEach(target -> run(target, trigger));
    }

    private RunOutcome execute(BranchTarget target, AiRun run) {
        try {
            AnalyzeInput input = store.buildInput(target.tenantId(), target.branchId(), target.branchName());
            if (input.request().products().isEmpty()) {
                ApplyResult empty = new ApplyResult(0, 0, 0, 0);
                store.finishRun(run.getId(), emptyResponse(), empty);
                return new RunOutcome(target.branchId(), run.getId(), AiRunStatus.OK,
                        "La sucursal todavía no tiene productos para analizar", empty);
            }
            AnalyzeResponse response = aiClient.analyze(input.request());
            ApplyResult result = store.applyResults(run, response, input);
            store.finishRun(run.getId(), response, result);
            notifyNewRecommendations(target, result);
            log.info("IA sucursal {}: {} productos, {} recomendaciones nuevas, {} expiradas", target.branchId(),
                    result.productsAnalyzed(), result.recommendationsCreated(), result.recommendationsExpired());
            return new RunOutcome(target.branchId(), run.getId(), AiRunStatus.OK, "Análisis completado", result);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("Falló el análisis de IA de la sucursal {}: {}", target.branchId(), message);
            store.failRun(run.getId(), message);
            return new RunOutcome(target.branchId(), run.getId(), AiRunStatus.ERROR, message, null);
        } finally {
            running.remove(target.branchId());
        }
    }

    /** Sucursales activas de comercios habilitados. */
    public List<BranchTarget> activeBranches() {
        return jdbc.query("""
                select b.id, b.tenant_id, b.name
                from branches b join tenants t on t.id = b.tenant_id
                where b.active and t.status = 'ACTIVE'
                order by b.tenant_id, b.id
                """, new MapSqlParameterSource(),
                (rs, rowNum) -> new BranchTarget(rs.getLong("tenant_id"), rs.getLong("id"), rs.getString("name")));
    }

    /** {@code true} si la sucursal no tiene un análisis OK en las últimas {@code hours} horas. */
    public boolean isStale(long branchId, int hours) {
        return !runRepository.existsByBranchIdAndStatusAndStartedAtAfter(branchId, AiRunStatus.OK,
                Instant.now().minusSeconds(hours * 3600L));
    }

    public boolean isRunning(long branchId) {
        return running.contains(branchId);
    }

    private void notifyNewRecommendations(BranchTarget target, ApplyResult result) {
        if (result.recommendationsCreated() <= 0) {
            return;
        }
        String branch = target.branchName() == null ? "Tu sucursal" : target.branchName();
        String title = result.recommendationsCreated() == 1
                ? "La IA tiene 1 recomendación nueva"
                : "La IA tiene %d recomendaciones nuevas".formatted(result.recommendationsCreated());
        NotificationDraft draft = new NotificationDraft(NotificationType.RECOMMENDATION, Severity.INFO, title,
                "%s: revisá las sugerencias de reposición, descuentos y anomalías en Inteligencia IA.".formatted(
                        branch),
                "/app/insights", "AI_RUN", null);
        notificationService.notifyBranchUsers(target.tenantId(), target.branchId(), Set.of(Role.TENANT_ADMIN), draft);
    }

    private static AnalyzeResponse emptyResponse() {
        return new AnalyzeResponse(null, Instant.now(), List.of(), List.of(), null);
    }
}
