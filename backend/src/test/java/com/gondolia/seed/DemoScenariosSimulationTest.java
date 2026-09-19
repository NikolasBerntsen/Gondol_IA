package com.gondolia.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.gondolia.domain.inventory.MovementSource;
import com.gondolia.domain.inventory.MovementType;
import com.gondolia.seed.SimModel.Lot;
import com.gondolia.seed.SimModel.Movement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Los escenarios documentados en {@code docs/datos-demo.md} se cumplen <em>cualquiera sea el día de la siembra</em>
 * (la simulación es determinística por fecha, así que se prueba con varias fechas a lo largo de un año): faltantes por
 * proveedor, lotes de los recalls en su lugar y ventas manuales de El Sol.
 */
class DemoScenariosSimulationTest {

    /** Faltantes que documenta datos-demo §3 ("Sin stock / bajo mínimo"): comercio, sucursal y producto. */
    private static final List<List<String>> DOCUMENTED_SHORTAGES = List.of(
            List.of(DemoWorld.DON_PEPE, "PRI", "yerba"), List.of(DemoWorld.DON_PEPE, "PRI", "aceite"),
            List.of(DemoWorld.EL_SOL, "CEN", "leche-desc"), List.of(DemoWorld.EL_SOL, "CEN", "detergente"),
            List.of(DemoWorld.EL_SOL, "FIS", "huevos"), List.of(DemoWorld.EL_SOL, "FIS", "arroz"),
            List.of(DemoWorld.EL_SOL, "ECH", "agua"), List.of(DemoWorld.EL_SOL, "ECH", "cola"),
            List.of(DemoWorld.VIDA_SANA, "NCB", "chia"), List.of(DemoWorld.VIDA_SANA, "NCB", "granola"),
            List.of(DemoWorld.VIDA_SANA, "CDR", "avena"));

    static Stream<LocalDate> seedDays() {
        return Stream.iterate(LocalDate.of(2026, 9, 19), day -> day.plusDays(31)).limit(12);
    }

    @ParameterizedTest
    @MethodSource("seedDays")
    void documentedScenariosHoldWhateverTheSeedDay(LocalDate today) {
        Instant now = LocalDateTime.of(today, LocalTime.of(15, 30)).atZone(SimulationFixture.ZONE).toInstant();
        SimulationFixture.Simulation simulation = SimulationFixture.simulate(today, now, DemoWorld.TenantSpec::rich);
        supplierStopsLeaveTheProductOutOfStockOrBelowMinimum(simulation, today);
        recallLotsStayWhereTheScenarioPutThem(simulation, today);
        elSolHasManualSalesEveryOtherWeek(simulation, today);
    }

    /** Proveedor que deja de entregar: desde el corte no entra nada y hoy queda sin stock o bajo mínimo. */
    private static void supplierStopsLeaveTheProductOutOfStockOrBelowMinimum(SimulationFixture.Simulation simulation,
                                                                             LocalDate today) {
        for (StoreSimulator.TenantRun run : simulation.runs().values()) {
            for (DemoScenarios.SupplyStop stop : run.scenario.supplyStops()) {
                StoreSimulator.BranchRun branch = simulation.branch(run.spec.key(), stop.branch());
                SimModel.Product product = simulation.product(run.spec.key(), stop.product());
                LocalDate from = today.minusDays(stop.fromDaysAgo());
                long scripted = run.scenario.lots().stream().filter(lot -> lot.branch().equals(stop.branch())
                        && lot.product().equals(stop.product()) && lot.daysAgo() <= stop.fromDaysAgo()).count();
                // Desde el corte no entra nada más que los lotes armados: ni pedidos pendientes ni transferencias.
                long arrivals = simulation.out().lots.stream().filter(lot -> lot.branchId == branch.id
                        && lot.product == product
                        && !lot.availableAt.atZone(SimulationFixture.ZONE).toLocalDate().isBefore(from)).count();
                assertThat(arrivals).as("%s: ingresos después del corte (%s)", stop, today).isEqualTo(scripted);
                if (scripted == 0) {
                    assertThat(simulation.sellable(branch, product, today))
                            .as("%s: sin stock o bajo mínimo (%s)", stop, today)
                            .isLessThanOrEqualTo(product.template.minStock());
                }
            }
        }
        for (List<String> shortage : DOCUMENTED_SHORTAGES) {
            StoreSimulator.TenantRun run = simulation.run(shortage.get(0));
            assertThat(run.scenario.supplyStops()).as("faltante documentado %s", shortage)
                    .anyMatch(stop -> stop.branch().equals(shortage.get(1))
                            && stop.product().equals(shortage.get(2)));
            SimModel.Product product = simulation.product(shortage.get(0), shortage.get(2));
            assertThat(simulation.sellable(simulation.branch(shortage.get(0), shortage.get(1)), product, today))
                    .as("faltante documentado %s (%s)", shortage, today)
                    .isLessThanOrEqualTo(product.template.minStock());
        }
    }

    /** Los lotes de los recalls armados no se transfieren: el recall en vivo alcanza solo a Don Pepe y Fisherton. */
    private static void recallLotsStayWhereTheScenarioPutThem(SimulationFixture.Simulation simulation,
                                                              LocalDate today) {
        long donPepe = simulation.branch(DemoWorld.DON_PEPE, "PRI").id;
        assertThat(branchesWithLot(simulation, DemoScenarios.LIVE_RECALL_LOT)).as("L2409A (%s)", today)
                .containsExactlyInAnyOrder(donPepe, simulation.branch(DemoWorld.EL_SOL, "FIS").id);
        assertThat(branchesWithLot(simulation, DemoScenarios.OLD_RECALL_LOT)).as("DV2603B (%s)", today)
                .containsExactlyInAnyOrder(donPepe, simulation.branch(DemoWorld.EL_SOL, "CEN").id);
    }

    /** El Sol vende también por la fuente manual (datos-demo §4): los pedidos quincenales que carga la administradora. */
    private static void elSolHasManualSalesEveryOtherWeek(SimulationFixture.Simulation simulation, LocalDate today) {
        long elSol = simulation.run(DemoWorld.EL_SOL).tenantId;
        List<Movement> manual = simulation.out().movements.stream().filter(m -> m.tenantId == elSol
                && m.type == MovementType.SALE && m.source == MovementSource.MANUAL).toList();
        assertThat(manual.stream().map(m -> m.batchRef).distinct().count()).as("pedidos manuales (%s)", today)
                .isGreaterThanOrEqualTo(10);
        assertThat(manual).allMatch(m -> m.lot != null && m.branchId == simulation.branch(DemoWorld.EL_SOL, "CEN").id);
        assertThat(manual).anyMatch(m -> !m.occurredAt.atZone(SimulationFixture.ZONE).toLocalDate()
                .isBefore(today.minusDays(15)));
    }

    private static List<Long> branchesWithLot(SimulationFixture.Simulation simulation, String lotNumber) {
        return simulation.out().lots.stream().filter(lot -> lotNumber.equals(lot.lotNumber))
                .map((Lot lot) -> lot.branchId).toList();
    }
}
