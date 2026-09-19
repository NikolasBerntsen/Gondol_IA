package com.gondolia.pos;

import com.gondolia.domain.tenant.StockRotation;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Consultas de stock por sucursal que necesita el mostrador del POS: cuánto hay vendible, cuánto está vencido o en
 * cuarentena por recall, en qué orden salen los lotes (rotación de SPEC §4.2) y si hay un recall vigente del producto.
 * <p>
 * Son lecturas agregadas para varios productos a la vez (el mostrador muestra hasta 20 mosaicos): se resuelven con
 * pocas consultas en lugar de una por producto.
 */
@Component
@RequiredArgsConstructor
public class PosStockQueries {

    /** Stock de un producto en una sucursal, separado por estado. */
    public record BranchStock(int sellable, int expired, int quarantined) {

        public static final BranchStock EMPTY = new BranchStock(0, 0, 0);
    }

    /** Categoría con la cantidad de productos vendibles en la sucursal. */
    public record CategoryCount(Long id, String name, int productCount) {
    }

    /** Lote vendible de un producto en una sucursal, con su remanente y su descuento de liquidación. */
    public record SellableLot(Long lotId, String lotNumber, LocalDate expiryDate, int quantity,
                              BigDecimal discountPct) {
    }

    /** Recall publicado que alcanza al código de barras de un producto (SPEC §6.7). */
    public record ActiveRecall(Long announcementId, String title, boolean allLots, List<String> lotNumbers) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    /** Stock vendible, vencido pendiente y en cuarentena por producto (solo los que tienen lotes con remanente). */
    public Map<Long, BranchStock> stockByProduct(Long tenantId, Long branchId, Collection<Long> productIds,
                                                 LocalDate today) {
        Map<Long, BranchStock> result = new HashMap<>();
        if (productIds.isEmpty()) {
            return result;
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("branchId", branchId)
                .addValue("productIds", productIds)
                .addValue("today", Date.valueOf(today));
        jdbc.query("""
                select product_id,
                       coalesce(sum(case when status = 'ACTIVE' and (expiry_date is null or expiry_date >= :today)
                                         then quantity else 0 end), 0) as sellable,
                       coalesce(sum(case when status = 'ACTIVE' and expiry_date < :today
                                         then quantity else 0 end), 0) as expired,
                       coalesce(sum(case when status = 'RECALLED' then quantity else 0 end), 0) as quarantined
                  from lots
                 where tenant_id = :tenantId and branch_id = :branchId and quantity > 0
                   and product_id in (:productIds)
                 group by product_id
                """, params, rs -> {
            result.put(rs.getLong("product_id"), new BranchStock(rs.getInt("sellable"), rs.getInt("expired"),
                    rs.getInt("quarantined")));
        });
        return result;
    }

    /**
     * Lotes vendibles de cada producto en el orden en el que se van a vender, el mismo que usa
     * {@code StockService.registerSale}: primero los lotes en liquidación ({@code discount_pct} activo) y dentro de
     * cada grupo FIFO o FEFO según el comercio (SPEC §4.2). El primero es el "próximo lote"; con todos, el mostrador
     * puede calcular el precio real de cualquier cantidad aunque cruce de un lote en liquidación a uno sin descuento.
     */
    public Map<Long, List<SellableLot>> sellableLotsByProduct(Long tenantId, Long branchId,
                                                              Collection<Long> productIds, LocalDate today,
                                                              StockRotation rotation) {
        Map<Long, List<SellableLot>> result = new HashMap<>();
        if (productIds.isEmpty()) {
            return result;
        }
        String rotationOrder = rotation == StockRotation.FEFO
                ? "expiry_date asc nulls last, received_at asc, id asc"
                : "received_at asc, id asc";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("branchId", branchId)
                .addValue("productIds", productIds)
                .addValue("today", Date.valueOf(today));
        jdbc.query("""
                select product_id, id, lot_number, expiry_date, quantity, discount_pct
                  from lots
                 where tenant_id = :tenantId and branch_id = :branchId and status = 'ACTIVE' and quantity > 0
                   and (expiry_date is null or expiry_date >= :today)
                   and product_id in (:productIds)
                 order by product_id,
                          case when discount_pct is null or discount_pct <= 0 then 1 else 0 end asc,
                          %s
                """.formatted(rotationOrder), params, rs -> {
            Date expiry = rs.getDate("expiry_date");
            result.computeIfAbsent(rs.getLong("product_id"), id -> new ArrayList<>())
                    .add(new SellableLot(rs.getLong("id"), rs.getString("lot_number"),
                            expiry == null ? null : expiry.toLocalDate(), rs.getInt("quantity"),
                            rs.getBigDecimal("discount_pct")));
        });
        return result;
    }

    /**
     * Recall {@code PUBLISHED} más reciente que alcanza al código de barras de cada producto, con los lotes que
     * nombra. Los lotes cargados ya pasaron por el chequeo de recall (los que coinciden quedan {@code RECALLED}); lo
     * que no se puede verificar es una unidad sin lote registrado, y eso es lo que el mostrador bloquea.
     */
    public Map<Long, ActiveRecall> activeRecallsByProduct(Long tenantId, Collection<Long> productIds) {
        Map<Long, ActiveRecall> result = new HashMap<>();
        if (productIds.isEmpty()) {
            return result;
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("productIds", productIds);
        jdbc.query("""
                select p.id as product_id, a.id as announcement_id, a.title as title,
                       a.recall_all_lots as all_lots,
                       array(select coalesce(rl.lot_number, rl.lot_number_normalized)
                               from announcement_recall_lots rl
                              where rl.announcement_id = a.id
                              order by rl.id) as lot_numbers
                  from products p
                  join announcements a on a.kind = 'RECALL' and a.status = 'PUBLISHED'
                       and a.recall_barcode = p.barcode
                 where p.tenant_id = :tenantId and p.id in (:productIds) and p.barcode is not null
                 order by p.id, a.published_at desc nulls last, a.id desc
                """, params, rs -> {
            java.sql.Array lots = rs.getArray("lot_numbers");
            List<String> lotNumbers = lots == null ? List.of()
                    : Arrays.stream((String[]) lots.getArray()).filter(Objects::nonNull).toList();
            result.putIfAbsent(rs.getLong("product_id"), new ActiveRecall(rs.getLong("announcement_id"),
                    rs.getString("title"), rs.getBoolean("all_lots"), lotNumbers));
        });
        return result;
    }

    /** Categorías con al menos un producto activo con stock vendible en la sucursal. */
    public List<CategoryCount> categoriesWithStock(Long tenantId, Long branchId, LocalDate today) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("branchId", branchId)
                .addValue("today", Date.valueOf(today));
        return jdbc.query("""
                select c.id as id, c.name as name, count(distinct p.id) as products
                  from categories c
                  join products p on p.category_id = c.id and p.tenant_id = c.tenant_id and p.active
                  join lots l on l.product_id = p.id and l.tenant_id = c.tenant_id and l.branch_id = :branchId
                       and l.status = 'ACTIVE' and l.quantity > 0
                       and (l.expiry_date is null or l.expiry_date >= :today)
                 where c.tenant_id = :tenantId
                 group by c.id, c.name
                 order by c.name
                """, params, (rs, row) ->
                new CategoryCount(rs.getLong("id"), rs.getString("name"), rs.getInt("products")));
    }
}
