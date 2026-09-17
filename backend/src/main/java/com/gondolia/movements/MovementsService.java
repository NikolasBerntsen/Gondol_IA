package com.gondolia.movements;

import com.gondolia.common.PageResponse;
import com.gondolia.common.error.BadRequestException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.common.error.ForbiddenException;
import com.gondolia.common.error.NotFoundException;
import com.gondolia.domain.inventory.Lot;
import com.gondolia.domain.inventory.LotRepository;
import com.gondolia.domain.inventory.MovementSource;
import com.gondolia.domain.inventory.MovementType;
import com.gondolia.domain.inventory.StockMovement;
import com.gondolia.domain.user.Role;
import com.gondolia.movements.dto.MovementDtos.AdjustmentRequest;
import com.gondolia.movements.dto.MovementDtos.MovementDto;
import com.gondolia.security.BranchAccessService;
import com.gondolia.security.CurrentUser;
import com.gondolia.stock.StockService;
import com.gondolia.stock.StockService.AdjustCommand;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Historial de movimientos de stock y ajustes manuales (SPEC §6.4).
 * <p>
 * El administrador puede registrar cualquier tipo de ajuste; el empleado, solo mermas
 * ({@code WASTE_EXPIRED}, {@code WASTE_DAMAGED}) y en sus sucursales (SPEC §3.3).
 */
@Service
@RequiredArgsConstructor
public class MovementsService {

    /** Tipos de ajuste permitidos al empleado. */
    public static final Set<MovementType> EMPLOYEE_ADJUSTMENTS =
            EnumSet.of(MovementType.WASTE_EXPIRED, MovementType.WASTE_DAMAGED);

    /** Tipos válidos para el endpoint de ajustes (el resto se registra desde otras operaciones). */
    public static final Set<MovementType> ADMIN_ADJUSTMENTS = EnumSet.of(MovementType.ADJUSTMENT_IN,
            MovementType.ADJUSTMENT_OUT, MovementType.WASTE_EXPIRED, MovementType.WASTE_DAMAGED,
            MovementType.RECALL_REMOVAL);

    private static final String SELECT = """
            select m.id, m.branch_id, m.product_id, m.lot_id, m.type, m.quantity, m.unit_price, m.discount_pct,
                   m.total_amount, m.source, m.batch_ref, m.reason, m.user_id, m.occurred_at,
                   p.name as product_name, p.barcode as barcode,
                   l.lot_number as lot_number, l.expiry_date as expiry_date
            from stock_movements m
            join products p on p.id = m.product_id
            left join lots l on l.id = m.lot_id
            """;

    private final StockService stockService;
    private final BranchAccessService branchAccessService;
    private final LotRepository lotRepository;
    private final MovementSupport support;
    private final NamedParameterJdbcTemplate jdbc;

    /** Historial paginado del alcance actual, más reciente primero. */
    @Transactional(readOnly = true)
    public PageResponse<MovementDto> list(Long productId, MovementType type, MovementSource source, String q,
                                          LocalDate from, LocalDate to, Long branchId, int page, int size) {
        support.validateRange(from, to);
        List<Long> branchIds = support.scope(branchId);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", CurrentUser.tenantId())
                .addValue("branchIds", branchIds)
                .addValue("productId", productId)
                .addValue("type", type == null ? null : type.name())
                .addValue("source", source == null ? null : source.name())
                .addValue("from", support.startOfDay(from))
                .addValue("to", support.endOfDay(to))
                .addValue("q", support.like(q));

        String where = """
                where m.tenant_id = :tenantId
                  and m.branch_id in (:branchIds)
                  and (cast(:productId as bigint) is null or m.product_id = :productId)
                  and (cast(:type as text) is null or m.type = :type)
                  and (cast(:source as text) is null or m.source = :source)
                  and (cast(:from as timestamptz) is null or m.occurred_at >= :from)
                  and (cast(:to as timestamptz) is null or m.occurred_at < :to)
                  and (cast(:q as text) is null or p.name ilike :q or coalesce(p.barcode, '') ilike :q
                       or coalesce(m.batch_ref, '') ilike :q or coalesce(l.lot_number, '') ilike :q)
                """;

        Long total = jdbc.queryForObject("""
                select count(*) from stock_movements m
                join products p on p.id = m.product_id
                left join lots l on l.id = m.lot_id
                """ + where, params, Long.class);
        long count = total == null ? 0 : total;
        if (count == 0) {
            return PageResponse.empty(page, size);
        }

        params.addValue("limit", size).addValue("offset", (long) page * size);
        List<Object[]> raw = jdbc.query(SELECT + where + " order by m.occurred_at desc, m.id desc limit :limit offset :offset",
                params, (rs, i) -> new Object[] {
                        rs.getLong("id"), rs.getLong("branch_id"), rs.getLong("product_id"),
                        rs.getObject("lot_id"), rs.getString("type"), rs.getInt("quantity"),
                        rs.getBigDecimal("unit_price"), rs.getBigDecimal("discount_pct"),
                        rs.getBigDecimal("total_amount"), rs.getString("source"), rs.getString("batch_ref"),
                        rs.getString("reason"), rs.getObject("user_id"), rs.getTimestamp("occurred_at"),
                        rs.getString("product_name"), rs.getString("barcode"), rs.getString("lot_number"),
                        rs.getDate("expiry_date")});

        Map<Long, String> branchNames = support.branchNames();
        List<Long> userIds = raw.stream().map(row -> (Long) toLong(row[12])).toList();
        Map<Long, String> userNames = support.userNames(userIds);

        List<MovementDto> content = new ArrayList<>(raw.size());
        for (Object[] row : raw) {
            MovementType movementType = MovementType.valueOf((String) row[4]);
            MovementSource movementSource = MovementSource.valueOf((String) row[9]);
            int quantity = (Integer) row[5];
            Long userId = toLong(row[12]);
            Long branch = (Long) row[1];
            content.add(new MovementDto((Long) row[0], branch, support.branchName(branchNames, branch),
                    (Long) row[2], (String) row[14], (String) row[15], toLong(row[3]), (String) row[16],
                    row[17] == null ? null : ((java.sql.Date) row[17]).toLocalDate(), movementType,
                    MovementLabels.type(movementType), quantity,
                    movementType.isInbound() ? quantity : -quantity, (BigDecimal) row[6], (BigDecimal) row[7],
                    (BigDecimal) row[8], movementSource, MovementLabels.source(movementSource), (String) row[10],
                    (String) row[11], userId == null ? null : userNames.get(userId),
                    row[13] == null ? null : ((Timestamp) row[13]).toInstant()));
        }
        return PageResponse.of(content, page, size, count);
    }

    private static Long toLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    /**
     * Registra un ajuste sobre un lote. Valida el rol (SPEC §3.3) y el acceso a la sucursal del lote
     * antes de delegar en {@link StockService#adjust}.
     */
    @Transactional
    public MovementDto adjust(AdjustmentRequest request) {
        Long tenantId = CurrentUser.tenantId();
        Role role = CurrentUser.role();
        MovementType type = request.type();

        if (!ADMIN_ADJUSTMENTS.contains(type)) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR,
                    "Ese tipo de movimiento no se puede registrar como ajuste");
        }
        if (role != Role.TENANT_ADMIN && !EMPLOYEE_ADJUSTMENTS.contains(type)) {
            throw new ForbiddenException(ErrorCodes.FORBIDDEN,
                    "Solo el administrador puede registrar este tipo de ajuste. Vos podés dar de baja "
                            + "mercadería vencida o dañada.");
        }

        Lot lot = lotRepository.findByIdAndTenantId(request.lotId(), tenantId)
                .orElseThrow(() -> new NotFoundException("No encontramos el lote #" + request.lotId()));
        branchAccessService.assertAccess(lot.getBranchId());

        StockMovement movement = stockService.adjust(new AdjustCommand(tenantId, lot.getId(), type,
                request.quantity(), request.reason(), MovementSource.MANUAL, CurrentUser.id()));
        return describe(movement, lot);
    }

    /** Arma la fila del historial para un movimiento recién registrado. */
    @Transactional(readOnly = true)
    public MovementDto describe(StockMovement movement, Lot lot) {
        Map<Long, String> branchNames = support.branchNames();
        Map<Long, String> userNames = support.userNames(List.of(movement.getUserId() == null ? -1L
                : movement.getUserId()));
        String[] product = jdbc.queryForObject("select name, barcode from products where id = :id",
                new MapSqlParameterSource("id", movement.getProductId()),
                (rs, i) -> new String[] {rs.getString("name"), rs.getString("barcode")});
        String productName = product == null ? null : product[0];
        String barcode = product == null ? null : product[1];
        Instant occurredAt = movement.getOccurredAt();
        int quantity = movement.getQuantity();
        return new MovementDto(movement.getId(), movement.getBranchId(),
                support.branchName(branchNames, movement.getBranchId()), movement.getProductId(), productName,
                barcode, movement.getLotId(), lot.getLotNumber(), lot.getExpiryDate(), movement.getType(),
                MovementLabels.type(movement.getType()), quantity,
                movement.getType().isInbound() ? quantity : -quantity, movement.getUnitPrice(),
                movement.getDiscountPct(), movement.getTotalAmount(), movement.getSource(),
                MovementLabels.source(movement.getSource()), movement.getBatchRef(), movement.getReason(),
                movement.getUserId() == null ? null : userNames.get(movement.getUserId()), occurredAt);
    }
}
