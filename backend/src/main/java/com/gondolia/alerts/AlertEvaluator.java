package com.gondolia.alerts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gondolia.domain.alert.Alert;
import com.gondolia.domain.alert.AlertRepository;
import com.gondolia.domain.alert.AlertStatus;
import com.gondolia.domain.alert.AlertType;
import com.gondolia.domain.alert.OpenAlertWriter;
import com.gondolia.domain.common.Severity;
import com.gondolia.domain.notification.NotificationType;
import com.gondolia.domain.tenant.TenantSettings;
import com.gondolia.domain.tenant.TenantSettingsRepository;
import com.gondolia.domain.user.Role;
import com.gondolia.notification.NotificationDraft;
import com.gondolia.notification.NotificationService;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Corazón del motor de alertas (SPEC §6.5): calcula el estado deseado de una sucursal, abre las alertas que
 * faltan (con {@code dedupe_key} por sucursal) y resuelve las que ya no aplican.
 * <p>
 * Tipos que administra: {@code EXPIRED}, {@code EXPIRING_SOON}, {@code LOW_STOCK}, {@code OUT_OF_STOCK},
 * {@code STOCKOUT_PREDICTED} y {@code ANOMALY}. Las de {@code RECALL_MATCH} (módulo D) y
 * {@code SALE_WITHOUT_STOCK} (núcleo) solo se cierran a mano.
 * <p>
 * Una alerta descartada no se vuelve a abrir mientras la condición siga igual: se respeta el descarte durante
 * {@link #DISMISS_QUIET_DAYS} días.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertEvaluator {

    /** Días que se respeta un descarte antes de volver a abrir la misma alerta. */
    static final int DISMISS_QUIET_DAYS = 7;

    /** Los tipos que abre y cierra el motor. */
    static final Set<AlertType> MANAGED_TYPES = EnumSet.of(AlertType.EXPIRED, AlertType.EXPIRING_SOON,
            AlertType.LOW_STOCK, AlertType.OUT_OF_STOCK, AlertType.STOCKOUT_PREDICTED, AlertType.ANOMALY);

    /** Tipos de vencimiento: también se notifican a los empleados de la sucursal (SPEC §6.5). */
    private static final Set<AlertType> EXPIRY_TYPES = EnumSet.of(AlertType.EXPIRED, AlertType.EXPIRING_SOON);

    /** Días hacia atrás en los que una anomalía sigue siendo noticia. */
    private static final int ANOMALY_WINDOW_DAYS = 7;

    /** Margen sobre el tiempo de reposición para avisar un quiebre previsto. */
    private static final int STOCKOUT_MARGIN_DAYS = 2;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.forLanguageTag("es-AR"));

    private final NamedParameterJdbcTemplate jdbc;
    private final AlertRepository alertRepository;
    private final OpenAlertWriter openAlertWriter;
    private final TenantSettingsRepository settingsRepository;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** Resultado de una pasada por una sucursal. */
    public record Evaluation(int opened, int resolved, int criticalOpened) {

        static final Evaluation NONE = new Evaluation(0, 0, 0);
    }

    /** Alerta que el motor quiere ver abierta. */
    private record Desired(AlertType type, Severity severity, String title, String message, Long productId,
                           Long lotId, String dedupeKey) {
    }

    /**
     * Reconcilia las alertas de una sucursal. Se ejecuta en su propia transacción: el motor la llama desde tareas
     * programadas y desde listeners posteriores al commit.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Evaluation evaluateBranch(long tenantId, long branchId, String branchName) {
        TenantSettings settings = settingsRepository.findById(tenantId)
                .orElseGet(() -> TenantSettings.defaultsFor(tenantId));
        LocalDate today = LocalDate.now(clock);
        String branch = branchName == null || branchName.isBlank() ? "Sucursal" : branchName;

        Map<String, Desired> desired = new LinkedHashMap<>();
        expiryAlerts(tenantId, branchId, branch, today, settings).forEach(d -> desired.put(d.dedupeKey(), d));
        stockAlerts(tenantId, branchId, branch, today).forEach(d -> desired.put(d.dedupeKey(), d));
        insightAlerts(tenantId, branchId, branch, today, settings).forEach(d -> desired.put(d.dedupeKey(), d));

        List<Alert> current = alertRepository.findByTenantIdAndBranchIdAndTypeInAndStatusIn(tenantId, branchId,
                MANAGED_TYPES, List.of(AlertStatus.OPEN, AlertStatus.ACKNOWLEDGED));
        Set<String> currentKeys = new HashSet<>();
        int resolved = 0;
        for (Alert alert : current) {
            currentKeys.add(alert.getDedupeKey());
            if (!desired.containsKey(alert.getDedupeKey())) {
                alert.setStatus(AlertStatus.RESOLVED);
                alert.setResolvedAt(clock.instant());
                alertRepository.save(alert);
                resolved++;
            }
        }

        Set<String> dismissed = recentlyDismissed(tenantId, branchId, today);
        int opened = 0;
        int criticalOpened = 0;
        for (Desired candidate : desired.values()) {
            if (currentKeys.contains(candidate.dedupeKey()) || dismissed.contains(candidate.dedupeKey())) {
                continue;
            }
            Optional<Long> alertId = openAlertWriter.openIfAbsent(toAlert(tenantId, branchId, candidate));
            if (alertId.isEmpty()) {
                continue;
            }
            opened++;
            if (candidate.severity() == Severity.CRITICAL) {
                criticalOpened++;
                notify(tenantId, branchId, candidate, alertId.get());
            }
        }
        if (opened > 0 || resolved > 0) {
            log.info("Alertas sucursal {}: {} abiertas, {} resueltas", branchId, opened, resolved);
        }
        return new Evaluation(opened, resolved, criticalOpened);
    }

    // ------------------------------------------------------------------ condiciones

    private List<Desired> expiryAlerts(long tenantId, long branchId, String branch, LocalDate today,
                                       TenantSettings settings) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("branchId", branchId)
                .addValue("limitDate", Date.valueOf(today.plusDays(settings.getExpiryWarningDays())));
        return jdbc.query("""
                select l.id, l.product_id, p.name as product_name, l.lot_number, l.expiry_date, l.quantity
                from lots l join products p on p.id = l.product_id
                where l.tenant_id = :tenantId and l.branch_id = :branchId and l.status = 'ACTIVE'
                  and l.quantity > 0 and l.expiry_date is not null and l.expiry_date <= :limitDate
                order by l.expiry_date asc
                """, params, (rs, rowNum) -> {
                    long lotId = rs.getLong("id");
                    long productId = rs.getLong("product_id");
                    String product = rs.getString("product_name");
                    String lotNumber = rs.getString("lot_number");
                    LocalDate expiry = rs.getObject("expiry_date", LocalDate.class);
                    int quantity = rs.getInt("quantity");
                    long days = ChronoUnit.DAYS.between(today, expiry);
                    String lotText = lotNumber == null || lotNumber.isBlank() ? "sin número de lote"
                            : "lote " + lotNumber;
                    if (days < 0) {
                        return new Desired(AlertType.EXPIRED, Severity.CRITICAL,
                                "Vencido: " + product,
                                "%s: el %s de %s venció el %s y todavía quedan %d u. Descartalo desde Vencimientos."
                                        .formatted(branch, lotText, product, DAY.format(expiry), quantity),
                                productId, lotId, key(AlertType.EXPIRED, branchId, lotId));
                    }
                    Severity severity = days <= settings.getExpiryCriticalDays() ? Severity.CRITICAL
                            : Severity.WARNING;
                    String when = days == 0 ? "vence hoy" : days == 1 ? "vence mañana" : "vence en " + days + " días";
                    return new Desired(AlertType.EXPIRING_SOON, severity,
                            "Por vencer: " + product,
                            "%s: el %s de %s %s (%s) con %d u. en stock."
                                    .formatted(branch, lotText, product, when, DAY.format(expiry), quantity),
                            productId, lotId, key(AlertType.EXPIRING_SOON, branchId, lotId));
                });
    }

    private List<Desired> stockAlerts(long tenantId, long branchId, String branch, LocalDate today) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("branchId", branchId)
                .addValue("today", Date.valueOf(today));
        return jdbc.query("""
                with handled as (
                  select distinct l.product_id from lots l
                  where l.tenant_id = :tenantId and l.branch_id = :branchId
                ),
                sellable as (
                  select l.product_id, sum(l.quantity) as qty from lots l
                  where l.tenant_id = :tenantId and l.branch_id = :branchId and l.status = 'ACTIVE'
                    and l.quantity > 0 and (l.expiry_date is null or l.expiry_date >= :today)
                  group by l.product_id
                )
                select p.id as product_id, p.name as product_name, p.min_stock,
                       coalesce(s.qty, 0)::int as sellable
                from handled h
                join products p on p.id = h.product_id and p.active
                left join sellable s on s.product_id = h.product_id
                where p.min_stock > 0 and coalesce(s.qty, 0) <= p.min_stock
                """, params, (rs, rowNum) -> {
                    long productId = rs.getLong("product_id");
                    String product = rs.getString("product_name");
                    int minStock = rs.getInt("min_stock");
                    int sellable = rs.getInt("sellable");
                    if (sellable <= 0) {
                        return new Desired(AlertType.OUT_OF_STOCK, Severity.CRITICAL,
                                "Sin stock: " + product,
                                "%s: no queda stock vendible de %s (el mínimo es %d u.). Reponelo."
                                        .formatted(branch, product, minStock),
                                productId, null, key(AlertType.OUT_OF_STOCK, branchId, productId));
                    }
                    return new Desired(AlertType.LOW_STOCK, Severity.WARNING,
                            "Stock bajo: " + product,
                            "%s: quedan %d u. de %s y el mínimo es %d u."
                                    .formatted(branch, sellable, product, minStock),
                            productId, null, key(AlertType.LOW_STOCK, branchId, productId));
                });
    }

    private List<Desired> insightAlerts(long tenantId, long branchId, String branch, LocalDate today,
                                        TenantSettings settings) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("branchId", branchId)
                .addValue("today", Date.valueOf(today))
                .addValue("stockoutLimit",
                        Date.valueOf(today.plusDays(settings.getDefaultLeadTimeDays() + STOCKOUT_MARGIN_DAYS)));

        List<Desired> desired = new ArrayList<>(jdbc.query("""
                with sellable as (
                  select l.product_id, sum(l.quantity) as qty from lots l
                  where l.tenant_id = :tenantId and l.branch_id = :branchId and l.status = 'ACTIVE'
                    and l.quantity > 0 and (l.expiry_date is null or l.expiry_date >= :today)
                  group by l.product_id
                )
                select i.product_id, p.name as product_name, i.predicted_stockout_date, i.days_of_cover,
                       i.suggested_order_qty, coalesce(s.qty, 0)::int as sellable
                from product_insights i
                join products p on p.id = i.product_id and p.active
                left join sellable s on s.product_id = i.product_id
                where i.tenant_id = :tenantId and i.branch_id = :branchId
                  and i.predicted_stockout_date is not null and i.predicted_stockout_date <= :stockoutLimit
                  and coalesce(s.qty, 0) > 0
                """, params, (rs, rowNum) -> {
                    long productId = rs.getLong("product_id");
                    String product = rs.getString("product_name");
                    LocalDate stockout = rs.getObject("predicted_stockout_date", LocalDate.class);
                    int sellable = rs.getInt("sellable");
                    Number suggested = (Number) rs.getObject("suggested_order_qty");
                    String order = suggested == null || suggested.intValue() <= 0 ? ""
                            : " La IA sugiere pedir %d u.".formatted(suggested.intValue());
                    return new Desired(AlertType.STOCKOUT_PREDICTED, Severity.WARNING,
                            "Quiebre previsto: " + product,
                            "%s: al ritmo actual las %d u. de %s se agotan el %s.%s"
                                    .formatted(branch, sellable, product, DAY.format(stockout), order),
                            productId, null, key(AlertType.STOCKOUT_PREDICTED, branchId, productId));
                }));

        LocalDate anomalyFrom = today.minusDays(ANOMALY_WINDOW_DAYS);
        jdbc.query("""
                select i.product_id, p.name as product_name, i.anomalies::text as anomalies
                from product_insights i
                join products p on p.id = i.product_id and p.active
                where i.tenant_id = :tenantId and i.branch_id = :branchId and i.anomalies is not null
                """, params, (rs, rowNum) -> new String[]{String.valueOf(rs.getLong("product_id")),
                        rs.getString("product_name"), rs.getString("anomalies")})
                .forEach(row -> desired.addAll(
                        anomalyAlerts(branchId, branch, Long.parseLong(row[0]), row[1], row[2], anomalyFrom)));
        return desired;
    }

    private List<Desired> anomalyAlerts(long branchId, String branch, long productId, String product, String json,
                                        LocalDate from) {
        List<Desired> alerts = new ArrayList<>();
        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (Exception e) {
            log.debug("Anomalías ilegibles del producto {}: {}", productId, e.getMessage());
            return alerts;
        }
        if (node == null || !node.isArray()) {
            return alerts;
        }
        for (JsonNode anomaly : node) {
            String date = anomaly.path("date").asText(null);
            if (date == null) {
                continue;
            }
            LocalDate day;
            try {
                day = LocalDate.parse(date);
            } catch (Exception e) {
                continue;
            }
            if (day.isBefore(from)) {
                continue;
            }
            int quantity = anomaly.path("quantity").asInt();
            double expected = anomaly.path("expected").asDouble();
            String kind = anomaly.path("kind").asText("SPIKE");
            String direction = "DROP".equalsIgnoreCase(kind) ? "muy por debajo" : "muy por encima";
            alerts.add(new Desired(AlertType.ANOMALY, Severity.INFO,
                    "Anomalía de ventas: " + product,
                    "%s: el %s se vendieron %d u. de %s, %s de lo esperado (%.1f u.). Revisá si hubo un error de carga."
                            .formatted(branch, DAY.format(day), quantity, product, direction, expected),
                    productId, null,
                    "ANOMALY:%d:%d:%s".formatted(branchId, productId, day)));
        }
        return alerts;
    }

    // ------------------------------------------------------------------ apoyo

    private Set<String> recentlyDismissed(long tenantId, long branchId, LocalDate today) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("branchId", branchId)
                .addValue("since", OffsetDateTime.ofInstant(
                        today.minusDays(DISMISS_QUIET_DAYS).atStartOfDay(clock.getZone()).toInstant(),
                        ZoneOffset.UTC));
        return new HashSet<>(jdbc.query("""
                select dedupe_key from alerts
                where tenant_id = :tenantId and branch_id = :branchId and status = 'DISMISSED'
                  and updated_at >= :since
                """, params, (rs, rowNum) -> rs.getString(1)));
    }

    private static String key(AlertType type, long branchId, long entityId) {
        return "%s:%d:%d".formatted(type.name(), branchId, entityId);
    }

    private static Alert toAlert(long tenantId, long branchId, Desired desired) {
        Alert alert = new Alert();
        alert.setTenantId(tenantId);
        alert.setBranchId(branchId);
        alert.setType(desired.type());
        alert.setSeverity(desired.severity());
        alert.setProductId(desired.productId());
        alert.setLotId(desired.lotId());
        alert.setTitle(desired.title());
        alert.setMessage(desired.message());
        alert.setDedupeKey(desired.dedupeKey());
        return alert;
    }

    private void notify(long tenantId, long branchId, Desired desired, Long alertId) {
        Set<Role> roles = EXPIRY_TYPES.contains(desired.type())
                ? Set.of(Role.TENANT_ADMIN, Role.TENANT_EMPLOYEE)
                : Set.of(Role.TENANT_ADMIN);
        NotificationDraft draft = new NotificationDraft(NotificationType.ALERT, desired.severity(),
                desired.title(), desired.message(), "/app/alerts", "ALERT", alertId);
        notificationService.notifyBranchUsers(tenantId, branchId, roles, draft);
    }
}
