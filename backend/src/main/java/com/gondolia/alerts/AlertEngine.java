package com.gondolia.alerts;

import com.gondolia.alerts.AlertEvaluator.Evaluation;
import com.gondolia.common.events.StockChangedEvent;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Motor de alertas (SPEC §6.5): reevalúa las sucursales cada 10 minutos, al arrancar la aplicación y cuando cambia
 * el stock.
 * <p>
 * Los cambios de stock llegan en ráfagas (una importación o el seeder disparan cientos de
 * {@link StockChangedEvent}), así que el listener <strong>agrupa por sucursal</strong>: anota la sucursal tocada y
 * recién la evalúa cuando pasaron {@link #QUIET_PERIOD_MS} sin novedades (o {@link #MAX_WAIT_MS} desde el primer
 * evento). Así una importación de 10.000 filas produce una sola pasada por sucursal.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertEngine {

    /** Silencio necesario para procesar una sucursal con cambios pendientes. */
    static final long QUIET_PERIOD_MS = 5_000;

    /** Espera máxima desde el primer cambio: una ráfaga larga igual se procesa. */
    static final long MAX_WAIT_MS = 60_000;

    /** Barrido completo (SPEC §6.5: cada 10 minutos). */
    static final long SWEEP_INTERVAL_MS = 600_000;

    private final AlertEvaluator evaluator;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();

    /** Sucursal con cambios de stock esperando su evaluación. */
    private static final class Pending {
        private final long tenantId;
        private final long firstEventAt;
        private volatile long lastEventAt;

        private Pending(long tenantId, long now) {
            this.tenantId = tenantId;
            this.firstEventAt = now;
            this.lastEventAt = now;
        }
    }

    /** Sucursal activa de un comercio habilitado. */
    public record BranchRow(long tenantId, long branchId, String branchName) {
    }

    // ------------------------------------------------------------------ disparadores

    /** Al arrancar: una pasada completa para que el Inicio muestre las alertas del día. */
    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            Evaluation total = evaluateAll();
            log.info("Motor de alertas iniciado: {} abiertas, {} resueltas", total.opened(), total.resolved());
        } catch (Exception e) {
            log.warn("No se pudo correr el motor de alertas al arrancar: {}", e.getMessage());
        }
    }

    /** Barrido periódico de todas las sucursales activas. */
    @Scheduled(initialDelay = SWEEP_INTERVAL_MS, fixedDelay = SWEEP_INTERVAL_MS)
    public void scheduledSweep() {
        evaluateAll();
    }

    /**
     * Cambio de stock: se anota la sucursal y se evalúa cuando la ráfaga termina (ver {@link #flushPending()}).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStockChanged(StockChangedEvent event) {
        if (event == null || event.branchId() == null || event.tenantId() == null) {
            return;
        }
        long now = clock.millis();
        pending.compute(event.branchId(), (branchId, current) -> {
            if (current == null) {
                return new Pending(event.tenantId(), now);
            }
            current.lastEventAt = now;
            return current;
        });
    }

    /** Procesa las sucursales cuya ráfaga de cambios ya terminó. */
    @Scheduled(initialDelay = 2_000, fixedDelay = 2_000)
    public void flushPending() {
        if (pending.isEmpty()) {
            return;
        }
        long now = clock.millis();
        for (Map.Entry<Long, Pending> entry : Map.copyOf(pending).entrySet()) {
            Pending state = entry.getValue();
            boolean quiet = now - state.lastEventAt >= QUIET_PERIOD_MS;
            boolean waitedTooLong = now - state.firstEventAt >= MAX_WAIT_MS;
            if (!quiet && !waitedTooLong) {
                continue;
            }
            if (pending.remove(entry.getKey(), state)) {
                evaluateSafely(state.tenantId, entry.getKey(), branchName(state.tenantId, entry.getKey()));
            }
        }
    }

    // ------------------------------------------------------------------ ejecución

    /** Evalúa todas las sucursales activas de comercios {@code ACTIVE}. */
    public Evaluation evaluateAll() {
        int opened = 0;
        int resolved = 0;
        int critical = 0;
        for (BranchRow branch : activeBranches()) {
            Evaluation result = evaluateSafely(branch.tenantId(), branch.branchId(), branch.branchName());
            opened += result.opened();
            resolved += result.resolved();
            critical += result.criticalOpened();
        }
        return new Evaluation(opened, resolved, critical);
    }

    /** Evalúa una sucursal puntual (la usa el endpoint de recálculo y las pruebas). */
    public Evaluation evaluateBranch(long tenantId, long branchId) {
        return evaluateSafely(tenantId, branchId, branchName(tenantId, branchId));
    }

    private Evaluation evaluateSafely(long tenantId, long branchId, String branchName) {
        try {
            return evaluator.evaluateBranch(tenantId, branchId, branchName);
        } catch (Exception e) {
            log.warn("Falló la evaluación de alertas de la sucursal {}: {}", branchId, e.toString());
            return new Evaluation(0, 0, 0);
        }
    }

    public List<BranchRow> activeBranches() {
        return jdbc.query("""
                select b.id, b.tenant_id, b.name
                from branches b join tenants t on t.id = b.tenant_id
                where b.active and t.status = 'ACTIVE'
                order by b.tenant_id, b.id
                """, new MapSqlParameterSource(),
                (rs, rowNum) -> new BranchRow(rs.getLong("tenant_id"), rs.getLong("id"), rs.getString("name")));
    }

    private String branchName(long tenantId, long branchId) {
        List<String> names = jdbc.query("select name from branches where id = :id and tenant_id = :tenantId",
                new MapSqlParameterSource("id", branchId).addValue("tenantId", tenantId),
                (rs, rowNum) -> rs.getString(1));
        return names.isEmpty() ? null : names.getFirst();
    }
}
