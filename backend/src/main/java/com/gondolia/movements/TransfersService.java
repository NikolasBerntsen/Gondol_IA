package com.gondolia.movements;

import com.gondolia.common.PageResponse;
import com.gondolia.common.error.BadRequestException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.common.error.NotFoundException;
import com.gondolia.domain.inventory.Lot;
import com.gondolia.domain.inventory.LotRepository;
import com.gondolia.domain.inventory.LotStatus;
import com.gondolia.domain.inventory.MovementType;
import com.gondolia.domain.inventory.Product;
import com.gondolia.domain.inventory.ProductRepository;
import com.gondolia.domain.inventory.StockMovement;
import com.gondolia.domain.inventory.StockMovementRepository;
import com.gondolia.domain.tenant.StockRotation;
import com.gondolia.movements.dto.TransferDtos.TransferDto;
import com.gondolia.movements.dto.TransferDtos.TransferItemDto;
import com.gondolia.movements.dto.TransferDtos.TransferItemRequest;
import com.gondolia.movements.dto.TransferDtos.TransferRequest;
import com.gondolia.movements.dto.TransferDtos.TransferSummaryDto;
import com.gondolia.movements.dto.TransferDtos.TransferableLotDto;
import com.gondolia.recall.RecallMatchingService;
import com.gondolia.recall.RecallMatchingService.RecallInfo;
import com.gondolia.security.BranchAccessService;
import com.gondolia.security.CurrentUser;
import com.gondolia.stock.StockService;
import com.gondolia.stock.StockService.TransferCommand;
import com.gondolia.stock.StockService.TransferItem;
import com.gondolia.stock.StockService.TransferResult;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transferencias de stock entre sucursales (SPEC §4.2, §6.4). Requieren el módulo {@code MULTI_BRANCH}.
 * <p>
 * La lógica vive en {@link StockService#transfer}: el lote destino conserva número, vencimiento, costo,
 * proveedor y el {@code received_at} original (antigüedad para FIFO) y pasa por el chequeo de recall.
 */
@Service
@RequiredArgsConstructor
public class TransfersService {

    private final StockService stockService;
    private final BranchAccessService branchAccessService;
    private final RecallMatchingService recallMatchingService;
    private final LotRepository lotRepository;
    private final ProductRepository productRepository;
    private final StockMovementRepository movementRepository;
    private final MovementSupport support;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    // ------------------------------------------------------------------ alta

    /** Registra una transferencia entre dos sucursales del comercio. */
    @Transactional
    public TransferDto transfer(TransferRequest request) {
        Long tenantId = CurrentUser.tenantId();
        if (request.fromBranchId().equals(request.toBranchId())) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR,
                    "La sucursal de origen y la de destino tienen que ser distintas");
        }
        branchAccessService.assertAccess(request.fromBranchId());
        branchAccessService.assertAccess(request.toBranchId());

        Set<Long> lotIds = new LinkedHashSet<>();
        List<TransferItem> items = new ArrayList<>();
        for (TransferItemRequest item : request.items()) {
            if (!lotIds.add(item.lotId())) {
                throw new BadRequestException(ErrorCodes.VALIDATION_ERROR,
                        "Cargaste el mismo lote dos veces: sumá las cantidades en una sola línea");
            }
            items.add(new TransferItem(item.lotId(), item.quantity()));
        }

        TransferResult result = stockService.transfer(new TransferCommand(tenantId, request.fromBranchId(),
                request.toBranchId(), items, request.note(), CurrentUser.id()));

        Map<Long, String> branchNames = support.branchNames();
        Map<Long, Product> products = new HashMap<>();
        Set<Long> productIds = new LinkedHashSet<>();
        result.destinationLots().forEach(lot -> productIds.add(lot.getProductId()));
        productRepository.findByTenantIdAndIdIn(tenantId, productIds).forEach(p -> products.put(p.getId(), p));

        Set<Long> quarantined = new HashSet<>();
        result.destinationLots().forEach(lot -> {
            if (lot.getStatus() == LotStatus.RECALLED) {
                quarantined.add(lot.getId());
            }
        });

        List<TransferItemDto> dtoItems = new ArrayList<>();
        int units = 0;
        BigDecimal costValue = BigDecimal.ZERO;
        for (int i = 0; i < result.outMovements().size(); i++) {
            StockMovement movement = result.outMovements().get(i);
            Lot destination = result.destinationLots().get(i);
            Product product = products.get(destination.getProductId());
            units += movement.getQuantity();
            costValue = costValue.add(movement.getTotalAmount() == null ? BigDecimal.ZERO
                    : movement.getTotalAmount());
            dtoItems.add(new TransferItemDto(movement.getLotId(), destination.getId(), destination.getProductId(),
                    product == null ? "Producto #" + destination.getProductId() : product.getName(),
                    product == null ? null : product.getBarcode(), destination.getLotNumber(),
                    destination.getExpiryDate(), movement.getQuantity(), destination.getCostPrice(),
                    quarantined.contains(destination.getId())));
        }

        List<RecallInfo> recalls = recallMatchingService.toRecallInfos(result.recallMatches());
        Map<Long, String> userNames = support.userNames(List.of(CurrentUser.id()));
        return new TransferDto(result.batchRef(), request.fromBranchId(),
                support.branchName(branchNames, request.fromBranchId()), request.toBranchId(),
                support.branchName(branchNames, request.toBranchId()),
                result.outMovements().isEmpty() ? clock.instant() : result.outMovements().getFirst().getOccurredAt(),
                dtoItems, units, costValue, request.note(), userNames.get(CurrentUser.id()), recalls);
    }

    // ------------------------------------------------------------------ historial

    /** Historial de transferencias en las que participa alguna sucursal del alcance. */
    @Transactional(readOnly = true)
    public PageResponse<TransferSummaryDto> list(LocalDate from, LocalDate to, Long branchId, int page, int size) {
        support.validateRange(from, to);
        List<Long> branchIds = support.scope(branchId);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", CurrentUser.tenantId())
                .addValue("branchIds", branchIds)
                .addValue("from", support.startOfDay(from))
                .addValue("to", support.endOfDay(to));

        String grouped = """
                select m.batch_ref                                                         as batch_ref,
                       (array_agg(m.branch_id order by m.id) filter (where m.type = 'TRANSFER_OUT'))[1] as from_branch,
                       (array_agg(m.branch_id order by m.id) filter (where m.type = 'TRANSFER_IN'))[1]  as to_branch,
                       min(m.occurred_at)                                                  as occurred_at,
                       count(*) filter (where m.type = 'TRANSFER_OUT')                     as items_count,
                       coalesce(sum(m.quantity) filter (where m.type = 'TRANSFER_OUT'), 0)  as units,
                       coalesce(sum(m.total_amount) filter (where m.type = 'TRANSFER_OUT'), 0) as cost_value,
                       (array_agg(m.user_id order by m.id))[1]                             as user_id,
                       (array_agg(m.reason order by m.id))[1]                              as note
                from stock_movements m
                where m.tenant_id = :tenantId
                  and m.batch_ref is not null
                  and m.type in ('TRANSFER_OUT', 'TRANSFER_IN')
                  and (cast(:from as timestamptz) is null or m.occurred_at >= :from)
                  and (cast(:to as timestamptz) is null or m.occurred_at < :to)
                group by m.batch_ref
                having bool_or(m.branch_id in (:branchIds))
                """;

        Long total = jdbc.queryForObject("select count(*) from (" + grouped + ") t", params, Long.class);
        long count = total == null ? 0 : total;
        if (count == 0) {
            return PageResponse.empty(page, size);
        }

        params.addValue("limit", size).addValue("offset", (long) page * size);
        Map<Long, String> branchNames = support.branchNames();
        List<Object[]> rows = jdbc.query(grouped + " order by occurred_at desc, batch_ref desc limit :limit offset :offset",
                params, (rs, i) -> new Object[] {
                        rs.getString("batch_ref"), rs.getObject("from_branch"), rs.getObject("to_branch"),
                        rs.getTimestamp("occurred_at"), rs.getInt("items_count"), rs.getInt("units"),
                        rs.getBigDecimal("cost_value"), rs.getObject("user_id"), rs.getString("note")});

        List<Long> userIds = rows.stream().map(row -> row[7] == null ? null : ((Number) row[7]).longValue()).toList();
        Map<Long, String> userNames = support.userNames(userIds);

        List<TransferSummaryDto> content = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            Long fromBranch = row[1] == null ? null : ((Number) row[1]).longValue();
            Long toBranch = row[2] == null ? null : ((Number) row[2]).longValue();
            Long userId = row[7] == null ? null : ((Number) row[7]).longValue();
            content.add(new TransferSummaryDto((String) row[0], fromBranch,
                    support.branchName(branchNames, fromBranch), toBranch, support.branchName(branchNames, toBranch),
                    ((java.sql.Timestamp) row[3]).toInstant(), (Integer) row[4], (Integer) row[5],
                    (BigDecimal) row[6], userId == null ? null : userNames.get(userId), (String) row[8]));
        }
        return PageResponse.of(content, page, size, count);
    }

    /** Detalle de una transferencia por su {@code batchRef}. */
    @Transactional(readOnly = true)
    public TransferDto detail(String batchRef) {
        Long tenantId = CurrentUser.tenantId();
        List<StockMovement> movements = movementRepository.findByTenantIdAndBatchRefOrderByIdAsc(tenantId, batchRef)
                .stream().filter(m -> m.getType().isTransfer()).toList();
        if (movements.isEmpty()) {
            throw new NotFoundException("No encontramos la transferencia " + batchRef);
        }
        List<StockMovement> outs = movements.stream().filter(m -> m.getType() == MovementType.TRANSFER_OUT).toList();
        List<StockMovement> ins = movements.stream().filter(m -> m.getType() == MovementType.TRANSFER_IN).toList();
        Long fromBranch = outs.isEmpty() ? null : outs.getFirst().getBranchId();
        Long toBranch = ins.isEmpty() ? null : ins.getFirst().getBranchId();

        boolean accessible = (fromBranch != null && branchAccessService.canAccess(CurrentUser.id(), fromBranch))
                || (toBranch != null && branchAccessService.canAccess(CurrentUser.id(), toBranch));
        if (!accessible) {
            throw new NotFoundException("No encontramos la transferencia " + batchRef);
        }

        Set<Long> productIds = new LinkedHashSet<>();
        Set<Long> lotIds = new LinkedHashSet<>();
        movements.forEach(m -> {
            productIds.add(m.getProductId());
            if (m.getLotId() != null) {
                lotIds.add(m.getLotId());
            }
        });
        Map<Long, Product> products = new HashMap<>();
        productRepository.findByTenantIdAndIdIn(tenantId, productIds).forEach(p -> products.put(p.getId(), p));
        Map<Long, Lot> lots = new HashMap<>();
        if (!lotIds.isEmpty()) {
            lotRepository.findByTenantIdAndIdIn(tenantId, lotIds).forEach(l -> lots.put(l.getId(), l));
        }

        List<TransferItemDto> items = new ArrayList<>();
        int units = 0;
        BigDecimal costValue = BigDecimal.ZERO;
        for (int i = 0; i < outs.size(); i++) {
            StockMovement out = outs.get(i);
            StockMovement in = i < ins.size() ? ins.get(i) : null;
            Lot sourceLot = out.getLotId() == null ? null : lots.get(out.getLotId());
            Lot destinationLot = in == null || in.getLotId() == null ? null : lots.get(in.getLotId());
            Product product = products.get(out.getProductId());
            units += out.getQuantity();
            costValue = costValue.add(out.getTotalAmount() == null ? BigDecimal.ZERO : out.getTotalAmount());
            Lot reference = destinationLot != null ? destinationLot : sourceLot;
            items.add(new TransferItemDto(out.getLotId(), in == null ? null : in.getLotId(), out.getProductId(),
                    product == null ? "Producto #" + out.getProductId() : product.getName(),
                    product == null ? null : product.getBarcode(),
                    reference == null ? null : reference.getLotNumber(),
                    reference == null ? null : reference.getExpiryDate(), out.getQuantity(),
                    reference == null ? null : reference.getCostPrice(),
                    destinationLot != null && destinationLot.getStatus() == LotStatus.RECALLED));
        }

        Map<Long, String> branchNames = support.branchNames();
        Long userId = movements.getFirst().getUserId();
        Map<Long, String> userNames = support.userNames(List.of(userId == null ? -1L : userId));
        Instant occurredAt = movements.getFirst().getOccurredAt();
        return new TransferDto(batchRef, fromBranch, support.branchName(branchNames, fromBranch), toBranch,
                support.branchName(branchNames, toBranch), occurredAt, items, units, costValue,
                movements.getFirst().getReason(), userId == null ? null : userNames.get(userId), List.of());
    }

    // ------------------------------------------------------------------ lotes disponibles

    /**
     * Lotes que se pueden transferir desde una sucursal: vendibles (activos, con remanente y sin vencer),
     * en el orden de rotación del comercio y con su posición ("1º sale").
     */
    @Transactional(readOnly = true)
    public PageResponse<TransferableLotDto> availableLots(Long branchId, String q, int page, int size) {
        Long tenantId = CurrentUser.tenantId();
        branchAccessService.assertAccess(branchId);
        LocalDate today = LocalDate.now(clock);
        StockRotation rotation = stockService.rotationFor(tenantId);
        String order = rotation == StockRotation.FEFO
                ? "l.expiry_date asc nulls last, l.received_at asc, l.id asc"
                : "l.received_at asc, l.id asc";

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("branchId", branchId)
                .addValue("today", Date.valueOf(today))
                .addValue("q", support.like(q));

        String where = """
                where l.tenant_id = :tenantId
                  and l.branch_id = :branchId
                  and l.status = 'ACTIVE'
                  and l.quantity > 0
                  and (l.expiry_date is null or l.expiry_date >= :today)
                  and (cast(:q as text) is null or p.name ilike :q or coalesce(p.barcode, '') ilike :q
                       or coalesce(l.lot_number, '') ilike :q)
                """;

        Long total = jdbc.queryForObject(
                "select count(*) from lots l join products p on p.id = l.product_id " + where, params, Long.class);
        long count = total == null ? 0 : total;
        if (count == 0) {
            return PageResponse.empty(page, size);
        }

        params.addValue("limit", size).addValue("offset", (long) page * size);
        String sql = "select l.id, l.branch_id, l.product_id, l.lot_number, l.expiry_date, l.quantity, l.received_at,"
                + " l.cost_price, l.discount_pct, p.name as product_name, p.barcode as barcode,"
                + " row_number() over (partition by l.product_id order by"
                + " (case when l.discount_pct is null or l.discount_pct <= 0 then 1 else 0 end), " + order
                + ") as rotation_rank"
                + " from lots l join products p on p.id = l.product_id " + where
                + " order by p.name asc, rotation_rank asc limit :limit offset :offset";

        Map<Long, String> branchNames = support.branchNames();
        List<TransferableLotDto> content = jdbc.query(sql, params, (rs, i) -> {
            Date expiry = rs.getDate("expiry_date");
            LocalDate expiryDate = expiry == null ? null : expiry.toLocalDate();
            return new TransferableLotDto(rs.getLong("id"), rs.getLong("branch_id"), support.branchName(branchNames,
                    rs.getLong("branch_id")), rs.getLong("product_id"), rs.getString("product_name"),
                    rs.getString("barcode"), rs.getString("lot_number"), expiryDate,
                    expiryDate == null ? null : ChronoUnit.DAYS.between(today, expiryDate), rs.getInt("quantity"),
                    rs.getTimestamp("received_at").toInstant(), rs.getInt("rotation_rank"),
                    rs.getBigDecimal("cost_price"), rs.getBigDecimal("discount_pct"));
        });
        return PageResponse.of(content, page, size, count);
    }
}
