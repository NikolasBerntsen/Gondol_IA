package com.gondolia.platform;

import com.gondolia.domain.tenant.BusinessType;
import com.gondolia.domain.tenant.TenantEventType;
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

        // Altas y bajas salen de la misma historia por comercio (incluidos los eliminados): ver growth().
        List<TenantHistory> histories = tenantHistories();
        TenantCounts tenants = new TenantCounts(total, active,
                byStatus.getOrDefault(TenantStatus.DISABLED, 0L), byStatus.getOrDefault(TenantStatus.CANCELLED, 0L),
                histories.stream().filter(history -> history.createdWithin(last30d, Instant.MAX)).count(),
                histories.stream().filter(history -> history.churnedWithin(last30d, Instant.MAX)).count());

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
                userCounts(), revenue, growth(histories, now), support(last30d), recalls(), modules);
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

    /**
     * {@code active}, el promedio y los multi-sucursal miran la misma población que el MRR: las sucursales activas de
     * los comercios ACTIVE (las que facturan). {@code total} son todas las sucursales registradas.
     */
    private BranchCounts branchCounts(List<ActiveTenant> activeTenants, long activeTenantCount) {
        long total = count("select count(*) from branches", null);
        long active = activeTenants.stream().mapToLong(ActiveTenant::activeBranches).sum();
        long multiBranch = activeTenants.stream().filter(tenant -> tenant.activeBranches() > 1).count();
        double average = activeTenantCount == 0 ? 0 : round1((double) active / activeTenantCount);
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
     * Doce meses de altas, bajas y comercios activos al cierre, todo desde la misma historia por comercio
     * ({@link #tenantHistories}), así las tres series cuadran entre sí:
     * <ul>
     *   <li><b>Altas</b>: comercios creados en el mes, también los que después se eliminaron.</li>
     *   <li><b>Bajas</b>: comercios cuya última baja o reactivación del mes fue una baja. Cada comercio cuenta una
     *       vez por mes (baja, reactivación y otra baja es una sola baja) y una baja que se revierte en el mismo mes
     *       no cuenta.</li>
     *   <li><b>Activos al cierre</b>: estado reconstruido con {@code tenant_events}, incluidos los eliminados.</li>
     * </ul>
     */
    private List<GrowthPoint> growth(List<TenantHistory> histories, Instant now) {
        ZoneId zone = clock.getZone();
        YearMonth currentMonth = YearMonth.now(clock);
        List<GrowthPoint> points = new ArrayList<>(GROWTH_MONTHS);
        for (int i = GROWTH_MONTHS - 1; i >= 0; i--) {
            YearMonth month = currentMonth.minusMonths(i);
            Instant start = month.atDay(1).atStartOfDay(zone).toInstant();
            Instant cut = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
            Instant asOf = cut.isAfter(now) ? now : cut;
            long created = histories.stream().filter(history -> history.createdWithin(start, cut)).count();
            long churned = histories.stream().filter(history -> history.churnedWithin(start, cut)).count();
            long activeAtEnd = histories.stream().filter(history -> history.wasActiveAt(asOf)).count();
            points.add(new GrowthPoint(month.toString(), created, churned, activeAtEnd));
        }
        return points;
    }

    /** Un cambio de estado del historial ({@code CREATED}, {@code DISABLED}, {@code CANCELLED}...). */
    private record StatusChange(Instant at, TenantEventType type) {

        boolean activeAfter() {
            return type == TenantEventType.CREATED || type == TenantEventType.ENABLED
                    || type == TenantEventType.REACTIVATED;
        }

        boolean churnOrWinBack() {
            return type == TenantEventType.CANCELLED || type == TenantEventType.REACTIVATED;
        }
    }

    /**
     * Línea de tiempo de estados de un comercio (existente o eliminado) para reconstruir el pasado.
     *
     * @param activeFromStart estado desde el alta hasta el primer cambio
     * @param changes         cambios de estado en orden cronológico
     */
    private record TenantHistory(Instant createdAt, boolean activeFromStart, List<StatusChange> changes) {

        boolean createdWithin(Instant from, Instant to) {
            return !createdAt.isBefore(from) && createdAt.isBefore(to);
        }

        boolean wasActiveAt(Instant moment) {
            if (createdAt.isAfter(moment)) {
                return false;
            }
            boolean active = activeFromStart;
            for (StatusChange change : changes) {
                if (change.at().isAfter(moment)) {
                    break;
                }
                active = change.activeAfter();
            }
            return active;
        }

        /** Baja en {@code [from, to)}: la última baja o reactivación del período es una baja. */
        boolean churnedWithin(Instant from, Instant to) {
            TenantEventType last = null;
            for (StatusChange change : changes) {
                if (!change.at().isBefore(to)) {
                    break;
                }
                if (change.churnOrWinBack() && !change.at().isBefore(from)) {
                    last = change.type();
                }
            }
            return last == TenantEventType.CANCELLED;
        }
    }

    /**
     * Historias de todos los comercios, incluidos los eliminados definitivamente: sus eventos quedan con
     * {@code tenant_id} NULL y su id en {@code deleted_tenant_id} (V250), así su alta y su baja siguen contando una vez
     * cada una. Los eventos de comercios eliminados antes de V250 no tienen a quién atribuirse y no cuentan (ni el alta
     * ni la baja).
     * <p>
     * Alta de un comercio existente = {@code tenants.created_at}; de uno eliminado, su evento {@code CREATED} (o el
     * primero que tenga). Un comercio existente sin eventos de estado vale su estado actual desde el alta.
     */
    private List<TenantHistory> tenantHistories() {
        Map<Long, Instant> createdAt = new HashMap<>();
        Map<Long, TenantStatus> status = new HashMap<>();
        jdbc.query("select id, created_at, status from tenants", (RowCallbackHandler) rs -> {
            long id = rs.getLong("id");
            createdAt.put(id, rs.getTimestamp("created_at").toInstant());
            status.put(id, TenantStatus.valueOf(rs.getString("status")));
        });
        Map<Long, List<StatusChange>> changes = new HashMap<>();
        Map<Long, Instant> createdEvent = new HashMap<>();
        jdbc.query("""
                select coalesce(tenant_id, deleted_tenant_id) as subject, type, created_at from tenant_events
                where coalesce(tenant_id, deleted_tenant_id) is not null
                  and type in ('CREATED', 'DISABLED', 'ENABLED', 'CANCELLED', 'REACTIVATED')
                order by created_at, id
                """, (RowCallbackHandler) rs -> {
            long id = rs.getLong("subject");
            TenantEventType type = TenantEventType.valueOf(rs.getString("type"));
            Instant at = rs.getTimestamp("created_at").toInstant();
            changes.computeIfAbsent(id, key -> new ArrayList<>()).add(new StatusChange(at, type));
            if (type == TenantEventType.CREATED) {
                createdEvent.putIfAbsent(id, at);
            }
        });
        List<TenantHistory> histories = new ArrayList<>(createdAt.size() + changes.size());
        createdAt.forEach((id, created) -> {
            List<StatusChange> tenantChanges = changes.getOrDefault(id, List.of());
            // Sin eventos de estado no hay historia que reconstruir: vale el estado actual desde el alta.
            boolean activeFromStart = !tenantChanges.isEmpty() || status.get(id) == TenantStatus.ACTIVE;
            histories.add(new TenantHistory(created, activeFromStart, tenantChanges));
        });
        changes.forEach((id, tenantChanges) -> {
            if (!createdAt.containsKey(id)) {
                // Comercio eliminado: solo se elimina uno dado de baja, así que su historia termina en CANCELLED.
                Instant created = createdEvent.getOrDefault(id, tenantChanges.getFirst().at());
                histories.add(new TenantHistory(created, true, tenantChanges));
            }
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

    /**
     * Recalls <b>en curso</b>: publicados y con al menos una coincidencia que algún comercio todavía no resolvió
     * ({@code OPEN} o {@code ACKNOWLEDGED}); uno con todas sus coincidencias resueltas ya no cuenta.
     * {@code affectedTenantsTotal} = comercios distintos alcanzados por esos recalls. Solo cantidades.
     */
    private RecallSummary recalls() {
        String activeRecall = """
                 a.kind = 'RECALL' and a.status = 'PUBLISHED'
                 and exists (select 1 from recall_matches pending
                             where pending.announcement_id = a.id and pending.status in ('OPEN', 'ACKNOWLEDGED'))
                """;
        long activeRecalls = count("select count(*) from announcements a where" + activeRecall, null);
        long affected = count("select count(distinct m.tenant_id) from recall_matches m"
                + " join announcements a on a.id = m.announcement_id where" + activeRecall, null);
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
