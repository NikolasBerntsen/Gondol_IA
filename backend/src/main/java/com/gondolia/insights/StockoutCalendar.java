package com.gondolia.insights;

import com.gondolia.analytics.AnalyticsSql;
import com.gondolia.domain.inventory.MovementType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Días sin stock de cada producto en una sucursal ({@code stockoutDays} de SPEC §8.2).
 * <p>
 * Reconstruye, lote por lote, cuántas unidades había al empezar y al terminar cada día a partir de la cantidad actual
 * y de los movimientos con lote (las ventas sin stock, con {@code lot_id} null, no mueven lotes). Un día cuenta como
 * faltante si el producto no tuvo stock vendible (lotes sin vencer con unidades) ni al empezar ni al terminar el día
 * y no registró ventas netas: esas ventas en 0 no muestran la demanda (demanda censurada) y la IA no las toma como una
 * caída de la venta. Se omiten los días anteriores a la primera vez que el producto tuvo stock o ventas en la ventana.
 */
@Component
@RequiredArgsConstructor
public class StockoutCalendar {

    private static final String INBOUND_TYPES = Arrays.stream(MovementType.values())
            .filter(MovementType::isInbound)
            .map(type -> "'" + type.name() + "'")
            .collect(Collectors.joining(", "));

    private final NamedParameterJdbcTemplate jdbc;

    /** Lote con su cantidad actual y la variación neta (entradas − salidas) de cada día con movimientos. */
    record LotHistory(long productId, int quantityNow, LocalDate expiryDate, LocalDate receivedDay,
                      Map<LocalDate, Integer> deltas) {
    }

    /**
     * Días de {@code [from, to]} sin stock y sin ventas de los productos de la sucursal ({@code productId} null =
     * todos). {@code salesDays}: días con ventas netas positivas de cada producto.
     */
    public Map<Long, List<LocalDate>> stockoutDays(long tenantId, long branchId, Long productId, LocalDate from,
                                                   LocalDate to, ZoneId zone, Map<Long, Set<LocalDate>> salesDays) {
        if (to.isBefore(from)) {
            return Map.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("branchId", branchId)
                .addValue("zone", zone.getId())
                .addValue("from", OffsetDateTime.ofInstant(from.atStartOfDay(zone).toInstant(), ZoneOffset.UTC));
        String movementFilter = "";
        String lotFilter = "";
        if (productId != null) {
            params.addValue("productId", productId);
            movementFilter = " and m.product_id = :productId";
            lotFilter = " and l.product_id = :productId";
        }

        Map<Long, Map<LocalDate, Integer>> deltas = new HashMap<>();
        jdbc.query("""
                select m.lot_id, (m.occurred_at at time zone :zone)::date as day,
                       sum(case when m.type in (%s) then m.quantity else -m.quantity end) as delta
                from stock_movements m
                where m.tenant_id = :tenantId and m.branch_id = :branchId and m.lot_id is not null
                  and m.occurred_at >= :from%s
                group by 1, 2
                """.formatted(INBOUND_TYPES, movementFilter), params, rs -> {
                    deltas.computeIfAbsent(rs.getLong("lot_id"), key -> new HashMap<>())
                            .merge(rs.getObject("day", LocalDate.class), rs.getInt("delta"), Integer::sum);
                });

        List<LotHistory> lots = new ArrayList<>();
        jdbc.query("""
                select l.id, l.product_id, l.quantity, l.expiry_date, l.received_at
                from lots l
                where l.tenant_id = :tenantId and l.branch_id = :branchId%s
                """.formatted(lotFilter), params, rs -> {
                    long lotId = rs.getLong("id");
                    Map<LocalDate, Integer> lotDeltas = deltas.get(lotId);
                    int quantity = rs.getInt("quantity");
                    if (quantity <= 0 && lotDeltas == null) {
                        return;
                    }
                    Instant received = AnalyticsSql.instant(rs, "received_at");
                    lots.add(new LotHistory(rs.getLong("product_id"), quantity,
                            rs.getObject("expiry_date", LocalDate.class),
                            received == null ? null : received.atZone(zone).toLocalDate(),
                            lotDeltas == null ? Map.of() : lotDeltas));
                });
        return compute(lots, salesDays, from, to);
    }

    /** Cálculo puro (sin base de datos) de {@link #stockoutDays}. */
    static Map<Long, List<LocalDate>> compute(Collection<LotHistory> lots, Map<Long, Set<LocalDate>> salesDays,
                                              LocalDate from, LocalDate to) {
        int days = (int) ChronoUnit.DAYS.between(from, to) + 1;
        if (days <= 0) {
            return Map.of();
        }
        Map<Long, long[]> opening = new LinkedHashMap<>();
        Map<Long, long[]> closing = new LinkedHashMap<>();
        for (LotHistory lot : lots) {
            long[] start = opening.computeIfAbsent(lot.productId(), key -> new long[days]);
            long[] end = closing.computeIfAbsent(lot.productId(), key -> new long[days]);
            // Cantidad actual menos lo que se movió después de "to" (hoy): cantidad al terminar el día "to".
            long running = lot.quantityNow();
            for (Map.Entry<LocalDate, Integer> entry : lot.deltas().entrySet()) {
                if (entry.getKey().isAfter(to)) {
                    running -= entry.getValue();
                }
            }
            for (int i = days - 1; i >= 0; i--) {
                LocalDate day = from.plusDays(i);
                long atEnd = running;
                running -= lot.deltas().getOrDefault(day, 0);
                boolean sellable = (lot.expiryDate() == null || !lot.expiryDate().isBefore(day))
                        && (lot.receivedDay() == null || !day.isBefore(lot.receivedDay()));
                if (sellable) {
                    start[i] += Math.max(0, running);
                    end[i] += Math.max(0, atEnd);
                }
            }
        }

        Map<Long, List<LocalDate>> result = new LinkedHashMap<>();
        opening.forEach((productId, start) -> {
            long[] end = closing.get(productId);
            Set<LocalDate> sold = salesDays.getOrDefault(productId, Set.of());
            List<LocalDate> stockout = new ArrayList<>();
            boolean seen = false;
            for (int i = 0; i < days; i++) {
                LocalDate day = from.plusDays(i);
                boolean hasStock = start[i] > 0 || end[i] > 0;
                boolean hasSales = sold.contains(day);
                if (!seen) {
                    seen = hasStock || hasSales;
                    if (!seen) {
                        continue;
                    }
                }
                if (!hasStock && !hasSales) {
                    stockout.add(day);
                }
            }
            if (!stockout.isEmpty()) {
                result.put(productId, List.copyOf(stockout));
            }
        });
        return result;
    }
}
