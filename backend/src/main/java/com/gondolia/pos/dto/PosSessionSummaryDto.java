package com.gondolia.pos.dto;

import com.gondolia.domain.pos.PosSessionStatus;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Fila del listado de turnos ("Mis turnos" y, para el administrador, todos los del alcance).
 * {@code closedWithoutSales}: el turno se cerró sin ninguna venta vigente.
 */
public record PosSessionSummaryDto(Long id, Long branchId, String branchName, Long registerId, String registerName,
                                   PosSessionStatus status, Long openedById, String openedByName, String closedByName,
                                   Instant openedAt, Instant closedAt, BigDecimal openingCash,
                                   BigDecimal expectedCash, BigDecimal countedCash, BigDecimal difference,
                                   int salesCount, BigDecimal salesTotal, int voidedCount, BigDecimal voidedTotal,
                                   boolean mine, boolean closedWithoutSales) {
}
