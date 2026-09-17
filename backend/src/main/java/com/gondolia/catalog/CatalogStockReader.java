package com.gondolia.catalog;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * </ul>
 * Se usa {@code JdbcTemplate} (SPEC §5.2) porque hace falta un agregado condicional por producto y sucursal que no
 * expone el núcleo.
 */
@Component
@RequiredArgsConstructor
public class CatalogStockReader {

    /**
     * Stock de un producto sumado sobre el alcance. {@code sellableByBranch} solo trae las sucursales con stock
     * vendible; el resto se considera 0.
     */
    public record ProductStock(int sellableStock, int expiredStock, int quarantinedStock, LocalDate nextExpiryDate,
                               int lotsCount, Map<Long, Integer> sellableByBranch) {

        public static final ProductStock EMPTY = new ProductStock(0, 0, 0, null, 0, Map.of());

        public int sellableIn(Long branchId) {
            return sellableByBranch.getOrDefault(branchId, 0);
        }
    }

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
                       where l.status = 'ACTIVE' and l.expiry_date >= :today)                       as next_expiry,
                   count(*) filter (where l.status in ('ACTIVE', 'RECALLED'))                       as lots_count
              from lots l
             where l.tenant_id = :tenantId
               and l.branch_id in (:branchIds)
               and l.quantity > 0
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    /** Stock de todos los productos del comercio en el alcance indicado. */
    public Map<Long, ProductStock> statsByProduct(Long tenantId, Collection<Long> branchIds) {
        return statsByProduct(tenantId, branchIds, null);
    }

    /**
     * Stock por producto en el alcance indicado. {@code productIds} null = todos los productos del comercio;
     * una lista vacía devuelve un mapa vacío sin consultar.
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

    private static final class Accumulator {
        private int sellable;
        private int expired;
        private int quarantined;
        private int lots;
        private LocalDate nextExpiry;
        private final Map<Long, Integer> byBranch = new HashMap<>();

        private ProductStock toStock() {
            return new ProductStock(sellable, expired, quarantined, nextExpiry, lots, Map.copyOf(byBranch));
        }
    }
}
