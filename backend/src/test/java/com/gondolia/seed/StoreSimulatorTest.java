package com.gondolia.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.gondolia.domain.inventory.MovementSource;
import com.gondolia.domain.inventory.MovementType;
import com.gondolia.domain.pos.CashMovementType;
import com.gondolia.domain.pos.PaymentMethod;
import com.gondolia.domain.pos.PosSaleStatus;
import com.gondolia.domain.pos.PosSessionStatus;
import com.gondolia.domain.tenant.StockRotation;
import com.gondolia.seed.SimModel.Lot;
import com.gondolia.seed.SimModel.Movement;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * La simulación respeta las reglas de stock del núcleo (SPEC §4.2): se reproduce cada movimiento en orden y se
 * verifica que cada venta salga del lote que corresponde según FIFO/FEFO (lotes en liquidación primero), que nunca se
 * venda un lote vencido o en cuarentena, que ningún lote quede negativo y que los arqueos del POS cierren.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StoreSimulatorTest {

    private static final ZoneId ZONE = SimulationFixture.ZONE;
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 19);
    private static final Instant NOW = LocalDateTime.of(2026, 9, 19, 15, 30).atZone(ZONE).toInstant();

    private SimOutput out;
    private final Map<Long, StockRotation> rotationByTenant = new HashMap<>();
    private Map<String, StoreSimulator.TenantRun> runs;

    @BeforeAll
    void simulate() {
        SimulationFixture.Simulation simulation = SimulationFixture.simulate(TODAY, NOW, spec -> true);
        out = simulation.out();
        runs = simulation.runs();
        runs.values().forEach(run -> rotationByTenant.put(run.tenantId, run.spec.rotation()));
    }

    // ------------------------------------------------------------------ reglas de stock

    @Test
    void everySaleFollowsTheTenantRotationAndNothingGoesNegative() {
        Map<Lot, Integer> quantity = new HashMap<>();
        Map<Lot, Instant> quarantinedAt = new HashMap<>();
        out.recalls.forEach(recall -> quarantinedAt.put(recall.lot(), recall.matchedAt()));
        Map<String, List<Lot>> lotsByStock = new HashMap<>();
        for (Lot lot : out.lots) {
            lotsByStock.computeIfAbsent(lot.branchId + ":" + lot.product.id, key -> new ArrayList<>()).add(lot);
        }
        int checkedSales = 0;
        for (Movement movement : out.movements) {
            Lot lot = movement.lot;
            LocalDate day = movement.occurredAt.atZone(ZONE).toLocalDate();
            if (movement.type == MovementType.SALE) {
                List<Lot> sellable = sellable(lotsByStock.get(movement.branchId + ":" + movement.product.id),
                        quantity, quarantinedAt, day, movement.occurredAt, rotationByTenant.get(movement.tenantId));
                if (lot == null) {
                    assertThat(sellable).as("faltante con stock vendible").isEmpty();
                    assertThat(movement.source).isIn(MovementSource.POS, MovementSource.CSV, MovementSource.MANUAL);
                    continue;
                }
                assertThat(lot.expiryDate == null || !lot.expiryDate.isBefore(day))
                        .as("venta de un lote vencido %s", lot.lotNumber).isTrue();
                assertThat(sellable).as("sin lotes vendibles para la venta").isNotEmpty();
                assertThat(sellable.getFirst()).as("orden de rotación en %s", movement.occurredAt).isSameAs(lot);
                checkedSales++;
            }
            if (lot == null) {
                continue;
            }
            int delta = switch (movement.type) {
                case ENTRY, TRANSFER_IN, ADJUSTMENT_IN, SALE_VOID -> movement.quantity;
                default -> -movement.quantity;
            };
            int updated = quantity.getOrDefault(lot, 0) + delta;
            assertThat(updated).as("stock negativo en %s", lot.lotNumber).isGreaterThanOrEqualTo(0);
            quantity.put(lot, updated);
        }
        assertThat(checkedSales).isGreaterThan(50_000);
        for (Lot lot : out.lots) {
            assertThat(quantity.getOrDefault(lot, 0)).as("remanente del lote %s", lot.lotNumber)
                    .isEqualTo(lot.quantity);
        }
    }

    private static List<Lot> sellable(List<Lot> lots, Map<Lot, Integer> quantity, Map<Lot, Instant> quarantinedAt,
                                      LocalDate day, Instant moment, StockRotation rotation) {
        List<Lot> result = new ArrayList<>();
        for (Lot lot : lots == null ? List.<Lot>of() : lots) {
            Instant quarantine = quarantinedAt.get(lot);
            boolean recalled = quarantine != null && !quarantine.isAfter(moment);
            if (quantity.getOrDefault(lot, 0) > 0 && !recalled && !lot.availableAt.isAfter(moment)
                    && (lot.expiryDate == null || !lot.expiryDate.isBefore(day))) {
                result.add(lot);
            }
        }
        Comparator<Lot> fifo = Comparator.comparing((Lot lot) -> lot.receivedAt).thenComparingInt(lot -> lot.seq);
        Comparator<Lot> fefo = Comparator.comparing((Lot lot) -> lot.expiryDate,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(lot -> lot.receivedAt).thenComparingInt(lot -> lot.seq);
        result.sort(Comparator.comparing((Lot lot) -> !lot.discountActiveAt(moment))
                .thenComparing(rotation == StockRotation.FEFO ? fefo : fifo));
        return result;
    }

    @Test
    void historyCoversOneHundredEightyDaysAndStopsNow() {
        StoreSimulator.TenantRun donPepe = runs.get(DemoWorld.DON_PEPE);
        Instant first = out.movements.stream().filter(m -> m.tenantId == donPepe.tenantId
                        && m.type == MovementType.SALE).map(m -> m.occurredAt).min(Comparator.naturalOrder())
                .orElseThrow();
        assertThat(first.atZone(ZONE).toLocalDate()).isEqualTo(TODAY.minusDays(180));
        assertThat(out.movements).allMatch(m -> !m.occurredAt.isAfter(NOW));
        assertThat(out.lots).allMatch(lot -> !lot.availableAt.isAfter(NOW));
        // Hoy hay ventas (hasta la hora actual) en el POS de El Sol Centro.
        long centro = runs.get(DemoWorld.EL_SOL).branches.getFirst().id;
        assertThat(out.movements).anyMatch(m -> m.branchId == centro && m.type == MovementType.SALE
                && m.occurredAt.atZone(ZONE).toLocalDate().equals(TODAY));
    }

    @Test
    void salesUseTheThreeChannelsAndRealisticPaymentMixes() {
        Set<MovementSource> sources = new HashSet<>();
        out.movements.stream().filter(m -> m.type == MovementType.SALE).forEach(m -> sources.add(m.source));
        assertThat(sources).contains(MovementSource.POS_GONDOLIA, MovementSource.POS, MovementSource.CSV,
                MovementSource.MANUAL);
        // El Sol vende por las cuatro fuentes (datos-demo §4): también los pedidos que la administradora carga a mano.
        long elSol = runs.get(DemoWorld.EL_SOL).tenantId;
        Set<MovementSource> elSolSources = new HashSet<>();
        out.movements.stream().filter(m -> m.tenantId == elSol && m.type == MovementType.SALE)
                .forEach(m -> elSolSources.add(m.source));
        assertThat(elSolSources).contains(MovementSource.POS_GONDOLIA, MovementSource.POS, MovementSource.CSV,
                MovementSource.MANUAL);
        assertThat(out.sales).anyMatch(sale -> sale.status == PosSaleStatus.VOIDED);
        assertThat(out.sales).anyMatch(sale -> sale.payments.size() > 1);
        Set<PaymentMethod> methods = new HashSet<>();
        out.sales.forEach(sale -> sale.payments.forEach(payment -> methods.add(payment.method)));
        assertThat(methods).containsExactlyInAnyOrder(PaymentMethod.values());
        for (SimModel.Sale sale : out.sales) {
            assertThat(sale.paidTotal).isGreaterThanOrEqualTo(sale.total);
            assertThat(sale.changeAmount).isLessThanOrEqualTo(sale.cashReceived());
            assertThat(sale.subtotal.subtract(sale.discountTotal)).isEqualByComparingTo(sale.total);
        }
        assertThat(out.movements).anyMatch(m -> m.type == MovementType.TRANSFER_OUT);
        assertThat(out.movements).anyMatch(m -> m.type == MovementType.WASTE_EXPIRED);
        assertThat(out.movements).anyMatch(m -> m.type == MovementType.SALE && m.discountPct != null);
    }

    @Test
    void cashSessionsCloseWithConsistentArqueoAndOneOpenShiftPerUser() {
        Set<Long> openUsers = new HashSet<>();
        Set<Long> openRegisters = new HashSet<>();
        int withDifference = 0;
        for (SimModel.Session session : out.sessions) {
            BigDecimal expected = session.openingCash;
            int completed = 0;
            for (SimModel.Sale sale : session.sales) {
                if (sale.status == PosSaleStatus.COMPLETED) {
                    expected = expected.add(sale.cashReceived()).subtract(sale.changeAmount);
                    completed++;
                }
            }
            for (SimModel.CashMovement movement : session.cashMovements) {
                expected = movement.type == CashMovementType.CASH_IN ? expected.add(movement.amount)
                        : expected.subtract(movement.amount);
            }
            assertThat(session.salesCount).isEqualTo(completed);
            if (session.status == PosSessionStatus.CLOSED) {
                assertThat(session.expectedCash).isEqualByComparingTo(expected);
                assertThat(session.countedCash.subtract(session.expectedCash))
                        .isEqualByComparingTo(session.cashDifference);
                if (session.cashDifference.signum() != 0) {
                    withDifference++;
                    assertThat(session.closingNote).isNotBlank();
                }
            } else {
                assertThat(session.openedAt.atZone(ZONE).toLocalDate()).isEqualTo(TODAY);
                assertThat(openUsers.add(session.openedBy)).isTrue();
                assertThat(openRegisters.add(session.register.id)).isTrue();
            }
        }
        assertThat(withDifference).isPositive();
        assertThat(openUsers).isNotEmpty();
        StoreSimulator.TenantRun elSol = runs.get(DemoWorld.EL_SOL);
        assertThat(out.sessions).anyMatch(session -> session.status == PosSessionStatus.OPEN
                && session.openedBy == elSol.user("cajero@elsol.com"));
    }

    // ------------------------------------------------------------------ escenarios

    @Test
    void liveRecallLotsHaveStockOnlyInDonPepeAndElSolFisherton() {
        List<Lot> recallLots = out.lots.stream()
                .filter(lot -> DemoScenarios.LIVE_RECALL_LOT.equals(lot.lotNumber)).toList();
        assertThat(recallLots).hasSize(2);
        assertThat(recallLots).allMatch(lot -> lot.quantity > 0 && !lot.recalled
                && lot.product.template.barcode().equals("7791234500017"));
        long donPepe = runs.get(DemoWorld.DON_PEPE).tenantId;
        StoreSimulator.TenantRun elSol = runs.get(DemoWorld.EL_SOL);
        long fisherton = elSol.branches.stream().filter(b -> b.spec.key().equals("FIS")).findFirst().orElseThrow().id;
        assertThat(recallLots).extracting(lot -> lot.tenantId).containsExactlyInAnyOrder(donPepe, elSol.tenantId);
        assertThat(recallLots).filteredOn(lot -> lot.tenantId == elSol.tenantId)
                .allMatch(lot -> lot.branchId == fisherton);
    }

    @Test
    void pendingRecallLeavesItsLotsInQuarantineWithStock() {
        StoreSimulator.TenantRun donPepe = runs.get(DemoWorld.DON_PEPE);
        StoreSimulator.TenantRun elSol = runs.get(DemoWorld.EL_SOL);
        long centro = elSol.branches.stream().filter(b -> b.spec.key().equals("CEN")).findFirst().orElseThrow().id;
        assertThat(out.recalls).hasSize(2);
        assertThat(out.recalls).extracting(SimOutput.RecallOutcome::branchId)
                .containsExactlyInAnyOrder(donPepe.branches.getFirst().id, centro);
        for (SimOutput.RecallOutcome recall : out.recalls) {
            Lot lot = recall.lot();
            assertThat(lot.lotNumber).isEqualTo(DemoScenarios.PENDING_RECALL_LOT);
            assertThat(lot.recalled).isTrue();
            // Publicado ayer a la tarde y sin resolver: el lote sigue en cuarentena con todo lo que tenía.
            assertThat(recall.matchedAt().atZone(ZONE).toLocalDate()).isEqualTo(TODAY.minusDays(1));
            assertThat(recall.quantityAtMatch()).isPositive();
            assertThat(lot.quantity).isEqualTo(recall.quantityAtMatch());
            assertThat(recall.branchUserIds()).isNotEmpty();
            assertThat(out.movements).noneMatch(m -> m.lot == lot && m.occurredAt.isAfter(recall.matchedAt()));
            // Como el POS GondolIA, desde la coincidencia no se vende el producto en esa sucursal (de ningún lote).
            assertThat(out.movements).noneMatch(m -> m.type == MovementType.SALE && m.branchId == recall.branchId()
                    && m.product == lot.product && m.occurredAt.isAfter(recall.matchedAt()));
        }
    }

    @Test
    void acceptedDiscountsHaveMeasuredOutcomeAndSellFirst() {
        assertThat(out.discounts).hasSizeGreaterThanOrEqualTo(7);
        List<SimOutput.DiscountOutcome> measured = out.discounts.stream()
                .filter(discount -> discount.unitsAfter7d() != null).toList();
        assertThat(measured).hasSizeGreaterThanOrEqualTo(6);
        assertThat(measured).allMatch(discount -> discount.lotUnitsSold() + discount.lotUnitsRemaining()
                <= discount.lotUnitsAtAccept() && discount.lotUnitsSold() > 0);
        // La liquidación de hoy en El Sol Centro sigue abierta (todavía sin medir).
        assertThat(out.discounts).anyMatch(discount -> discount.unitsAfter7d() == null
                && discount.lot().quantity > 0 && discount.lot().discountPct != null);
    }

    @Test
    void fifoCaseLeavesANewerLotThatExpiresBeforeAnOlderOne() {
        for (String key : List.of(DemoWorld.DON_PEPE, DemoWorld.EL_SOL)) {
            StoreSimulator.TenantRun run = runs.get(key);
            boolean found = false;
            for (StoreSimulator.BranchRun branch : run.branches) {
                List<Lot> live = out.lots.stream().filter(lot -> lot.branchId == branch.id && lot.quantity > 0
                        && !lot.recalled && lot.expiryDate != null && !lot.expiryDate.isBefore(TODAY)).toList();
                for (Lot older : live) {
                    for (Lot newer : live) {
                        if (older.product == newer.product && older.receivedAt.isBefore(newer.receivedAt)
                                && newer.expiryDate.isBefore(older.expiryDate)) {
                            found = true;
                        }
                    }
                }
            }
            assertThat(found).as("caso FIFO en " + key).isTrue();
        }
    }

    @Test
    void severalProductsHaveTwoToFourLiveLotsPerBranch() {
        for (StoreSimulator.TenantRun run : List.of(runs.get(DemoWorld.DON_PEPE), runs.get(DemoWorld.EL_SOL),
                runs.get(DemoWorld.VIDA_SANA))) {
            for (StoreSimulator.BranchRun branch : run.branches) {
                Map<Long, Integer> liveLots = new HashMap<>();
                out.lots.stream().filter(lot -> lot.branchId == branch.id && lot.quantity > 0 && !lot.recalled)
                        .forEach(lot -> liveLots.merge(lot.product.id, 1, Integer::sum));
                long multi = liveLots.values().stream().filter(count -> count >= 2).count();
                assertThat(multi).as(branch.spec.name()).isGreaterThanOrEqualTo(5);
            }
        }
    }

    @Test
    void expiredLotsPendingAndLowStockExistAtTheEnd() {
        for (StoreSimulator.TenantRun run : List.of(runs.get(DemoWorld.DON_PEPE), runs.get(DemoWorld.EL_SOL),
                runs.get(DemoWorld.VIDA_SANA))) {
            assertThat(out.lots).as(run.spec.name()).anyMatch(lot -> lot.tenantId == run.tenantId
                    && lot.quantity > 0 && !lot.recalled && lot.expiryDate != null
                    && lot.expiryDate.isBefore(TODAY));
            // Al menos un producto quedó en cero (el proveedor dejó de entregar).
            boolean outOfStock = false;
            for (StoreSimulator.BranchRun branch : run.branches) {
                for (SimModel.Product product : run.products) {
                    if (product.template.pattern() == DemoCatalog.Pattern.NONE) {
                        continue;
                    }
                    int sellable = out.lots.stream().filter(lot -> lot.branchId == branch.id
                                    && lot.product == product && !lot.recalled
                                    && (lot.expiryDate == null || !lot.expiryDate.isBefore(TODAY)))
                            .mapToInt(lot -> lot.quantity).sum();
                    if (sellable == 0) {
                        outOfStock = true;
                    }
                }
            }
            assertThat(outOfStock).as("sin stock en " + run.spec.name()).isTrue();
        }
        long days = ChronoUnit.DAYS.between(TODAY.minusDays(180), TODAY);
        assertThat(days).isEqualTo(180);
    }
}
