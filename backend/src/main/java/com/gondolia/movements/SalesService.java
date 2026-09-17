package com.gondolia.movements;

import com.gondolia.common.PageResponse;
import com.gondolia.common.error.BadRequestException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.common.error.NotFoundException;
import com.gondolia.domain.inventory.Lot;
import com.gondolia.domain.inventory.LotRepository;
import com.gondolia.domain.inventory.MovementSource;
import com.gondolia.domain.inventory.MovementType;
import com.gondolia.domain.inventory.Product;
import com.gondolia.domain.inventory.ProductRepository;
import com.gondolia.domain.inventory.StockMovement;
import com.gondolia.domain.inventory.StockMovementRepository;
import com.gondolia.domain.pos.PosSale;
import com.gondolia.domain.pos.PosSaleRepository;
import com.gondolia.movements.dto.SaleDtos.SaleDetailDto;
import com.gondolia.movements.dto.SaleDtos.SaleDto;
import com.gondolia.movements.dto.SaleDtos.SaleItemRequest;
import com.gondolia.movements.dto.SaleDtos.SaleLineDto;
import com.gondolia.movements.dto.SaleDtos.SaleLotDto;
import com.gondolia.movements.dto.SaleDtos.SaleRequest;
import com.gondolia.movements.dto.SaleDtos.SaleSummaryDto;
import com.gondolia.security.BranchAccessService;
import com.gondolia.security.CurrentUser;
import com.gondolia.stock.BatchRefs;
import com.gondolia.stock.StockService;
import com.gondolia.stock.StockService.SaleCommand;
import com.gondolia.stock.StockService.SaleResult;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ventas manuales e historial de ventas de todas las fuentes (SPEC §6.4).
 * <p>
 * El historial agrupa {@code stock_movements} por {@code batch_ref} y **neteá las anulaciones**
 * ({@code SALE_VOID}, SPEC §15.1): las unidades y el importe de cada venta se calculan como
 * {@code SALE − SALE_VOID}. El origen sale de {@code MovementSource} y, si la venta salió del POS GondolIA,
 * se agrega el {@code ticketCode} de {@code pos_sales} para enlazar el comprobante.
 */
@Service
@RequiredArgsConstructor
public class SalesService {

    /** Tipos que componen las ventas netas. */
    private static final String SALE_TYPES = "('SALE','SALE_VOID')";

    private final StockService stockService;
    private final BranchAccessService branchAccessService;
    private final MovementSupport support;
    private final ProductRepository productRepository;
    private final LotRepository lotRepository;
    private final StockMovementRepository movementRepository;
    private final PosSaleRepository posSaleRepository;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    // ------------------------------------------------------------------ venta manual

    /**
     * Registra una venta manual: una llamada a {@link StockService#registerSale} por línea, **ordenadas por
     * productId** (para no bloquearse con otra venta concurrente) y con el mismo {@code batchRef}.
     */
    @Transactional
    public SaleDto register(SaleRequest request) {
        Long tenantId = CurrentUser.tenantId();
        Long branchId = branchAccessService.requireSingleBranch(request.branchId());
        Instant occurredAt = resolveOccurredAt(request.occurredAt());

        Map<Long, SaleItemRequest> byProduct = new LinkedHashMap<>();
        for (SaleItemRequest item : request.items()) {
            if (byProduct.putIfAbsent(item.productId(), item) != null) {
                throw new BadRequestException(ErrorCodes.VALIDATION_ERROR,
                        "Cargaste el mismo producto en dos líneas: sumá las cantidades en una sola");
            }
        }

        Map<Long, Product> products = loadProducts(tenantId, byProduct.keySet());
        String batchRef = BatchRefs.sale(clock);

        List<SaleLineDto> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        int units = 0;
        int shortageUnits = 0;

        List<Long> orderedProductIds = byProduct.keySet().stream().sorted().toList();
        for (Long productId : orderedProductIds) {
            SaleItemRequest item = byProduct.get(productId);
            Product product = products.get(productId);
            SaleResult result = stockService.registerSale(new SaleCommand(tenantId, branchId, productId,
                    item.quantity(), item.unitPrice(), occurredAt, MovementSource.MANUAL, CurrentUser.id(), batchRef));
            lines.add(toLine(product, item.quantity(), result));
            total = total.add(result.totalAmount() == null ? BigDecimal.ZERO : result.totalAmount());
            units += item.quantity();
            shortageUnits += result.shortageQuantity();
        }

        // Se devuelven en el orden en que las cargó el usuario, aunque se hayan procesado ordenadas por id.
        List<SaleLineDto> ordered = new ArrayList<>(lines);
        List<Long> requestOrder = new ArrayList<>(byProduct.keySet());
        ordered.sort(Comparator.comparingInt(line -> requestOrder.indexOf(line.productId())));

        return new SaleDto(batchRef, branchId, support.branchName(support.branchNames(), branchId), occurredAt,
                ordered, total, units, shortageUnits);
    }

    private Instant resolveOccurredAt(Instant requested) {
        Instant now = clock.instant();
        if (requested == null) {
            return now;
        }
        if (requested.isAfter(now)) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR,
                    "La fecha de la venta no puede ser futura");
        }
        return requested;
    }

    private Map<Long, Product> loadProducts(Long tenantId, Set<Long> productIds) {
        Map<Long, Product> products = new HashMap<>();
        for (Product product : productRepository.findByTenantIdAndIdIn(tenantId, productIds)) {
            products.put(product.getId(), product);
        }
        for (Long productId : productIds) {
            if (!products.containsKey(productId)) {
                throw new NotFoundException("No encontramos el producto #" + productId);
            }
        }
        return products;
    }

    private SaleLineDto toLine(Product product, int requested, SaleResult result) {
        List<SaleLotDto> lots = new ArrayList<>();
        BigDecimal lineTotal = BigDecimal.ZERO;
        BigDecimal unitPrice = null;
        BigDecimal discountPct = null;
        int shortage = 0;
        for (StockMovement movement : result.movements()) {
            BigDecimal amount = movement.getTotalAmount() == null ? BigDecimal.ZERO : movement.getTotalAmount();
            lineTotal = lineTotal.add(amount);
            if (unitPrice == null) {
                unitPrice = movement.getUnitPrice();
            }
            if (movement.getLotId() == null) {
                shortage += movement.getQuantity();
                continue;
            }
            Lot lot = lotRepository.findById(movement.getLotId()).orElse(null);
            if (movement.getDiscountPct() != null && discountPct == null) {
                discountPct = movement.getDiscountPct();
            }
            lots.add(new SaleLotDto(movement.getLotId(), lot == null ? null : lot.getLotNumber(),
                    lot == null ? null : lot.getExpiryDate(), movement.getQuantity(), movement.getUnitPrice(),
                    movement.getDiscountPct()));
        }
        return new SaleLineDto(product.getId(), product.getName(), product.getBarcode(), requested,
                unitPrice == null ? product.getSalePrice() : unitPrice, discountPct, lineTotal, shortage, lots);
    }

    // ------------------------------------------------------------------ historial

    /**
     * Historial de ventas del alcance, agrupado por {@code batchRef} y neteando las anulaciones.
     * {@code source} filtra por origen (POS GondolIA, POS externo, CSV, manual, importación).
     */
    @Transactional(readOnly = true)
    public PageResponse<SaleSummaryDto> list(LocalDate from, LocalDate to, MovementSource source, String q,
                                             Long branchId, int page, int size) {
        support.validateRange(from, to);
        Long tenantId = CurrentUser.tenantId();
        List<Long> branchIds = support.scope(branchId);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("branchIds", branchIds)
                .addValue("from", support.startOfDay(from))
                .addValue("to", support.endOfDay(to))
                .addValue("source", source == null ? null : source.name())
                .addValue("q", support.like(q));

        String having = """
                having (cast(:from as timestamptz) is null or min(m.occurred_at) filter (where m.type = 'SALE') >= :from)
                   and (cast(:to as timestamptz) is null or min(m.occurred_at) filter (where m.type = 'SALE') < :to)
                   and (cast(:source as text) is null
                        or (array_agg(m.source order by m.id) filter (where m.type = 'SALE'))[1] = :source)
                   and (cast(:q as text) is null or bool_or(m.batch_ref ilike :q or coalesce(p.name, '') ilike :q
                                              or coalesce(p.barcode, '') ilike :q))
                """;

        String grouped = """
                select m.batch_ref                                                        as batch_ref,
                       (array_agg(m.branch_id order by m.id))[1]                          as branch_id,
                       min(m.occurred_at) filter (where m.type = 'SALE')                  as occurred_at,
                       (array_agg(m.source order by m.id) filter (where m.type = 'SALE'))[1]  as source,
                       (array_agg(m.user_id order by m.id) filter (where m.type = 'SALE'))[1] as user_id,
                       count(distinct m.product_id) filter (where m.type = 'SALE')        as items_count,
                       coalesce(sum(m.quantity) filter (where m.type = 'SALE'), 0)
                         - coalesce(sum(m.quantity) filter (where m.type = 'SALE_VOID'), 0) as units,
                       coalesce(sum(m.total_amount) filter (where m.type = 'SALE'), 0)
                         - coalesce(sum(m.total_amount) filter (where m.type = 'SALE_VOID'), 0) as total,
                       coalesce(sum(m.quantity) filter (where m.type = 'SALE_VOID'), 0)    as voided_units
                from stock_movements m
                left join products p on p.id = m.product_id
                where m.tenant_id = :tenantId
                  and m.branch_id in (:branchIds)
                  and m.batch_ref is not null
                  and m.type in """ + SALE_TYPES + """

                group by m.batch_ref
                """ + having;

        Long totalElements = jdbc.queryForObject("select count(*) from (" + grouped + ") s", params, Long.class);
        long count = totalElements == null ? 0 : totalElements;
        if (count == 0) {
            return PageResponse.empty(page, size);
        }

        params.addValue("limit", size).addValue("offset", (long) page * size);
        List<SaleRow> rows = jdbc.query(grouped + " order by occurred_at desc, batch_ref desc limit :limit offset :offset",
                params, (rs, i) -> new SaleRow(
                        rs.getString("batch_ref"),
                        rs.getLong("branch_id"),
                        rs.getTimestamp("occurred_at") == null ? null : rs.getTimestamp("occurred_at").toInstant(),
                        MovementSource.valueOf(rs.getString("source")),
                        rs.getObject("user_id") == null ? null : rs.getLong("user_id"),
                        rs.getInt("items_count"),
                        rs.getInt("units"),
                        rs.getBigDecimal("total"),
                        rs.getInt("voided_units")));

        return PageResponse.of(toSummaries(tenantId, rows), page, size, count);
    }

    private record SaleRow(String batchRef, Long branchId, Instant occurredAt, MovementSource source, Long userId,
                           int itemsCount, int units, BigDecimal total, int voidedUnits) {
    }

    private List<SaleSummaryDto> toSummaries(Long tenantId, List<SaleRow> rows) {
        Map<Long, String> branchNames = support.branchNames();
        Map<Long, String> userNames = support.userNames(rows.stream().map(SaleRow::userId).toList());
        Map<String, PosSale> posSales = posSales(tenantId, rows.stream().map(SaleRow::batchRef).toList());

        List<SaleSummaryDto> summaries = new ArrayList<>(rows.size());
        for (SaleRow row : rows) {
            PosSale posSale = posSales.get(row.batchRef());
            summaries.add(new SaleSummaryDto(row.batchRef(), row.branchId(),
                    support.branchName(branchNames, row.branchId()), row.occurredAt(), row.itemsCount(), row.units(),
                    row.total(), row.source(), MovementLabels.source(row.source()),
                    row.userId() == null ? null : userNames.get(row.userId()),
                    row.voidedUnits() > 0, row.voidedUnits(),
                    posSale == null ? null : posSale.getTicketCode(), posSale == null ? null : posSale.getId()));
        }
        return summaries;
    }

    private Map<String, PosSale> posSales(Long tenantId, List<String> batchRefs) {
        Set<String> posRefs = new LinkedHashSet<>();
        for (String ref : batchRefs) {
            if (ref != null && ref.startsWith("P-")) {
                posRefs.add(ref);
            }
        }
        if (posRefs.isEmpty()) {
            return Map.of();
        }
        Map<String, PosSale> map = new HashMap<>();
        for (PosSale sale : posSaleRepository.findByTenantIdAndBatchRefIn(tenantId, posRefs)) {
            map.put(sale.getBatchRef(), sale);
        }
        return map;
    }

    /** Detalle de una venta: cabecera neteada más las líneas con los lotes de los que salió cada unidad. */
    @Transactional(readOnly = true)
    public SaleDetailDto detail(String batchRef) {
        Long tenantId = CurrentUser.tenantId();
        List<StockMovement> movements = movementRepository.findByTenantIdAndBatchRefOrderByIdAsc(tenantId, batchRef)
                .stream()
                .filter(m -> m.getType().isSaleRelated())
                .toList();
        if (movements.isEmpty()) {
            throw new NotFoundException("No encontramos la venta " + batchRef);
        }
        Long branchId = movements.getFirst().getBranchId();
        branchAccessService.assertAccess(branchId);

        Map<Long, Product> products = new HashMap<>();
        Set<Long> productIds = new LinkedHashSet<>();
        Set<Long> lotIds = new LinkedHashSet<>();
        for (StockMovement movement : movements) {
            productIds.add(movement.getProductId());
            if (movement.getLotId() != null) {
                lotIds.add(movement.getLotId());
            }
        }
        productRepository.findByTenantIdAndIdIn(tenantId, productIds).forEach(p -> products.put(p.getId(), p));
        Map<Long, Lot> lots = new HashMap<>();
        if (!lotIds.isEmpty()) {
            lotRepository.findByTenantIdAndIdIn(tenantId, lotIds).forEach(l -> lots.put(l.getId(), l));
        }

        Map<Long, List<StockMovement>> byProduct = new LinkedHashMap<>();
        for (StockMovement movement : movements) {
            if (movement.getType() == MovementType.SALE) {
                byProduct.computeIfAbsent(movement.getProductId(), id -> new ArrayList<>()).add(movement);
            }
        }

        List<SaleLineDto> lines = new ArrayList<>();
        BigDecimal grossTotal = BigDecimal.ZERO;
        int units = 0;
        int itemsCount = byProduct.size();
        for (Map.Entry<Long, List<StockMovement>> entry : byProduct.entrySet()) {
            Product product = products.get(entry.getKey());
            List<SaleLotDto> saleLots = new ArrayList<>();
            BigDecimal lineTotal = BigDecimal.ZERO;
            BigDecimal unitPrice = null;
            BigDecimal discountPct = null;
            int quantity = 0;
            int shortage = 0;
            for (StockMovement movement : entry.getValue()) {
                quantity += movement.getQuantity();
                lineTotal = lineTotal.add(movement.getTotalAmount() == null ? BigDecimal.ZERO
                        : movement.getTotalAmount());
                if (unitPrice == null) {
                    unitPrice = movement.getUnitPrice();
                }
                if (movement.getLotId() == null) {
                    shortage += movement.getQuantity();
                    continue;
                }
                Lot lot = lots.get(movement.getLotId());
                if (movement.getDiscountPct() != null && discountPct == null) {
                    discountPct = movement.getDiscountPct();
                }
                saleLots.add(new SaleLotDto(movement.getLotId(), lot == null ? null : lot.getLotNumber(),
                        lot == null ? null : lot.getExpiryDate(), movement.getQuantity(), movement.getUnitPrice(),
                        movement.getDiscountPct()));
            }
            grossTotal = grossTotal.add(lineTotal);
            units += quantity;
            lines.add(new SaleLineDto(entry.getKey(), product == null ? "Producto #" + entry.getKey()
                    : product.getName(), product == null ? null : product.getBarcode(), quantity, unitPrice,
                    discountPct, lineTotal, shortage, saleLots));
        }

        int voidedUnits = movements.stream().filter(m -> m.getType() == MovementType.SALE_VOID)
                .mapToInt(StockMovement::getQuantity).sum();
        BigDecimal voidedTotal = movements.stream().filter(m -> m.getType() == MovementType.SALE_VOID)
                .map(m -> m.getTotalAmount() == null ? BigDecimal.ZERO : m.getTotalAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        StockMovement first = movements.getFirst();
        Map<Long, String> branchNames = support.branchNames();
        Map<Long, String> userNames = support.userNames(List.of(first.getUserId() == null ? -1L : first.getUserId()));
        PosSale posSale = batchRef.startsWith("P-")
                ? posSaleRepository.findByTenantIdAndBatchRef(tenantId, batchRef).orElse(null) : null;
        String reason = movements.stream().filter(m -> m.getType() == MovementType.SALE_VOID)
                .map(StockMovement::getReason).filter(Objects::nonNull).findFirst().orElse(null);

        SaleSummaryDto summary = new SaleSummaryDto(batchRef, branchId, support.branchName(branchNames, branchId),
                first.getOccurredAt(), itemsCount, units - voidedUnits, grossTotal.subtract(voidedTotal),
                first.getSource(), MovementLabels.source(first.getSource()),
                first.getUserId() == null ? null : userNames.get(first.getUserId()), voidedUnits > 0, voidedUnits,
                posSale == null ? null : posSale.getTicketCode(), posSale == null ? null : posSale.getId());
        return new SaleDetailDto(summary, lines, grossTotal, reason);
    }
}
