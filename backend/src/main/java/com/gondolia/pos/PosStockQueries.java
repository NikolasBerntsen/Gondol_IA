package com.gondolia.pos;

import com.gondolia.domain.tenant.StockRotation;
import java.math.BigDecimal;
import java.sql.Date;
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
 * Consultas de stock por sucursal que necesita el mostrador del POS: cuánto hay vendible, cuánto está vencido o en
 * cuarentena por recall y cuál es el próximo lote que sale (orden de rotación de SPEC §4.2).
 * <p>
 * Son lecturas agregadas para varios productos a la vez (el mostrador muestra hasta 20 mosaicos): se resuelven con
 * dos consultas en lugar de una por producto.
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

    /** Próximo lote a consumir de un producto en una sucursal. */
    public record NextLot(Long lotId, String lotNumber, LocalDate expiryDate, BigDecimal discountPct) {
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
     * Primer lote vendible de cada producto en el orden en el que se va a vender: primero los lotes en liquidación
     * ({@code discount_pct} activo) y dentro de cada grupo FIFO o FEFO según el comercio (SPEC §4.2).
     */
    public Map<Long, NextLot> nextLotByProduct(Long tenantId, Long branchId, Collection<Long> productIds,
                                               LocalDate today, StockRotation rotation) {
        Map<Long, NextLot> result = new HashMap<>();
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
                select distinct on (product_id) product_id, id, lot_number, expiry_date, discount_pct
                  from lots
                 where tenant_id = :tenantId and branch_id = :branchId and status = 'ACTIVE' and quantity > 0
                   and (expiry_date is null or expiry_date >= :today)
                   and product_id in (:productIds)
                 order by product_id,
                          case when discount_pct is null or discount_pct <= 0 then 1 else 0 end asc,
                          %s
                """.formatted(rotationOrder), params, rs -> {
            Date expiry = rs.getDate("expiry_date");
            result.put(rs.getLong("product_id"), new NextLot(rs.getLong("id"), rs.getString("lot_number"),
                    expiry == null ? null : expiry.toLocalDate(), rs.getBigDecimal("discount_pct")));
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
