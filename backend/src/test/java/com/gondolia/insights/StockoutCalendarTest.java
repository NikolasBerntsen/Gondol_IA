package com.gondolia.insights;

import static org.assertj.core.api.Assertions.assertThat;

import com.gondolia.insights.StockoutCalendar.LotHistory;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Reconstrucción de los días sin stock (demanda censurada) a partir de la cantidad actual y los movimientos. */
class StockoutCalendarTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 19);
    private static final LocalDate FROM = day(-15);
    private static final LocalDate TO = day(-1);
    private static final long MILK = 122L;

    @Test
    void daysAfterTheLastUnitWasSoldAreStockoutDays() {
        // 20 u. que entraron hace 10 días y se vendieron de a 5 hasta hace 7 días: desde ahí no hay stock.
        LotHistory lot = lot(MILK, 0, null, day(-10), Map.of(day(-10), 20 - 5, day(-9), -5, day(-8), -5, day(-7), -5));
        Map<Long, Set<LocalDate>> sales = Map.of(MILK, days(-10, -7));

        Map<Long, List<LocalDate>> result = StockoutCalendar.compute(List.of(lot), sales, FROM, TO);

        // Los días previos a la primera vez que hubo stock no se informan.
        assertThat(result.get(MILK)).containsExactlyElementsOf(sorted(days(-6, -1)));
    }

    @Test
    void aRestockEndsTheStockoutEvenIfNothingSoldThatDay() {
        LotHistory first = lot(MILK, 0, null, day(-10), Map.of(day(-10), 20, day(-7), -20));
        // Transferencia que llegó hace 3 días (a última hora, sin ventas ese día) con fecha de ingreso original.
        LotHistory transferred = lot(MILK, 6, null, day(-40), Map.of(day(-3), 10, day(-2), -2, day(-1), -2));
        Map<Long, Set<LocalDate>> sales = Map.of(MILK, Set.of(day(-7), day(-2), day(-1)));

        Map<Long, List<LocalDate>> result = StockoutCalendar.compute(List.of(first, transferred), sales, FROM, TO);

        assertThat(result.get(MILK)).containsExactly(day(-6), day(-5), day(-4));
    }

    @Test
    void expiredUnitsAreNotSellableStock() {
        // Quedan 5 u. de un lote que venció hace 4 días: desde el día siguiente al vencimiento no hay stock vendible.
        LotHistory expired = lot(MILK, 5, day(-4), day(-20), Map.of(day(-6), -3));
        Map<Long, Set<LocalDate>> sales = Map.of(MILK, Set.of(day(-6)));

        Map<Long, List<LocalDate>> result = StockoutCalendar.compute(List.of(expired), sales, FROM, TO);

        assertThat(result.get(MILK)).containsExactly(day(-3), day(-2), day(-1));
    }

    @Test
    void salesWithoutStockAndMovementsAfterTheWindowAreHandled() {
        // Hoy (fuera de la ventana) entraron 12 u.: ayer y antes el lote estaba vacío.
        LotHistory lot = lot(MILK, 12, null, day(-12), Map.of(day(-12), 4, day(-11), -4, day(0), 12));
        // Día -5: venta registrada sin stock (faltante, sin lote): la demanda se vio, no es un día censurado.
        Map<Long, Set<LocalDate>> sales = Map.of(MILK, Set.of(day(-11), day(-5)));

        List<LocalDate> result = StockoutCalendar.compute(List.of(lot), sales, FROM, TO).get(MILK);

        assertThat(result).contains(day(-10), day(-6), day(-4), day(-1)).doesNotContain(day(-12), day(-11), day(-5));
        assertThat(result).hasSize(9);
    }

    @Test
    void productsThatAlwaysHadStockAreNotReported() {
        LotHistory lot = lot(MILK, 30, null, day(-60), Map.of(day(-5), -2));
        assertThat(StockoutCalendar.compute(List.of(lot), Map.of(), FROM, TO)).isEmpty();
    }

    // ------------------------------------------------------------------ apoyo

    private static LocalDate day(int offset) {
        return TODAY.plusDays(offset);
    }

    private static Set<LocalDate> days(int from, int to) {
        return Set.copyOf(IntStream.rangeClosed(from, to).mapToObj(StockoutCalendarTest::day).toList());
    }

    private static List<LocalDate> sorted(Set<LocalDate> days) {
        return days.stream().sorted().toList();
    }

    private static LotHistory lot(long productId, int quantityNow, LocalDate expiry, LocalDate received,
                                  Map<LocalDate, Integer> deltas) {
        return new LotHistory(productId, quantityNow, expiry, received, new HashMap<>(deltas));
    }
}
