package com.gondolia.platform;

import com.gondolia.domain.tenant.BusinessType;
import com.gondolia.domain.tenant.TenantModule;
import com.gondolia.domain.tenant.TenantPlan;
import com.gondolia.domain.tenant.TenantSettings;
import com.gondolia.domain.tenant.TenantStatus;
import com.gondolia.domain.user.Role;
import com.gondolia.modules.ModuleCatalog;
import com.gondolia.platform.dto.PlatformMetrics;
import com.gondolia.platform.dto.PlatformMetrics.BranchCounts;
import com.gondolia.platform.dto.PlatformMetrics.Engagement;
import com.gondolia.platform.dto.PlatformMetrics.GrowthPoint;
import com.gondolia.platform.dto.PlatformMetrics.ModuleAdoption;
import com.gondolia.platform.dto.PlatformMetrics.RecallSummary;
import com.gondolia.platform.dto.PlatformMetrics.Revenue;
import com.gondolia.platform.dto.PlatformMetrics.SupportSummary;
import com.gondolia.platform.dto.PlatformMetrics.TenantCounts;
import com.gondolia.platform.dto.PlatformMetrics.UserCounts;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Métricas agregadas de la consola de dueños (SPEC §6.6 y §14.3).
 * <p>
 * <b>Privacidad (SPEC §3.4.3)</b>: todas las consultas devuelven <b>cantidades</b> sobre tablas administrativas
 * (comercios, sucursales, usuarios, módulos, tickets y avisos). Nunca se leen productos, lotes, ventas, alertas ni
 * mensajes, y de los recalls solo sale <b>cuántos</b> comercios alcanzaron, nunca cuáles.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlatformMetricsService {

    /** Meses del gráfico de crecimiento (el último es el mes en curso). */
    static final int GROWTH_MONTHS = 12;

    private static final Duration WINDOW_7D = Duration.ofDays(7);
    private static final Duration WINDOW_30D = Duration.ofDays(30);

    private final JdbcTemplate jdbc;
    private final Clock clock;

    /** Cuota mensual de un comercio ACTIVE: sucursales activas × (plan + adicionales de sus módulos). */
    private record ActiveTenant(long id, TenantPlan plan, long activeBranches, Set<TenantModule> modules) {
    }

    public PlatformMetrics metrics() {
        Instant now = Instant.now(clock);
        Instant last7d = now.minus(WINDOW_7D);
        Instant last30d = now.minus(WINDOW_30D);

        Map<TenantStatus, Long> byStatus = tenantsByStatus();
        long active = byStatus.getOrDefault(TenantStatus.ACTIVE, 0L);
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();

        TenantCounts tenants = new TenantCounts(total, active,
                byStatus.getOrDefault(TenantStatus.DISABLED, 0L), byStatus.getOrDefault(TenantStatus.CANCELLED, 0L),
                count("select count(*) from tenants where created_at >= ?", last30d),
                count("select count(*) from tenant_events where type = 'CANCELLED' and created_at >= ?", last30d));

        List<ActiveTenant> activeTenants = activeTenants();
        BranchCounts branches = branchCounts(activeTenants, active);
        Revenue revenue = revenue(activeTenants, active);
        Map<TenantModule, ModuleAdoption> modules = moduleAdoption(activeTenants, active);

        Engagement engagement = new Engagement(
                count("select count(distinct tenant_id) from users where tenant_id is not null and last_login_at >= ?",
                        last7d),
                count("select count(distinct tenant_id) from users where tenant_id is not null and last_login_at >= ?",
                        last30d),
                count("select count(*) from users where tenant_id is not null and last_login_at >= ?", last7d));

        return new PlatformMetrics(tenants, branches, tenantsByPlan(), tenantsByBusinessType(), engagement,
                userCounts(), revenue, growth(), support(last30d), recalls(), modules);
    }

    // ------------------------------------------------------------------ comercios

    private Map<TenantStatus, Long> tenantsByStatus() {
        Map<TenantStatus, Long> counts = new EnumMap<>(TenantStatus.class);
        for (TenantStatus status : TenantStatus.values()) {
            counts.put(status, 0L);
        }
        jdbc.query("select status, count(*) as total from tenants group by status", (RowCallbackHandler) rs ->
                counts.put(TenantStatus.valueOf(rs.getString("status")), rs.getLong("total")));
        return counts;
    }

    private Map<TenantPlan, Long> tenantsByPlan() {
        Map<TenantPlan, Long> counts = new EnumMap<>(TenantPlan.class);
        for (TenantPlan plan : TenantPlan.values()) {
            counts.put(plan, 0L);
        }
        jdbc.query("select plan, count(*) as total from tenants group by plan", (RowCallbackHandler) rs ->
                counts.put(TenantPlan.valueOf(rs.getString("plan")), rs.getLong("total")));
        return counts;
    }

    private Map<BusinessType, Long> tenantsByBusinessType() {
        Map<BusinessType, Long> counts = new EnumMap<>(BusinessType.class);
        for (BusinessType type : BusinessType.values()) {
            counts.put(type, 0L);
        }
        jdbc.query("select business_type, count(*) as total from tenants group by business_type",
                (RowCallbackHandler) rs ->
                        counts.put(BusinessType.valueOf(rs.getString("business_type")), rs.getLong("total")));
        return counts;
    }

    /** Comercios ACTIVE con su plan, sus sucursales activas y sus módulos: base del MRR y de la adopción. */
    private List<ActiveTenant> activeTenants() {
        Map<Long, Set<TenantModule>> modules = new HashMap<>();
        jdbc.query("select tenant_id, module from tenant_modules where enabled", (RowCallbackHandler) rs ->
                modules.computeIfAbsent(rs.getLong("tenant_id"), id -> EnumSet.noneOf(TenantModule.class))
                        .add(TenantModule.valueOf(rs.getString("module"))));
        return jdbc.query("""
                select t.id, t.plan,
                       (select count(*) from branches b where b.tenant_id = t.id and b.active) as active_branches
                from tenants t
                where t.status = 'ACTIVE'
                """, (rs, rowNum) -> new ActiveTenant(rs.getLong("id"), TenantPlan.valueOf(rs.getString("plan")),
                rs.getLong("active_branches"),
                modules.getOrDefault(rs.getLong("id"), EnumSet.noneOf(TenantModule.class))));
    }

    private BranchCounts branchCounts(List<ActiveTenant> activeTenants, long activeTenantCount) {
        long total = count("select count(*) from branches", null);
        long active = count("select count(*) from branches where active", null);
        long activeBranchesOfActiveTenants = activeTenants.stream().mapToLong(ActiveTenant::activeBranches).sum();
        long multiBranch = activeTenants.stream().filter(tenant -> tenant.activeBranches() > 1).count();
        double average = activeTenantCount == 0 ? 0
                : round1((double) activeBranchesOfActiveTenants / activeTenantCount);
        return new BranchCounts(total, active, average, multiBranch);
    }

    private UserCounts userCounts() {
        Map<Role, Long> byRole = new EnumMap<>(Role.class);
        for (Role role : Role.tenantRoles()) {
            byRole.put(role, 0L);
        }
        jdbc.query("select role, count(*) as total from users where tenant_id is not null group by role",
                (RowCallbackHandler) rs -> byRole.put(Role.valueOf(rs.getString("role")), rs.getLong("total")));
        long total = byRole.values().stream().mapToLong(Long::longValue).sum();
        return new UserCounts(total, byRole);
    }

    // ------------------------------------------------------------------ facturación y módulos

    private Revenue revenue(List<ActiveTenant> activeTenants, long activeTenantCount) {
        BigDecimal mrr = BigDecimal.ZERO;
        long paid = 0;
        for (ActiveTenant tenant : activeTenants) {
            mrr = mrr.add(ModuleCatalog.monthlyFee(tenant.plan(), tenant.modules(), tenant.activeBranches()));
            if (tenant.plan().isPaid()) {
                paid++;
            }
        }
        double conversion = activeTenantCount == 0 ? 0 : round1(100.0 * paid / activeTenantCount);
        return new Revenue(mrr.setScale(2, RoundingMode.HALF_UP), TenantSettings.DEFAULT_CURRENCY, conversion);
    }

    private Map<TenantModule, ModuleAdoption> moduleAdoption(List<ActiveTenant> activeTenants, long activeTenantCount) {
        Map<TenantModule, ModuleAdoption> adoption = new EnumMap<>(TenantModule.class);
        for (TenantModule module : TenantModule.values()) {
            long enabled = activeTenants.stream().filter(tenant -> tenant.modules().contains(module)).count();
            double pct = activeTenantCount == 0 ? 0 : round1(100.0 * enabled / activeTenantCount);
            adoption.put(module, new ModuleAdoption(enabled, pct));
        }
        return adoption;
    }

    /** Adopción por módulo para {@code GET /api/platform/modules} (SPEC §14.3). */
    Map<TenantModule, ModuleAdoption> moduleAdoption() {
        List<ActiveTenant> activeTenants = activeTenants();
        return moduleAdoption(activeTenants, activeTenants.size());
    }

    /** Cantidad de comercios ACTIVE (base de los porcentajes de adopción). */
    long activeTenantCount() {
        return count("select count(*) from tenants where status = 'ACTIVE'", null);
    }

    // ------------------------------------------------------------------ crecimiento

    /**
     * Doce meses de altas, bajas y comercios activos al cierre. El estado histórico se reconstruye con
     * {@code tenant_events}: un comercio sin eventos de estado se considera activo desde su alta salvo que hoy no lo
     * esté. Los comercios eliminados definitivamente ya no existen, así que no cuentan.
     */
    private List<GrowthPoint> growth() {
        ZoneId zone = clock.getZone();
        YearMonth currentMonth = YearMonth.now(clock);
        List<YearMonth> months = new ArrayList<>(GROWTH_MONTHS);
        for (int i = GROWTH_MONTHS - 1; i >= 0; i--) {
            months.add(currentMonth.minusMonths(i));
        }
        Map<String, Long> newByMonth = countByMonth("select created_at from tenants", zone);
        Map<String, Long> cancelledByMonth = countByMonth(
                "select created_at from tenant_events where type = 'CANCELLED'", zone);

        List<TenantHistory> histories = tenantHistories();
        Instant now = Instant.now(clock);
        List<GrowthPoint> points = new ArrayList<>(months.size());
        for (YearMonth month : months) {
            Instant cut = month.atEndOfMonth().plusDays(1).atStartOfDay(zone).toInstant();
            Instant asOf = cut.isAfter(now) ? now : cut;
            long activeAtEnd = histories.stream().filter(history -> history.wasActiveAt(asOf)).count();
            String key = month.toString();
            points.add(new GrowthPoint(key, newByMonth.getOrDefault(key, 0L), cancelledByMonth.getOrDefault(key, 0L),
                    activeAtEnd));
        }
        return points;
    }

    private Map<String, Long> countByMonth(String selectCreatedAt, ZoneId zone) {
        Map<String, Long> counts = new HashMap<>();
        jdbc.query("select to_char(created_at at time zone ?, 'YYYY-MM') as month, count(*) as total from ("
                        + selectCreatedAt + ") as source group by 1",
                (RowCallbackHandler) rs -> counts.put(rs.getString("month"), rs.getLong("total")),
                zone.getId());
        return counts;
    }

    /** Línea de tiempo de estados de un comercio para reconstruir el pasado. */
    private record TenantHistory(Instant createdAt, List<Instant> changes, List<Boolean> activeAfter,
                                 boolean activeFromStart) {

        boolean wasActiveAt(Instant moment) {
            if (createdAt == null || createdAt.isAfter(moment)) {
                return false;
            }
            boolean active = activeFromStart;
            for (int i = 0; i < changes.size(); i++) {
                if (changes.get(i).isAfter(moment)) {
                    break;
                }
                active = activeAfter.get(i);
            }
            return active;
        }
    }

    private List<TenantHistory> tenantHistories() {
        Map<Long, Instant> createdAt = new HashMap<>();
        Map<Long, TenantStatus> status = new HashMap<>();
        jdbc.query("select id, created_at, status from tenants", (RowCallbackHandler) rs -> {
            long id = rs.getLong("id");
            createdAt.put(id, rs.getTimestamp("created_at").toInstant());
            status.put(id, TenantStatus.valueOf(rs.getString("status")));
        });
        Map<Long, List<Instant>> changes = new HashMap<>();
        Map<Long, List<Boolean>> activeAfter = new HashMap<>();
        jdbc.query("""
                select tenant_id, type, created_at from tenant_events
                where tenant_id is not null
                  and type in ('CREATED', 'DISABLED', 'ENABLED', 'CANCELLED', 'REACTIVATED')
                order by created_at, id
                """, (RowCallbackHandler) rs -> {
            long id = rs.getLong("tenant_id");
            String type = rs.getString("type");
            boolean active = "ENABLED".equals(type) || "REACTIVATED".equals(type) || "CREATED".equals(type);
            changes.computeIfAbsent(id, key -> new ArrayList<>()).add(rs.getTimestamp("created_at").toInstant());
            activeAfter.computeIfAbsent(id, key -> new ArrayList<>()).add(active);
        });
        List<TenantHistory> histories = new ArrayList<>(createdAt.size());
        createdAt.forEach((id, created) -> {
            List<Instant> tenantChanges = changes.getOrDefault(id, List.of());
            // Sin eventos de estado no hay historia que reconstruir: vale el estado actual desde el alta.
            boolean activeFromStart = !tenantChanges.isEmpty() || status.get(id) == TenantStatus.ACTIVE;
            histories.add(new TenantHistory(created, tenantChanges, activeAfter.getOrDefault(id, List.of()),
                    activeFromStart));
        });
        return histories;
    }

    // ------------------------------------------------------------------ soporte y recalls

    private SupportSummary support(Instant last30d) {
        long open = count("select count(*) from support_tickets where status not in ('RESOLVED', 'CLOSED')", null);
        long unassigned = count("""
                select count(*) from support_tickets
                where status not in ('RESOLVED', 'CLOSED') and assigned_to is null
                """, null);
        Double avgFirstResponse = jdbc.queryForObject("""
                select avg(extract(epoch from (first_response_at - created_at)) / 60.0)
                from support_tickets where first_response_at is not null and created_at >= ?
                """, Double.class, java.sql.Timestamp.from(last30d));
        long resolved = count("select count(*) from support_tickets where resolved_at >= ?", last30d);
        Double avgRating = jdbc.queryForObject("select avg(rating) from support_tickets where rating is not null",
                Double.class);
        return new SupportSummary(open, unassigned, avgFirstResponse == null ? null : round1(avgFirstResponse),
                resolved, avgRating == null ? null : round1(avgRating));
    }

    private RecallSummary recalls() {
        long activeRecalls = count("""
                select count(*) from announcements where kind = 'RECALL' and status = 'PUBLISHED'
                """, null);
        long affected = count("""
                select count(distinct m.tenant_id) from recall_matches m
                join announcements a on a.id = m.announcement_id
                where a.kind = 'RECALL' and a.status = 'PUBLISHED'
                """, null);
        return new RecallSummary(activeRecalls, affected);
    }

    // ------------------------------------------------------------------ utilidades

    private long count(String sql, Instant since) {
        Long value = since == null ? jdbc.queryForObject(sql, Long.class)
                : jdbc.queryForObject(sql, Long.class, java.sql.Timestamp.from(since));
        return value == null ? 0 : value;
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
