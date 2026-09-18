package com.gondolia.pos;

import com.gondolia.domain.pos.PaymentMethod;
import com.gondolia.pos.dto.PosStatsDto;
import com.gondolia.pos.dto.PosTopProductDto;
import com.gondolia.security.BranchAccessService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Estadísticas del POS GondolIA (SPEC §15.2): ventas por medio de pago, por hora del día, por cajero, ticket
 * promedio, productos más vendidos y anulaciones. Siempre dentro del alcance de sucursales del usuario.
 */
@Service
@RequiredArgsConstructor
public class PosStatsService {

    private final NamedParameterJdbcTemplate jdbc;
    private final BranchAccessService branchAccess;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PosStatsDto stats(Long tenantId, int days) {
        List<Long> branchIds = branchAccess.scopeBranchIds();
        LocalDate to = LocalDate.now(clock);
        LocalDate from = to.minusDays(Math.max(days, 1) - 1L);
        if (branchIds.isEmpty()) {
            return empty(days, from, to);
        }
        Instant since = from.atStartOfDay(clock.getZone()).toInstant();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("branchIds", branchIds)
                .addValue("since", Timestamp.from(since))
                .addValue("zone", clock.getZone().getId());

        Totals totals = jdbc.queryForObject("""
                select coalesce(count(*) filter (where status = 'COMPLETED'), 0) as sales,
                       coalesce(sum(total) filter (where status = 'COMPLETED'), 0) as total,
                       coalesce(sum(units) filter (where status = 'COMPLETED'), 0) as units,
                       coalesce(count(*) filter (where status = 'VOIDED'), 0) as voided,
                       coalesce(sum(total) filter (where status = 'VOIDED'), 0) as voided_total
                  from pos_sales
                 where tenant_id = :tenantId and branch_id in (:branchIds) and created_at >= :since
                """, params, (rs, row) -> new Totals(rs.getInt("sales"), rs.getBigDecimal("total"),
                rs.getInt("units"), rs.getInt("voided"), rs.getBigDecimal("voided_total")));
        Totals resolved = totals == null ? new Totals(0, BigDecimal.ZERO, 0, 0, BigDecimal.ZERO) : totals;

        List<PosStatsDto.MethodTotal> byMethod = jdbc.query("""
                select p.method as method, coalesce(sum(p.amount), 0) as total, count(distinct s.id) as sales
                  from pos_payments p
                  join pos_sales s on s.id = p.sale_id
                 where s.tenant_id = :tenantId and s.branch_id in (:branchIds) and s.created_at >= :since
                   and s.status = 'COMPLETED'
                 group by p.method
                 order by total desc
                """, params, (rs, row) -> {
            PaymentMethod method = PaymentMethod.valueOf(rs.getString("method"));
            return new PosStatsDto.MethodTotal(method, PaymentMethods.label(method),
                    PosMoney.orZero(rs.getBigDecimal("total")), rs.getInt("sales"), PosMoney.ZERO);
        });
        BigDecimal methodsTotal = byMethod.stream().map(PosStatsDto.MethodTotal::total)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<PosStatsDto.MethodTotal> methodShares = new ArrayList<>(byMethod.size());
        for (PosStatsDto.MethodTotal method : byMethod) {
            BigDecimal share = methodsTotal.signum() == 0 ? PosMoney.ZERO
                    : method.total().multiply(BigDecimal.valueOf(100))
                            .divide(methodsTotal, 1, RoundingMode.HALF_UP);
            methodShares.add(new PosStatsDto.MethodTotal(method.method(), method.label(), method.total(),
                    method.sales(), share));
        }

        List<PosStatsDto.HourBucket> byHour = jdbc.query("""
                select extract(hour from (created_at at time zone :zone))::int as hour,
                       count(*) as sales, coalesce(sum(total), 0) as total
                  from pos_sales
                 where tenant_id = :tenantId and branch_id in (:branchIds) and created_at >= :since
                   and status = 'COMPLETED'
                 group by 1
                 order by 1
                """, params, (rs, row) -> new PosStatsDto.HourBucket(rs.getInt("hour"), rs.getInt("sales"),
                PosMoney.orZero(rs.getBigDecimal("total"))));

        List<PosStatsDto.CashierTotal> byCashier = jdbc.query("""
                select s.cashier_id as user_id, coalesce(u.full_name, 'Sin cajero') as name,
                       count(*) filter (where s.status = 'COMPLETED') as sales,
                       coalesce(sum(s.total) filter (where s.status = 'COMPLETED'), 0) as total,
                       count(*) filter (where s.status = 'VOIDED') as voided
                  from pos_sales s
                  left join users u on u.id = s.cashier_id
                 where s.tenant_id = :tenantId and s.branch_id in (:branchIds) and s.created_at >= :since
                 group by s.cashier_id, u.full_name
                 order by total desc
                """, params, (rs, row) -> {
            int sales = rs.getInt("sales");
            BigDecimal total = PosMoney.orZero(rs.getBigDecimal("total"));
            return new PosStatsDto.CashierTotal((Long) rs.getObject("user_id"), rs.getString("name"), sales, total,
                    sales == 0 ? PosMoney.ZERO : PosMoney.perUnit(total, sales), rs.getInt("voided"));
        });

        List<PosTopProductDto> topProducts = jdbc.query("""
                select i.product_id as product_id, i.product_name as product_name,
                       coalesce(sum(i.quantity), 0) as units, coalesce(sum(i.line_total), 0) as total
                  from pos_sale_items i
                  join pos_sales s on s.id = i.sale_id
                 where s.tenant_id = :tenantId and s.branch_id in (:branchIds) and s.created_at >= :since
                   and s.status = 'COMPLETED'
                 group by i.product_id, i.product_name
                 order by units desc, total desc
                 limit 8
                """, params, (rs, row) -> new PosTopProductDto((Long) rs.getObject("product_id"),
                rs.getString("product_name"), rs.getInt("units"), PosMoney.orZero(rs.getBigDecimal("total"))));

        BigDecimal salesTotal = PosMoney.orZero(resolved.total());
        BigDecimal average = resolved.sales() == 0 ? PosMoney.ZERO : PosMoney.perUnit(salesTotal, resolved.sales());
        return new PosStatsDto(days, from, to, resolved.sales(), salesTotal, resolved.units(), average,
                resolved.voided(), PosMoney.orZero(resolved.voidedTotal()), methodShares, byHour, byCashier,
                topProducts);
    }

    private PosStatsDto empty(int days, LocalDate from, LocalDate to) {
        return new PosStatsDto(days, from, to, 0, PosMoney.ZERO, 0, PosMoney.ZERO, 0, PosMoney.ZERO, List.of(),
                List.of(), List.of(), List.of());
    }

    private record Totals(int sales, BigDecimal total, int units, int voided, BigDecimal voidedTotal) {
    }
}
