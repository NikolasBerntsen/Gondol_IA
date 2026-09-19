package com.gondolia.catalog;

import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Agregados de stock por producto sobre un alcance de sucursales (SPEC §4.2), en una sola consulta.
 * <ul>
 *   <li><b>Vendible</b>: lotes {@code ACTIVE} con remanente y sin vencer.</li>
 *   <li><b>Vencido pendiente</b>: lotes {@code ACTIVE} con remanente y vencimiento anterior a hoy.</li>
 *   <li><b>En cuarentena</b>: lotes {@code RECALLED} con remanente.</li>
 *   <li><b>Lotes</b>: lotes con remanente ({@code ACTIVE} o {@code RECALLED}), es decir el stock físico.</li>
 *   <li><b>Sucursales que manejan el producto</b>: las que tienen o tuvieron algún lote de él (cualquier estado o
 *       remanente). Es la misma regla que usa el Inicio para "Artículos a reponer" y el contador "sin stock"
 *       ({@code DashboardService.reorderRowsSql}, CTE {@code handled}): una sucursal que nunca recibió el producto no
 *       lo tiene "sin stock", simplemente no lo trabaja.</li>
 * </ul>
 * Se usa {@code JdbcTemplate} (SPEC §5.2) porque hace falta un agregado condicional por producto y sucursal que no
 * expone el núcleo.
 */
@Component
@RequiredArgsConstructor
public class CatalogStockReader {

    /**
     * Stock de un producto sumado sobre el alcance. {@code sellableByBranch} solo trae las sucursales con stock
     * vendible; el resto se considera 0. {@code handledBranches} son las sucursales del alcance donde el producto
     * tiene o tuvo lotes.
     */
    public record ProductStock(int sellableStock, int expiredStock, int quarantinedStock, LocalDate nextExpiryDate,
                               int lotsCount, Map<Long, Integer> sellableByBranch, Set<Long> handledBranches) {

        public static final ProductStock EMPTY = new ProductStock(0, 0, 0, null, 0, Map.of(), Set.of());

        public ProductStock {
            sellableByBranch = sellableByBranch == null ? Map.of() : Map.copyOf(sellableByBranch);
            handledBranches = handledBranches == null ? Set.of() : Set.copyOf(handledBranches);
        }

        public int sellableIn(Long branchId) {
            return sellableByBranch.getOrDefault(branchId, 0);
        }

        /** {@code true} si el comercio maneja el producto en esa sucursal (tiene o tuvo lotes ahí). */
        public boolean handledIn(Long branchId) {
            return handledBranches.contains(branchId);
        }
    }

    /*
     * Recorre también los lotes agotados: cada grupo (producto, sucursal) que devuelve es una sucursal que maneja el
     * producto. Por eso el remanente se filtra dentro de cada agregado y no en el WHERE.
     */
    private static final String SQL = """
            select l.product_id                                                                     as product_id,
                   l.branch_id                                                                      as branch_id,
                   coalesce(sum(l.quantity) filter (
                       where l.status = 'ACTIVE' and (l.expiry_date is null or l.expiry_date >= :today)), 0)
                                                                                                    as sellable,
                   coalesce(sum(l.quantity) filter (
                       where l.status = 'ACTIVE' and l.expiry_date < :today), 0)                    as expired,
                   coalesce(sum(l.quantity) filter (where l.status = 'RECALLED'), 0)                as quarantined,
                   min(l.expiry_date) filter (
                       where l.status = 'ACTIVE' and l.quantity > 0 and l.expiry_date >= :today)    as next_expiry,
                   count(*) filter (where l.status in ('ACTIVE', 'RECALLED') and l.quantity > 0)    as lots_count
              from lots l
             where l.tenant_id = :tenantId
               and l.branch_id in (:branchIds)
            """;

    /**
     * Lotes del producto de los que salió stock por algo que no es una venta desde {@code :since}: retiro por recall,
     * descarte, ajuste o transferencia. Un lote que simplemente se vendió entero no cuenta (si no, un producto de
     * alta rotación llenaría la ficha de lotes agotados).
     */
    private static final String RECENTLY_WITHDRAWN_LOTS_SQL = """
            select distinct m.lot_id
              from stock_movements m
             where m.tenant_id = :tenantId
               and m.branch_id in (:branchIds)
               and m.product_id = :productId
               and m.lot_id is not null
               and m.type in ('RECALL_REMOVAL', 'WASTE_EXPIRED', 'WASTE_DAMAGED', 'ADJUSTMENT_OUT', 'TRANSFER_OUT')
               and m.occurred_at >= :since
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    /** Stock de todos los productos del comercio en el alcance indicado. */
    public Map<Long, ProductStock> statsByProduct(Long tenantId, Collection<Long> branchIds) {
        return statsByProduct(tenantId, branchIds, null);
    }

    /**
     * Stock por producto en el alcance indicado. {@code productIds} null = todos los productos del comercio;
     * una lista vacía devuelve un mapa vacío sin consultar. Un producto que no está en el mapa nunca tuvo lotes en
     * el alcance.
     */
    public Map<Long, ProductStock> statsByProduct(Long tenantId, Collection<Long> branchIds,
                                                  Collection<Long> productIds) {
        if (tenantId == null || branchIds == null || branchIds.isEmpty()
                || (productIds != null && productIds.isEmpty())) {
            return Map.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("branchIds", branchIds)
                .addValue("today", Date.valueOf(LocalDate.now(clock)));
        String sql = SQL;
        if (productIds != null) {
            sql += " and l.product_id in (:productIds)\n";
            params.addValue("productIds", productIds);
        }
        sql += " group by l.product_id, l.branch_id";

        Map<Long, Accumulator> accumulators = new HashMap<>();
        jdbc.query(sql, params, rs -> {
            long productId = rs.getLong("product_id");
            long branchId = rs.getLong("branch_id");
            Accumulator acc = accumulators.computeIfAbsent(productId, id -> new Accumulator());
            acc.handled.add(branchId);
            int sellable = rs.getInt("sellable");
            acc.sellable += sellable;
            acc.expired += rs.getInt("expired");
            acc.quarantined += rs.getInt("quarantined");
            acc.lots += rs.getInt("lots_count");
            Date nextExpiry = rs.getDate("next_expiry");
            if (nextExpiry != null) {
                LocalDate value = nextExpiry.toLocalDate();
                if (acc.nextExpiry == null || value.isBefore(acc.nextExpiry)) {
                    acc.nextExpiry = value;
                }
            }
            if (sellable > 0) {
                acc.byBranch.put(branchId, sellable);
            }
        });
        Map<Long, ProductStock> result = new HashMap<>();
        accumulators.forEach((productId, acc) -> result.put(productId, acc.toStock()));
        return result;
    }

    /** Stock de un solo producto en el alcance (mismo cálculo que la lista). */
    public ProductStock stats(Long tenantId, Collection<Long> branchIds, Long productId) {
        return statsByProduct(tenantId, branchIds, List.of(productId)).getOrDefault(productId, ProductStock.EMPTY);
    }

    /**
     * Ids de los lotes del producto en el alcance de los que salió stock desde {@code since} por un retiro por
     * recall, un descarte, un ajuste o una transferencia. La ficha los sigue mostrando aunque hayan quedado en 0: un
     * lote retirado hoy por un recall es "reciente" aunque haya ingresado hace meses (SPEC §6.3).
     */
    public Set<Long> recentlyWithdrawnLotIds(Long tenantId, Collection<Long> branchIds, Long productId,
                                             Instant since) {
        if (tenantId == null || productId == null || branchIds == null || branchIds.isEmpty()) {
            return Set.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("branchIds", branchIds)
                .addValue("productId", productId)
                .addValue("since", OffsetDateTime.ofInstant(since, ZoneOffset.UTC));
        return new HashSet<>(jdbc.queryForList(RECENTLY_WITHDRAWN_LOTS_SQL, params, Long.class));
    }

    private static final class Accumulator {
        private int sellable;
        private int expired;
        private int quarantined;
        private int lots;
        private LocalDate nextExpiry;
        private final Map<Long, Integer> byBranch = new HashMap<>();
        private final Set<Long> handled = new HashSet<>();

        private ProductStock toStock() {
            return new ProductStock(sellable, expired, quarantined, nextExpiry, lots, byBranch, handled);
        }
    }
}
