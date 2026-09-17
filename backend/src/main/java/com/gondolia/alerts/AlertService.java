package com.gondolia.alerts;

import com.gondolia.alerts.dto.AlertCountsDto;
import com.gondolia.alerts.dto.AlertDto;
import com.gondolia.analytics.BranchScopeService.Scope;
import com.gondolia.common.PageResponse;
import com.gondolia.common.error.NotFoundException;
import com.gondolia.domain.alert.Alert;
import com.gondolia.domain.alert.AlertRepository;
import com.gondolia.domain.alert.AlertStatus;
import com.gondolia.domain.alert.AlertType;
import com.gondolia.domain.common.Severity;
import com.gondolia.security.BranchAccessService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bandeja de alertas del comercio (SPEC §6.5): listado por alcance de sucursales y cambios de estado
 * (vista, resuelta, descartada) que solo puede hacer el administrador.
 */
@Service
@RequiredArgsConstructor
public class AlertService {

    static final String MSG_NOT_FOUND = "No encontramos esa alerta";

    private static final String SELECT_ALERT = """
            select a.id, a.branch_id, a.type, a.severity, a.status, a.product_id, p.name as product_name,
                   a.lot_id, l.lot_number, a.announcement_id, a.title, a.message, a.created_at, a.updated_at,
                   u.full_name as handled_by_name, a.resolved_at
            from alerts a
            left join products p on p.id = a.product_id
            left join lots l on l.id = a.lot_id
            left join users u on u.id = a.handled_by
            """;

    private final AlertRepository alertRepository;
    private final BranchAccessService branchAccess;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    /** Filtro de la bandeja. {@code status} null = todas. */
    public record AlertQuery(AlertStatus status, AlertType type, Severity severity, Long productId, String q,
                             int page, int size) {
    }

    @Transactional(readOnly = true)
    public PageResponse<AlertDto> list(Long tenantId, Scope scope, AlertQuery query) {
        if (scope.isEmpty()) {
            return PageResponse.empty(query.page(), query.size());
        }
        MapSqlParameterSource params = filterParams(tenantId, scope, query);
        String where = whereClause(query);
        Long total = jdbc.queryForObject("select count(*) from alerts a " + where, params, Long.class);
        if (total == null || total == 0) {
            return PageResponse.empty(query.page(), query.size());
        }
        params.addValue("rowLimit", query.size()).addValue("rowOffset", (long) query.page() * query.size());
        List<AlertDto> rows = jdbc.query(SELECT_ALERT + where + """
                 order by case a.severity when 'CRITICAL' then 0 when 'WARNING' then 1 else 2 end,
                          a.created_at desc, a.id desc
                 limit :rowLimit offset :rowOffset
                """, params, (rs, rowNum) -> mapAlert(rs, scope));
        return PageResponse.of(rows, query.page(), query.size(), total);
    }

    @Transactional(readOnly = true)
    public AlertCountsDto counts(Long tenantId, Scope scope) {
        if (scope.isEmpty()) {
            return new AlertCountsDto(0, 0, 0, 0, 0, 0, 0, Map.of());
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("branchIds", scope.branchIds());
        String scopeWhere = """
                where a.tenant_id = :tenantId and (a.branch_id in (:branchIds) or a.branch_id is null)
                """;
        Map<String, Object> totals = jdbc.queryForMap("""
                select count(*) filter (where a.status = 'OPEN') as open_count,
                       count(*) filter (where a.status = 'ACKNOWLEDGED') as acknowledged_count,
                       count(*) filter (where a.status = 'RESOLVED') as resolved_count,
                       count(*) filter (where a.status = 'DISMISSED') as dismissed_count,
                       count(*) filter (where a.status in ('OPEN', 'ACKNOWLEDGED')
                                          and a.severity = 'CRITICAL') as critical_count,
                       count(*) filter (where a.status in ('OPEN', 'ACKNOWLEDGED')
                                          and a.severity = 'WARNING') as warning_count,
                       count(*) filter (where a.status in ('OPEN', 'ACKNOWLEDGED')
                                          and a.severity = 'INFO') as info_count
                from alerts a
                """ + scopeWhere, params);

        Map<String, Long> byType = new LinkedHashMap<>();
        jdbc.query("""
                select a.type, count(*) as row_count from alerts a
                """ + scopeWhere + " and a.status in ('OPEN', 'ACKNOWLEDGED') group by a.type",
                params, (rs, rowNum) -> Map.entry(rs.getString("type"), rs.getLong("row_count")))
                .forEach(entry -> byType.put(entry.getKey(), entry.getValue()));

        return new AlertCountsDto(number(totals.get("open_count")), number(totals.get("acknowledged_count")),
                number(totals.get("resolved_count")), number(totals.get("dismissed_count")),
                number(totals.get("critical_count")), number(totals.get("warning_count")),
                number(totals.get("info_count")), byType);
    }

    /** Marca la alerta como vista. */
    @Transactional
    public AlertDto acknowledge(Long tenantId, Long alertId, Long userId, Scope scope) {
        return transition(tenantId, alertId, userId, AlertStatus.ACKNOWLEDGED, scope);
    }

    /** Marca la alerta como resuelta. */
    @Transactional
    public AlertDto resolve(Long tenantId, Long alertId, Long userId, Scope scope) {
        return transition(tenantId, alertId, userId, AlertStatus.RESOLVED, scope);
    }

    /** Descarta la alerta (no vuelve a abrirse mientras la condición siga igual). */
    @Transactional
    public AlertDto dismiss(Long tenantId, Long alertId, Long userId, Scope scope) {
        return transition(tenantId, alertId, userId, AlertStatus.DISMISSED, scope);
    }

    private AlertDto transition(Long tenantId, Long alertId, Long userId, AlertStatus status, Scope scope) {
        Alert alert = alertRepository.findByIdAndTenantId(alertId, tenantId)
                .orElseThrow(() -> new NotFoundException(MSG_NOT_FOUND));
        if (alert.getBranchId() != null) {
            branchAccess.assertAccess(alert.getBranchId());
        }
        alert.setStatus(status);
        alert.setHandledBy(userId);
        alert.setResolvedAt(status == AlertStatus.ACKNOWLEDGED ? null : clock.instant());
        alertRepository.saveAndFlush(alert);
        return byId(tenantId, alertId, scope);
    }

    /** La alerta con los nombres de producto, lote y responsable ya resueltos. */
    @Transactional(readOnly = true)
    public AlertDto byId(Long tenantId, Long alertId, Scope scope) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("id", alertId);
        List<AlertDto> rows = jdbc.query(SELECT_ALERT + " where a.tenant_id = :tenantId and a.id = :id",
                params, (rs, rowNum) -> mapAlert(rs, scope));
        if (rows.isEmpty()) {
            throw new NotFoundException(MSG_NOT_FOUND);
        }
        return rows.getFirst();
    }

    private static AlertDto mapAlert(java.sql.ResultSet rs, Scope scope) throws java.sql.SQLException {
        Long branchId = (Long) rs.getObject("branch_id");
        return new AlertDto(rs.getLong("id"), branchId, scope.nameOf(branchId),
                AlertType.valueOf(rs.getString("type")), Severity.valueOf(rs.getString("severity")),
                AlertStatus.valueOf(rs.getString("status")), (Long) rs.getObject("product_id"),
                rs.getString("product_name"), (Long) rs.getObject("lot_id"), rs.getString("lot_number"),
                (Long) rs.getObject("announcement_id"), rs.getString("title"), rs.getString("message"),
                rs.getObject("created_at", Instant.class), rs.getObject("updated_at", Instant.class),
                rs.getString("handled_by_name"), rs.getObject("resolved_at", Instant.class));
    }

    private MapSqlParameterSource filterParams(Long tenantId, Scope scope, AlertQuery query) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("branchIds", scope.branchIds());
        if (query.status() != null) {
            params.addValue("status", query.status().name());
        }
        if (query.type() != null) {
            params.addValue("type", query.type().name());
        }
        if (query.severity() != null) {
            params.addValue("severity", query.severity().name());
        }
        if (query.productId() != null) {
            params.addValue("productId", query.productId());
        }
        if (query.q() != null && !query.q().isBlank()) {
            params.addValue("q", "%" + query.q().strip().toLowerCase() + "%");
        }
        return params;
    }

    private static String whereClause(AlertQuery query) {
        List<String> conditions = new ArrayList<>();
        conditions.add("a.tenant_id = :tenantId");
        conditions.add("(a.branch_id in (:branchIds) or a.branch_id is null)");
        if (query.status() != null) {
            conditions.add("a.status = :status");
        }
        if (query.type() != null) {
            conditions.add("a.type = :type");
        }
        if (query.severity() != null) {
            conditions.add("a.severity = :severity");
        }
        if (query.productId() != null) {
            conditions.add("a.product_id = :productId");
        }
        if (query.q() != null && !query.q().isBlank()) {
            conditions.add("(lower(a.title) like :q or lower(coalesce(a.message, '')) like :q)");
        }
        return "where " + String.join(" and ", conditions) + "\n";
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0;
    }
}
