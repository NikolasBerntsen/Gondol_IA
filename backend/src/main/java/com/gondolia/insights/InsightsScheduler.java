package com.gondolia.insights;

import com.gondolia.ai.AiClient;
import com.gondolia.domain.ai.AiRunTrigger;
import com.gondolia.insights.InsightsRunner.BranchTarget;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Programación del análisis de IA (SPEC §6.5): todas las noches a las 03:00 y al arrancar cuando la sucursal no
 * tiene un análisis OK en las últimas 24 horas.
 * <p>
 * Al arrancar espera a que el servicio de IA esté listo (el contenedor tarda en levantar): reintenta el
 * {@code /health} hasta {@link #STARTUP_HEALTH_RETRIES} veces con pausas de {@link #STARTUP_RETRY_SECONDS} s.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InsightsScheduler {

    static final int STALE_HOURS = 24;
    static final int STARTUP_HEALTH_RETRIES = 20;
    static final int STARTUP_RETRY_SECONDS = 15;

    private final InsightsRunner runner;
    private final InsightsStore store;
    private final AiClient aiClient;

    /** Análisis diario de todas las sucursales activas (03:00 hora del comercio). */
    @Scheduled(cron = "0 0 3 * * *")
    public void daily() {
        List<BranchTarget> targets = runner.activeBranches();
        log.info("Análisis diario de IA: {} sucursales", targets.size());
        runner.runAll(targets, AiRunTrigger.SCHEDULED);
    }

    /** Al arrancar: cierra runs colgados y analiza las sucursales sin análisis reciente. */
    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            int closed = store.closeStaleRuns();
            if (closed > 0) {
                log.info("Se cerraron {} análisis de IA interrumpidos", closed);
            }
            List<BranchTarget> stale = runner.activeBranches().stream()
                    .filter(target -> runner.isStale(target.branchId(), STALE_HOURS))
                    .toList();
            if (stale.isEmpty()) {
                return;
            }
            if (!waitForAi()) {
                log.info("El servicio de IA no respondió: el análisis inicial queda para el próximo intento");
                return;
            }
            log.info("Análisis de IA al arrancar: {} sucursales sin resultados recientes", stale.size());
            runner.runAll(stale, AiRunTrigger.STARTUP);
        } catch (Exception e) {
            log.warn("No se pudo correr el análisis de IA al arrancar: {}", e.toString());
        }
    }

    /** Espera a que el servicio de IA esté disponible; {@code false} si nunca respondió. */
    private boolean waitForAi() {
        for (int attempt = 1; attempt <= STARTUP_HEALTH_RETRIES; attempt++) {
            if (aiClient.isHealthy()) {
                return true;
            }
            try {
                Thread.sleep(STARTUP_RETRY_SECONDS * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
