package com.gondolia.pos.dto;

import com.gondolia.domain.pos.PaymentMethod;
import com.gondolia.domain.pos.PosSessionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Reporte Z de un turno (SPEC §15.2).
 * <p>
 * {@code expectedCash = openingCash + Σ pagos CASH − Σ vuelto + cashIn − cashOut − efectivo neto de ventas anuladas}
 * y {@code difference = countedCash − expectedCash} (null mientras el turno sigue abierto).
 * {@code totalsByMethod} y {@code changeGiven} cuentan solo las ventas vigentes (COMPLETED).
 * {@code closedWithoutSales}: el turno se cerró sin ninguna venta vigente (false mientras sigue abierto).
 */
public record PosSessionReportDto(Long id, Long branchId, String branchName, Long registerId, String registerName,
                                  PosSessionStatus status, Long openedById, String openedByName, String closedByName,
                                  Instant openedAt, Instant closedAt, BigDecimal openingCash,
                                  Map<PaymentMethod, BigDecimal> totalsByMethod, BigDecimal cashIn,
                                  BigDecimal cashOut, BigDecimal changeGiven, BigDecimal expectedCash,
                                  BigDecimal countedCash, BigDecimal difference, int salesCount,
                                  BigDecimal salesTotal, int units, int voidedCount, BigDecimal voidedTotal,
                                  List<PosTopProductDto> topProducts, List<PosCashMovementDto> cashMovements,
                                  boolean mine, String closingNote, boolean closedWithoutSales) {
}
