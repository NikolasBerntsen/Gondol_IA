package com.gondolia.seed;

import com.gondolia.domain.announcement.RecallResolution;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Todo lo que produce la simulación de los comercios, listo para escribirse en bloque: lotes, movimientos, turnos
 * y ventas del POS, más los hechos que después se vuelven recomendaciones decididas, coincidencias de recall y filas
 * de la importación inicial.
 */
final class SimOutput {

    final List<SimModel.Lot> lots = new ArrayList<>();
    final List<SimModel.Movement> movements = new ArrayList<>();
    final List<SimModel.Session> sessions = new ArrayList<>();
    final List<SimModel.Sale> sales = new ArrayList<>();
    final List<DiscountOutcome> discounts = new ArrayList<>();
    final List<DiscardedDiscountOutcome> discardedDiscounts = new ArrayList<>();
    final List<ReorderOutcome> reorders = new ArrayList<>();
    final List<RecallOutcome> recalls = new ArrayList<>();
    final List<ImportedLot> importedLots = new ArrayList<>();
    /** Último número de ticket por sucursal (para {@code pos_branch_counters}). */
    final Map<Long, Long> ticketCounters = new HashMap<>();

    private int lotSeq;
    private int sessionSeq;
    private int saleSeq;

    int nextLotSeq() {
        return lotSeq++;
    }

    int nextSessionSeq() {
        return sessionSeq++;
    }

    int nextSaleSeq() {
        return saleSeq++;
    }

    /** Descuento aceptado sobre un lote, con su resultado medido a los 7 días si ya pasaron. */
    record DiscountOutcome(long tenantId, long branchId, String branchName, SimModel.Product product,
                           SimModel.Lot lot, Instant createdAt, Instant decidedAt, long decidedBy, int pct,
                           String note, int lotUnitsAtAccept, int unitsBefore7d, Integer unitsAfter7d,
                           Integer lotUnitsSold, Integer lotUnitsRemaining, Instant measuredAt,
                           int daysToExpiry, double avgDailyBefore) {
    }

    record DiscardedDiscountOutcome(long tenantId, long branchId, SimModel.Product product, SimModel.Lot lot,
                                    Instant createdAt, Instant decidedAt, long decidedBy, int pct, String note,
                                    int lotQuantity, int daysToExpiry, double avgDaily) {
    }

    record ReorderOutcome(long tenantId, long branchId, SimModel.Product product, Instant createdAt,
                          Instant decidedAt, long decidedBy, boolean accepted, String note, int suggestedQuantity,
                          int stockAtDecision, double avgDaily, java.time.LocalDate suggestedDate) {
    }

    record RecallOutcome(long tenantId, long branchId, String branchName, SimModel.Product product,
                         SimModel.Lot lot, int quantityAtMatch, Instant matchedAt, Instant acknowledgedAt,
                         long acknowledgedBy, Instant resolvedAt, long resolvedBy, RecallResolution resolution,
                         String note, List<Long> branchUserIds) {
    }

    /** Lote creado por la importación inicial (una fila de la planilla). */
    record ImportedLot(long tenantId, long branchId, String branchName, SimModel.Product product,
                       SimModel.Lot lot, int rowNumber) {
    }
}
