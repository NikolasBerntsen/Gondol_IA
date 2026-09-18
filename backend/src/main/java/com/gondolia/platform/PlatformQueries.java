package com.gondolia.platform;

import com.gondolia.common.error.BadRequestException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.domain.tenant.BusinessType;
import com.gondolia.domain.tenant.TenantModule;
import com.gondolia.domain.tenant.TenantPlan;
import com.gondolia.domain.tenant.TenantStatus;
import com.gondolia.domain.user.Role;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Consultas administrativas de la consola de dueños (SPEC §6.6): listado de comercios con sus contadores y
 * módulos habilitados. Es la parte analítica del módulo, con {@code NamedParameterJdbcTemplate} (SPEC §5.2).
 * <p>
 * <b>Privacidad</b>: acá solo se leen tablas administrativas ({@code tenants}, {@code branches}, {@code users},
 * {@code tenant_modules}). Nunca productos, lotes, ventas, alertas ni chats.
 */
@Component
@RequiredArgsConstructor
class PlatformQueries {

    /** Fila administrativa de un comercio con sus contadores (sin módulos ni cuota: los agrega el servicio). */
    record TenantRow(Long id, String name, BusinessType businessType, TenantPlan plan, TenantStatus status,
                     String city, String province, String contactName, String contactEmail, String contactPhone,
                     String legalName, String taxId, String address, String notes, long userCount, long branchCount,
                     long activeBranchCount, Instant lastActivityAt, Instant createdAt, Instant statusChangedAt,
                     String statusReason) {
    }

    /** Filtros del listado (SPEC §6.6 y §14.3). */
    record TenantFilters(String q, TenantStatus status, TenantPlan plan, BusinessType businessType,
                         TenantModule module) {

        static TenantFilters of(String q, TenantStatus status, TenantPlan plan, BusinessType businessType,
                                TenantModule module) {
            String text = q == null || q.isBlank() ? null : q.strip().toLowerCase(Locale.ROOT);
            return new TenantFilters(text, status, plan, businessType, module);
        }
    }

    /** Columnas por las que se puede ordenar el listado (`sort=campo,asc|desc`). */
    private static final Map<String, String> SORTABLE = Map.of(
            "name", "t.name",
            "createdAt", "t.created_at",
            "lastActivityAt", "last_activity_at",
            "status", "t.status",
            "plan", "t.plan",
            "city", "t.city",
            "activeBranchCount", "active_branch_count",
            "userCount", "user_count");

    private static final String SELECT_COLUMNS = """
            select t.id, t.name, t.business_type, t.plan, t.status, t.city, t.province, t.contact_name,
                   t.contact_email, t.contact_phone, t.legal_name, t.tax_id, t.address, t.notes,
                   t.created_at, t.status_changed_at, t.status_reason,
                   (select count(*) from users u where u.tenant_id = t.id) as user_count,
                   (select count(*) from branches b where b.tenant_id = t.id) as branch_count,
                   (select count(*) from branches b where b.tenant_id = t.id and b.active) as active_branch_count,
                   (select max(u.last_login_at) from users u where u.tenant_id = t.id) as last_activity_at
            from tenants t
            """;

    private static final RowMapper<TenantRow> ROW_MAPPER = PlatformQueries::mapRow;

    private final NamedParameterJdbcTemplate jdbc;

    /** Página del listado de comercios, ya filtrada y ordenada. */
    List<TenantRow> search(TenantFilters filters, String sort, int page, int size) {
        MapSqlParameterSource params = params(filters);
        params.addValue("limit", size);
        params.addValue("offset", (long) page * size);
        String sql = SELECT_COLUMNS + where(filters) + " order by " + orderBy(sort) + " limit :limit offset :offset";
        return jdbc.query(sql, params, ROW_MAPPER);
    }

    /** Cantidad total de comercios que cumplen los filtros. */
    long count(TenantFilters filters) {
        Long total = jdbc.queryForObject("select count(*) from tenants t" + where(filters), params(filters),
                Long.class);
        return total == null ? 0 : total;
    }

    /** Fila administrativa de un comercio ({@code null} si no existe). */
    TenantRow byId(Long tenantId) {
        List<TenantRow> rows = jdbc.query(SELECT_COLUMNS + " where t.id = :id",
                new MapSqlParameterSource("id", tenantId), ROW_MAPPER);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** Módulos habilitados de varios comercios: {@code tenantId → módulos}. */
    Map<Long, Set<TenantModule>> enabledModules(Collection<Long> tenantIds) {
        Map<Long, Set<TenantModule>> byTenant = new HashMap<>();
        if (tenantIds == null || tenantIds.isEmpty()) {
            return byTenant;
        }
        jdbc.query("select tenant_id, module from tenant_modules where enabled and tenant_id in (:ids)",
                new MapSqlParameterSource("ids", tenantIds), (RowCallbackHandler) rs ->
                        byTenant.computeIfAbsent(rs.getLong("tenant_id"), id -> EnumSet.noneOf(TenantModule.class))
                                .add(TenantModule.valueOf(rs.getString("module"))));
        return byTenant;
    }

    /** Cantidad de usuarios por rol de un comercio (todos los roles, incluso los que no tiene). */
    Map<Role, Long> usersByRole(Long tenantId) {
        Map<Role, Long> counts = new LinkedHashMap<>();
        for (Role role : Role.tenantRoles()) {
            counts.put(role, 0L);
        }
        jdbc.query("select role, count(*) as total from users where tenant_id = :id group by role",
                new MapSqlParameterSource("id", tenantId),
                (RowCallbackHandler) rs -> counts.put(Role.valueOf(rs.getString("role")), rs.getLong("total")));
        return counts;
    }

    // ------------------------------------------------------------------ internos

    private MapSqlParameterSource params(TenantFilters filters) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (filters.q() != null) {
            params.addValue("q", "%" + filters.q() + "%");
        }
        if (filters.status() != null) {
            params.addValue("status", filters.status().name());
        }
        if (filters.plan() != null) {
            params.addValue("plan", filters.plan().name());
        }
        if (filters.businessType() != null) {
            params.addValue("businessType", filters.businessType().name());
        }
        if (filters.module() != null) {
            params.addValue("module", filters.module().name());
        }
        return params;
    }

    private String where(TenantFilters filters) {
        List<String> conditions = new ArrayList<>();
        if (filters.q() != null) {
            conditions.add("""
                    (lower(t.name) like :q or lower(coalesce(t.legal_name, '')) like :q
                     or lower(coalesce(t.contact_name, '')) like :q or lower(coalesce(t.contact_email, '')) like :q
                     or lower(coalesce(t.city, '')) like :q or lower(coalesce(t.tax_id, '')) like :q)""");
        }
        if (filters.status() != null) {
            conditions.add("t.status = :status");
        }
        if (filters.plan() != null) {
            conditions.add("t.plan = :plan");
        }
        if (filters.businessType() != null) {
            conditions.add("t.business_type = :businessType");
        }
        if (filters.module() != null) {
            conditions.add("""
                    exists (select 1 from tenant_modules m
                            where m.tenant_id = t.id and m.module = :module and m.enabled)""");
        }
        return conditions.isEmpty() ? "" : " where " + String.join(" and ", conditions);
    }

    /** Traduce {@code sort=campo,asc|desc} a SQL; 400 {@code VALIDATION_ERROR} si el campo no existe. */
    private String orderBy(String sort) {
        if (sort == null || sort.isBlank()) {
            return "t.name asc";
        }
        String[] parts = sort.split(",", 2);
        String column = SORTABLE.get(parts[0].strip());
        if (column == null) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR,
                    "No se puede ordenar por «" + parts[0].strip() + "»");
        }
        boolean desc = parts.length > 1 && parts[1].strip().equalsIgnoreCase("desc");
        String direction = desc ? "desc" : "asc";
        String nulls = desc ? " nulls last" : " nulls first";
        return column + " " + direction + nulls + ", t.id asc";
    }

    private static TenantRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new TenantRow(
                rs.getLong("id"),
                rs.getString("name"),
                BusinessType.valueOf(rs.getString("business_type")),
                TenantPlan.valueOf(rs.getString("plan")),
                TenantStatus.valueOf(rs.getString("status")),
                rs.getString("city"),
                rs.getString("province"),
                rs.getString("contact_name"),
                rs.getString("contact_email"),
                rs.getString("contact_phone"),
                rs.getString("legal_name"),
                rs.getString("tax_id"),
                rs.getString("address"),
                rs.getString("notes"),
                rs.getLong("user_count"),
                rs.getLong("branch_count"),
                rs.getLong("active_branch_count"),
                instant(rs, "last_activity_at"),
                instant(rs, "created_at"),
                instant(rs, "status_changed_at"),
                rs.getString("status_reason"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
