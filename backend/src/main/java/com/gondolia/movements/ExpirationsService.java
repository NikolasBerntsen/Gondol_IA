package com.gondolia.movements;

import com.gondolia.common.PageResponse;
import com.gondolia.common.error.ApiException;
import com.gondolia.common.error.ConflictException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.common.error.NotFoundException;
import com.gondolia.domain.inventory.Lot;
import com.gondolia.domain.inventory.LotRepository;
import com.gondolia.domain.inventory.LotStatus;
import com.gondolia.domain.inventory.MovementSource;
import com.gondolia.domain.inventory.MovementType;
import com.gondolia.domain.inventory.StockMovement;
import com.gondolia.domain.tenant.StockRotation;
import com.gondolia.domain.tenant.TenantSettings;
import com.gondolia.domain.tenant.TenantSettingsRepository;
import com.gondolia.movements.dto.ExpirationDtos.BulkDiscardResultDto;
import com.gondolia.movements.dto.ExpirationDtos.DiscardRequest;
import com.gondolia.movements.dto.ExpirationDtos.ExpirationBucket;
import com.gondolia.movements.dto.ExpirationDtos.ExpirationBucketTotals;
import com.gondolia.movements.dto.ExpirationDtos.ExpirationRowDto;
import com.gondolia.movements.dto.ExpirationDtos.ExpirationSummaryDto;
import com.gondolia.movements.dto.MovementDtos.MovementDto;
import com.gondolia.security.BranchAccessService;
import com.gondolia.security.CurrentUser;
import com.gondolia.stock.StockService;
import com.gondolia.stock.StockService.AdjustCommand;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vencimientos por sucursal: listado por bucket, resumen con valores y descarte de lo vencido
 * (SPEC §4.2, §6.4).
 * <p>
 * Los buckets salen de {@code tenant_settings} más 30 días para "Próximo": Vencido ({@literal <} 0 días) ·
 * Crítico (0..critical_days) · Por vencer (..warning_days) · Próximo (..30).
 */
@Service
@RequiredArgsConstructor
public class ExpirationsService {

    /** Horizonte del bucket "Próximo" (SPEC §4.2). */
    public static final int UPCOMING_DAYS = 30;

    private static final int DEFAULT_CRITICAL_DAYS = 5;
    private static final int DEFAULT_WARNING_DAYS = 15;

    private final StockService stockService;
    private final BranchAccessService branchAccessService;
    private final LotRepository lotRepository;
    private final TenantSettingsRepository tenantSettingsRepository;
    private final MovementsService movementsService;
    private final MovementSupport support;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    /** Umbrales de vencimiento del comercio. */
    public record Thresholds(int criticalDays, int warningDays, int upcomingDays) {
    }

    /** Totales de un bucket junto con su nombre (resultado intermedio de la consulta del resumen). */
    private record BucketRow(ExpirationBucket bucket, ExpirationBucketTotals totals) {
    }

    /** Umbrales configurados para el comercio (o los valores por defecto). */
    @Transactional(readOnly = true)
    public Thresholds thresholds(Long tenantId) {
        TenantSettings settings = tenantSettingsRepository.findById(tenantId).orElse(null);
        int critical = settings == null ? DEFAULT_CRITICAL_DAYS : settings.getExpiryCriticalDays();
        int warning = settings == null ? DEFAULT_WARNING_DAYS : settings.getExpiryWarningDays();
        return new Thresholds(critical, Math.max(warning, critical), UPCOMING_DAYS);
    }

    // ------------------------------------------------------------------ listado

    /** Listado paginado de lotes con vencimiento dentro del horizonte, por fecha de vencimiento. */
    @Transactional(readOnly = true)
    public PageResponse<ExpirationRowDto> list(ExpirationBucket bucket, String q, Long branchId, int page, int size) {
        Long tenantId = CurrentUser.tenantId();
        List<Long> branchIds = support.scope(branchId);
        LocalDate today = LocalDate.now(clock);
        Thresholds thresholds = thresholds(tenantId);

        MapSqlParameterSource params = bucketParams(tenantId, branchIds, today, thresholds)
                .addValue("q", support.like(q));
        String where = baseWhere() + bucketCondition(bucket == null ? ExpirationBucket.ALL : bucket)
                + " and (cast(:q as text) is null or p.name ilike :q or coalesce(p.barcode, '') ilike :q"
                + " or coalesce(l.lot_number, '') ilike :q)";

        Long total = jdbc.queryForObject(
                "select count(*) from lots l join products p on p.id = l.product_id " + where, params, Long.class);
        long count = total == null ? 0 : total;
        if (count == 0) {
            return PageResponse.empty(page, size);
        }

        params.addValue("limit", size).addValue("offset", (long) page * size);
        String sql = rankedCte(stockService.rotationFor(tenantId)) + """
                select l.id, l.branch_id, l.product_id, l.lot_number, l.expiry_date, l.quantity, l.received_at,
                       l.discount_pct, l.status,
                       p.name as product_name, p.barcode as barcode,
                       c.name as category_name,
                       coalesce(l.cost_price, p.cost_price, 0) as unit_cost,
                       coalesce(p.sale_price, 0) as unit_sale,
                       r.rank as rotation_rank
                from lots l
                join products p on p.id = l.product_id
                left join categories c on c.id = p.category_id
                left join ranked r on r.id = l.id
                """ + where + " order by l.expiry_date asc, l.id asc limit :limit offset :offset";

        Map<Long, String> branchNames = support.branchNames();
        List<ExpirationRowDto> content = jdbc.query(sql, params, (rs, i) -> {
            LocalDate expiry = rs.getDate("expiry_date").toLocalDate();
            long daysLeft = ChronoUnit.DAYS.between(today, expiry);
            int quantity = rs.getInt("quantity");
            BigDecimal unitCost = rs.getBigDecimal("unit_cost");
            BigDecimal unitSale = rs.getBigDecimal("unit_sale");
            long branch = rs.getLong("branch_id");
            Object rank = rs.getObject("rotation_rank");
            return new ExpirationRowDto(rs.getLong("id"), branch, support.branchName(branchNames, branch),
                    rs.getLong("product_id"), rs.getString("product_name"), rs.getString("barcode"),
                    rs.getString("category_name"), rs.getString("lot_number"), expiry, daysLeft, quantity,
                    rs.getTimestamp("received_at").toInstant(), rank == null ? null : ((Number) rank).intValue(),
                    unitCost.multiply(BigDecimal.valueOf(quantity)),
                    unitSale.multiply(BigDecimal.valueOf(quantity)),
                    bucketOf(daysLeft, thresholds), rs.getBigDecimal("discount_pct"),
                    LotStatus.valueOf(rs.getString("status")));
        });
        return PageResponse.of(content, page, size, count);
    }

    /** Resumen por bucket: lotes, unidades y valor a costo y a precio de venta. */
    @Transactional(readOnly = true)
    public ExpirationSummaryDto summary(Long branchId) {
        Long tenantId = CurrentUser.tenantId();
        List<Long> branchIds = support.scope(branchId);
        LocalDate today = LocalDate.now(clock);
        Thresholds thresholds = thresholds(tenantId);

        String sql = """
                select case when l.expiry_date < :today then 'EXPIRED'
                            when l.expiry_date <= :criticalUntil then 'CRITICAL'
                            when l.expiry_date <= :warningUntil then 'WARNING'
                            else 'UPCOMING' end                                      as bucket,
                       count(*)                                                      as lots,
                       coalesce(sum(l.quantity), 0)                                  as units,
                       coalesce(sum(l.quantity * coalesce(l.cost_price, p.cost_price, 0)), 0) as cost_value,
                       coalesce(sum(l.quantity * coalesce(p.sale_price, 0)), 0)      as sale_value
                from lots l join products p on p.id = l.product_id
                """ + baseWhere() + " group by 1";

        List<BucketRow> rows = jdbc.query(sql, bucketParams(tenantId, branchIds, today, thresholds),
                (rs, i) -> new BucketRow(ExpirationBucket.valueOf(rs.getString("bucket")),
                        new ExpirationBucketTotals(rs.getLong("lots"), rs.getLong("units"),
                                rs.getBigDecimal("cost_value"), rs.getBigDecimal("sale_value"))));

        Map<ExpirationBucket, ExpirationBucketTotals> totals = new EnumMap<>(ExpirationBucket.class);
        rows.forEach(row -> totals.put(row.bucket(), row.totals()));

        return new ExpirationSummaryDto(
                totals.getOrDefault(ExpirationBucket.EXPIRED, ExpirationBucketTotals.empty()),
                totals.getOrDefault(ExpirationBucket.CRITICAL, ExpirationBucketTotals.empty()),
                totals.getOrDefault(ExpirationBucket.WARNING, ExpirationBucketTotals.empty()),
                totals.getOrDefault(ExpirationBucket.UPCOMING, ExpirationBucketTotals.empty()),
                thresholds.criticalDays(), thresholds.warningDays(), thresholds.upcomingDays(), today);
    }

    private MapSqlParameterSource bucketParams(Long tenantId, List<Long> branchIds, LocalDate today,
                                               Thresholds thresholds) {
        return new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("branchIds", branchIds)
                .addValue("today", Date.valueOf(today))
                .addValue("criticalUntil", Date.valueOf(today.plusDays(thresholds.criticalDays())))
                .addValue("warningUntil", Date.valueOf(today.plusDays(thresholds.warningDays())))
                .addValue("upcomingUntil", Date.valueOf(today.plusDays(thresholds.upcomingDays())));
    }

    private String baseWhere() {
        return """
                where l.tenant_id = :tenantId
                  and l.branch_id in (:branchIds)
                  and l.status = 'ACTIVE'
                  and l.quantity > 0
                  and l.expiry_date is not null
                  and l.expiry_date <= :upcomingUntil
                """;
    }

    private String bucketCondition(ExpirationBucket bucket) {
        return switch (bucket) {
            case ALL -> "";
            case EXPIRED -> " and l.expiry_date < :today";
            case CRITICAL -> " and l.expiry_date >= :today and l.expiry_date <= :criticalUntil";
            case WARNING -> " and l.expiry_date > :criticalUntil and l.expiry_date <= :warningUntil";
            case UPCOMING -> " and l.expiry_date > :warningUntil and l.expiry_date <= :upcomingUntil";
        };
    }

    /** CTE con el orden de rotación (liquidación primero, después FIFO o FEFO) sobre los lotes vendibles. */
    private String rankedCte(StockRotation rotation) {
        String order = rotation == StockRotation.FEFO
                ? "l.expiry_date asc nulls last, l.received_at asc, l.id asc"
                : "l.received_at asc, l.id asc";
        return "with ranked as (select l.id, row_number() over (partition by l.branch_id, l.product_id"
                + " order by (case when l.discount_pct is null or l.discount_pct <= 0 then 1 else 0 end), "
                + order + ") as rank from lots l where l.tenant_id = :tenantId and l.branch_id in (:branchIds)"
                + " and l.status = 'ACTIVE' and l.quantity > 0"
                + " and (l.expiry_date is null or l.expiry_date >= :today)) ";
    }

    /** Bucket de un lote según los días que le quedan (SPEC §4.2). */
    public static ExpirationBucket bucketOf(long daysLeft, Thresholds thresholds) {
        if (daysLeft < 0) {
            return ExpirationBucket.EXPIRED;
        }
        if (daysLeft <= thresholds.criticalDays()) {
            return ExpirationBucket.CRITICAL;
        }
        if (daysLeft <= thresholds.warningDays()) {
            return ExpirationBucket.WARNING;
        }
        return ExpirationBucket.UPCOMING;
    }

    // ------------------------------------------------------------------ descarte

    /** Descarta un lote (todo el remanente si no se indica cantidad) como {@code WASTE_EXPIRED}. */
    @Transactional
    public MovementDto discard(Long lotId, DiscardRequest request) {
        Long tenantId = CurrentUser.tenantId();
        Lot lot = lotRepository.findByIdAndTenantId(lotId, tenantId)
                .orElseThrow(() -> new NotFoundException("No encontramos el lote #" + lotId));
        branchAccessService.assertAccess(lot.getBranchId());
        if (lot.getQuantity() <= 0) {
            throw new ConflictException(ErrorCodes.INSUFFICIENT_STOCK, "Ese lote ya no tiene unidades para descartar");
        }
        int quantity = request == null || request.quantity() == null ? lot.getQuantity() : request.quantity();
        String reason = request == null || request.reason() == null || request.reason().isBlank()
                ? "Descarte por vencimiento" : request.reason().strip();

        StockMovement movement = stockService.adjust(new AdjustCommand(tenantId, lot.getId(),
                MovementType.WASTE_EXPIRED, quantity, reason, MovementSource.MANUAL, CurrentUser.id()));
        return movementsService.describe(movement, lot);
    }

    /**
     * Descarta **todos** los lotes vencidos con remanente del alcance (o de una sucursal). Cada lote se
     * descarta en su propia transacción ({@code StockService.adjust}): si uno falla, los demás siguen.
     */
    public BulkDiscardResultDto discardAllExpired(Long branchId, String reason) {
        Long tenantId = CurrentUser.tenantId();
        List<Long> branchIds = support.scope(branchId);
        LocalDate today = LocalDate.now(clock);

        List<Long> lotIds = jdbc.queryForList("""
                select l.id from lots l
                where l.tenant_id = :tenantId
                  and l.branch_id in (:branchIds)
                  and l.status = 'ACTIVE'
                  and l.quantity > 0
                  and l.expiry_date is not null
                  and l.expiry_date < :today
                order by l.id
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("branchIds", branchIds)
                .addValue("today", Date.valueOf(today)), Long.class);

        String note = reason == null || reason.isBlank() ? "Descarte masivo de vencidos" : reason.strip();
        int lots = 0;
        int units = 0;
        int failed = 0;
        BigDecimal costValue = BigDecimal.ZERO;
        for (Long lotId : lotIds) {
            Lot lot = lotRepository.findByIdAndTenantId(lotId, tenantId).orElse(null);
            if (lot == null || lot.getQuantity() <= 0) {
                continue;
            }
            int quantity = lot.getQuantity();
            try {
                StockMovement movement = stockService.adjust(new AdjustCommand(tenantId, lotId,
                        MovementType.WASTE_EXPIRED, quantity, note, MovementSource.MANUAL, CurrentUser.id()));
                lots++;
                units += quantity;
                costValue = costValue.add(movement.getTotalAmount() == null ? BigDecimal.ZERO
                        : movement.getTotalAmount());
            } catch (ApiException ex) {
                failed++;
            }
        }
        return new BulkDiscardResultDto(lots, units, costValue, failed);
    }
}
