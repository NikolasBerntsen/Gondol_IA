package com.gondolia.pos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gondolia.common.PageResponse;
import com.gondolia.common.error.BadRequestException;
import com.gondolia.common.error.ConflictException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.common.error.ForbiddenException;
import com.gondolia.common.error.NotFoundException;
import com.gondolia.domain.common.Timestamps;
import com.gondolia.domain.inventory.Lot;
import com.gondolia.domain.inventory.LotRepository;
import com.gondolia.domain.inventory.MovementSource;
import com.gondolia.domain.inventory.Product;
import com.gondolia.domain.inventory.ProductRepository;
import com.gondolia.domain.inventory.StockMovement;
import com.gondolia.domain.pos.PaymentMethod;
import com.gondolia.domain.pos.PosBranchCounter;
import com.gondolia.domain.pos.PosBranchCounterRepository;
import com.gondolia.domain.pos.PosPayment;
import com.gondolia.domain.pos.PosPaymentRepository;
import com.gondolia.domain.pos.PosRegister;
import com.gondolia.domain.pos.PosRegisterRepository;
import com.gondolia.domain.pos.PosSale;
import com.gondolia.domain.pos.PosSaleItem;
import com.gondolia.domain.pos.PosSaleItemRepository;
import com.gondolia.domain.pos.PosSaleRepository;
import com.gondolia.domain.pos.PosSaleStatus;
import com.gondolia.domain.pos.PosSession;
import com.gondolia.domain.pos.PosSessionRepository;
import com.gondolia.domain.tenant.Branch;
import com.gondolia.domain.tenant.BranchRepository;
import com.gondolia.domain.tenant.Tenant;
import com.gondolia.domain.tenant.TenantRepository;
import com.gondolia.pos.dto.PosPaymentDto;
import com.gondolia.pos.dto.PosSaleDto;
import com.gondolia.pos.dto.PosSaleItemDto;
import com.gondolia.pos.dto.PosSaleLotDto;
import com.gondolia.pos.dto.PosSaleRequest;
import com.gondolia.pos.dto.PosSaleSummaryDto;
import com.gondolia.pos.dto.PosTicketDto;
import com.gondolia.pos.dto.VoidSaleRequest;
import com.gondolia.security.AuthUser;
import com.gondolia.security.BranchAccessService;
import com.gondolia.stock.BatchRefs;
import com.gondolia.stock.StockService;
import com.gondolia.stock.StockService.SaleCommand;
import com.gondolia.stock.StockService.SaleResult;
import com.gondolia.stock.StockService.VoidSaleCommand;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ventas del POS GondolIA (SPEC §15.2): validación, descuento de stock por rotación, numeración por sucursal,
 * persistencia del ticket y anulación.
 */
@Service
@RequiredArgsConstructor
public class PosSaleService {

    /** Prefijo de {@code batch_ref} de las ventas del POS GondolIA. */
    public static final String BATCH_PREFIX = "P";
    private static final String NOT_FOUND = "No encontramos esa venta.";
    private static final String LEGEND = "Comprobante no válido como factura";

    private final PosSaleRepository saleRepository;
    private final PosSaleItemRepository saleItemRepository;
    private final PosPaymentRepository paymentRepository;
    private final PosSessionRepository sessionRepository;
    private final PosRegisterRepository registerRepository;
    private final PosBranchCounterRepository counterRepository;
    private final PosSessionService sessionService;
    private final PosCatalogService catalogService;
    private final ProductRepository productRepository;
    private final LotRepository lotRepository;
    private final TenantRepository tenantRepository;
    private final BranchRepository branchRepository;
    private final StockService stockService;
    private final BranchAccessService branchAccess;
    private final PosDirectory directory;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    // ------------------------------------------------------------------ cobro

    /**
     * Cobra una venta: valida el turno, el stock y los pagos, descuenta con {@code StockService.registerSale}
     * (una llamada por producto, ordenadas por {@code productId}, origen {@code POS_GONDOLIA} y {@code batchRef}
     * {@code P-...} compartido), numera el ticket con {@code pos_branch_counters} y guarda venta, ítems y pagos.
     */
    @Transactional
    public PosSaleDto create(AuthUser user, PosSaleRequest request) {
        Long tenantId = user.tenantId();
        PosSession session = sessionService.requireOpen(user, request.sessionId());
        Long branchId = session.getBranchId();

        Map<Long, Integer> quantities = mergeItems(request.items());
        List<Product> products = productRepository.findByTenantIdAndIdIn(tenantId, quantities.keySet());
        Map<Long, Product> byId = products.stream().collect(Collectors.toMap(Product::getId, product -> product));
        for (Long productId : quantities.keySet()) {
            Product product = byId.get(productId);
            if (product == null) {
                throw new NotFoundException("Uno de los productos del carrito ya no existe. Quitalo y volvé a probar.");
            }
            if (!product.isActive()) {
                throw new ConflictException(ErrorCodes.CONFLICT,
                        "El producto " + product.getName() + " está dado de baja: no se puede vender.");
            }
        }

        Map<Long, PosStockQueries.BranchStock> stock = catalogService.stockOf(tenantId, branchId, quantities.keySet());
        assertNotRecalled(quantities.keySet(), byId, stock);
        if (!request.allowShortage()) {
            assertEnoughStock(quantities, byId, stock);
        }

        String batchRef = BatchRefs.next(BATCH_PREFIX, clock);
        Instant occurredAt = Timestamps.now();
        List<Line> lines = new ArrayList<>();
        // Una llamada por producto, ordenadas por id: así dos cajas nunca se bloquean en orden inverso (SPEC §5.3).
        for (Long productId : quantities.keySet().stream().sorted().toList()) {
            Product product = byId.get(productId);
            int quantity = quantities.get(productId);
            SaleResult result = stockService.registerSale(new SaleCommand(tenantId, branchId, productId, quantity,
                    null, occurredAt, MovementSource.POS_GONDOLIA, user.id(), batchRef));
            lines.add(new Line(product, quantity, result));
        }

        BigDecimal subtotal = PosMoney.ZERO;
        BigDecimal total = PosMoney.ZERO;
        int units = 0;
        boolean hasShortage = false;
        for (Line line : lines) {
            subtotal = subtotal.add(PosMoney.orZero(line.product().getSalePrice())
                    .multiply(BigDecimal.valueOf(line.quantity())));
            total = total.add(PosMoney.orZero(line.result().totalAmount()));
            units += line.quantity();
            hasShortage = hasShortage || line.result().shortageQuantity() > 0;
        }
        subtotal = PosMoney.scale(subtotal);
        total = PosMoney.scale(total);

        Payments payments = validatePayments(request, total);

        PosSale sale = new PosSale();
        sale.setTenantId(tenantId);
        sale.setBranchId(branchId);
        sale.setRegisterId(session.getRegisterId());
        sale.setSessionId(session.getId());
        long number = nextTicketNumber(branchId);
        sale.setNumber(number);
        sale.setTicketCode(PosBranchCounter.ticketCode(branchId, number));
        sale.setStatus(PosSaleStatus.COMPLETED);
        sale.setSubtotal(subtotal);
        sale.setDiscountTotal(PosMoney.scale(subtotal.subtract(total)));
        sale.setTotal(total);
        sale.setItemsCount(lines.size());
        sale.setUnits(units);
        sale.setPaidTotal(payments.paidTotal());
        sale.setChangeAmount(payments.change());
        sale.setCustomerName(blankToNull(request.customerName()));
        sale.setCustomerDoc(blankToNull(request.customerDoc()));
        sale.setCashierId(user.id());
        sale.setBatchRef(batchRef);
        sale.setHasShortage(hasShortage);
        sale.setCreatedAt(occurredAt);
        saleRepository.save(sale);

        Map<Long, Lot> lots = lotsOf(tenantId, lines);
        List<PosSaleItem> items = new ArrayList<>(lines.size());
        for (Line line : lines) {
            items.add(buildItem(sale.getId(), line, lots));
        }
        saleItemRepository.saveAll(items);

        List<PosPayment> paymentRows = new ArrayList<>(payments.lines().size());
        for (PosSaleRequest.Payment payment : payments.lines()) {
            PosPayment row = new PosPayment();
            row.setSaleId(sale.getId());
            row.setMethod(payment.method());
            row.setAmount(PosMoney.scale(payment.amount()));
            row.setReference(blankToNull(payment.reference()));
            row.setCreatedAt(occurredAt);
            paymentRows.add(row);
        }
        paymentRepository.saveAll(paymentRows);

        PosSession locked = sessionService.lock(session.getId());
        sessionService.applySale(locked == null ? session : locked, total, false);

        return toDto(user, sale, items, paymentRows);
    }

    // ------------------------------------------------------------------ anulación

    /**
     * Anula una venta y devuelve el stock a sus lotes ({@code StockService.voidSale}). El administrador puede anular
     * cualquier venta de su alcance; el empleado y el cajero, solo las de su turno abierto.
     */
    @Transactional
    public PosSaleDto voidSale(AuthUser user, Long saleId, VoidSaleRequest request) {
        PosSale sale = require(user.tenantId(), saleId);
        branchAccess.assertAccess(sale.getBranchId());
        if (sale.getStatus() == PosSaleStatus.VOIDED) {
            throw new ConflictException(ErrorCodes.ALREADY_VOIDED, "Esa venta ya está anulada.");
        }
        PosSession session = sessionRepository.findByIdAndTenantId(sale.getSessionId(), user.tenantId())
                .orElseThrow(() -> new NotFoundException(NOT_FOUND));
        if (!PosAccess.isAdmin(user) && !(PosAccess.owns(user, session) && session.isOpen())) {
            throw new ForbiddenException(ErrorCodes.FORBIDDEN, PosAccess.ONLY_OWN_SALE);
        }

        stockService.voidSale(new VoidSaleCommand(user.tenantId(), sale.getBatchRef(), user.id(),
                request.reason().strip()));

        sale.setStatus(PosSaleStatus.VOIDED);
        sale.setVoidedAt(Timestamps.now());
        sale.setVoidedBy(user.id());
        sale.setVoidReason(request.reason().strip());
        saleRepository.save(sale);

        PosSession locked = sessionService.lock(session.getId());
        sessionService.applySale(locked == null ? session : locked, sale.getTotal(), true);

        return get(user, sale.getId());
    }

    // ------------------------------------------------------------------ consultas

    @Transactional(readOnly = true)
    public PosSaleDto get(AuthUser user, Long saleId) {
        PosSale sale = require(user.tenantId(), saleId);
        branchAccess.assertAccess(sale.getBranchId());
        assertCanView(user, sale);
        return toDto(user, sale, saleItemRepository.findBySaleIdOrderByIdAsc(sale.getId()),
                paymentRepository.findBySaleIdOrderByIdAsc(sale.getId()));
    }

    /** Datos del comprobante de 80 mm (SPEC §15.2). */
    @Transactional(readOnly = true)
    public PosTicketDto ticket(AuthUser user, Long saleId) {
        PosSale sale = require(user.tenantId(), saleId);
        branchAccess.assertAccess(sale.getBranchId());
        assertCanView(user, sale);
        List<PosSaleItem> items = saleItemRepository.findBySaleIdOrderByIdAsc(sale.getId());
        List<PosPayment> payments = paymentRepository.findBySaleIdOrderByIdAsc(sale.getId());
        Tenant tenant = tenantRepository.findById(user.tenantId()).orElse(null);
        Branch branch = branchRepository.findByIdAndTenantId(sale.getBranchId(), user.tenantId()).orElse(null);
        PosRegister register = registerRepository.findById(sale.getRegisterId()).orElse(null);
        String cashier = PosDirectory.nameOf(directory.userNames(List.of(sale.getCashierId())), sale.getCashierId());

        List<PosTicketDto.TicketItem> ticketItems = new ArrayList<>(items.size());
        for (PosSaleItem item : items) {
            List<PosSaleLotDto> lots = readLots(item);
            PosSaleLotDto discounted = lots.stream()
                    .filter(lot -> lot.discountPct() != null && lot.discountPct().signum() > 0)
                    .findFirst().orElse(lots.isEmpty() ? null : lots.getFirst());
            BigDecimal unitPrice = PosMoney.perUnit(item.getLineTotal(), item.getQuantity());
            boolean discountedLine = PosMoney.orZero(item.getDiscountAmount()).signum() > 0;
            ticketItems.add(new PosTicketDto.TicketItem(item.getProductName(), item.getQuantity(), unitPrice,
                    discountedLine ? PosMoney.orZero(item.getListUnitPrice()) : null,
                    discounted == null ? null : discounted.lotNumber(),
                    discounted == null ? null : discounted.expiryDate(),
                    discounted == null ? null : discounted.discountPct(),
                    PosMoney.orZero(item.getLineTotal())));
        }
        List<PosTicketDto.TicketPayment> ticketPayments = payments.stream()
                .map(payment -> new PosTicketDto.TicketPayment(payment.getMethod(),
                        PaymentMethods.label(payment.getMethod()), PosMoney.orZero(payment.getAmount()),
                        payment.getReference()))
                .toList();

        String address = branch == null ? null : joinAddress(branch);
        return new PosTicketDto(sale.getId(), tenant == null ? "GondolIA" : tenant.getName(),
                tenant == null ? null : tenant.getTaxId(), branch == null ? null : branch.getName(), address,
                sale.getTicketCode(), sale.getCreatedAt(), register == null ? null : register.getName(), cashier,
                sale.getCustomerName(), sale.getCustomerDoc(), ticketItems, ticketPayments,
                PosMoney.orZero(sale.getChangeAmount()), PosMoney.orZero(sale.getSubtotal()),
                PosMoney.orZero(sale.getDiscountTotal()), PosMoney.orZero(sale.getTotal()), sale.getUnits(),
                sale.getStatus(), sale.getVoidedAt(), sale.getVoidReason(), LEGEND);
    }

    /** Historial de ventas del POS dentro del alcance de sucursales. */
    @Transactional(readOnly = true)
    public PageResponse<PosSaleSummaryDto> list(AuthUser user, Long sessionId, PosSaleStatus status, LocalDate from,
                                                LocalDate to, String query, boolean mine, int page, int size) {
        List<Long> branchIds = branchAccess.scopeBranchIds();
        if (branchIds.isEmpty()) {
            return PageResponse.empty(page, size);
        }
        Set<Long> visibleSessions = mine || !PosAccess.isAdmin(user) ? ownSessionIds(user) : null;
        if (visibleSessions != null && visibleSessions.isEmpty()) {
            return PageResponse.empty(page, size);
        }
        String text = query == null ? "" : query.strip();
        Specification<PosSale> spec = (root, criteria, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), user.tenantId()));
            predicates.add(root.get("branchId").in(branchIds));
            if (sessionId != null) {
                predicates.add(cb.equal(root.get("sessionId"), sessionId));
            }
            if (visibleSessions != null) {
                predicates.add(root.get("sessionId").in(visibleSessions));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), startOfDay(from)));
            }
            if (to != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), startOfDay(to.plusDays(1))));
            }
            if (!text.isEmpty()) {
                Predicate byTicket = cb.like(cb.lower(root.get("ticketCode")), "%" + text.toLowerCase() + "%");
                Predicate byCustomer = cb.like(cb.lower(cb.coalesce(root.get("customerName"), "")),
                        "%" + text.toLowerCase() + "%");
                predicates.add(cb.or(byTicket, byCustomer));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        Page<PosSale> sales = saleRepository.findAll(spec,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")));

        Map<Long, String> branchNames = directory.branchNames(user.tenantId());
        Map<Long, String> registerNames = new HashMap<>();
        registerRepository.findByTenantIdOrderByBranchIdAscNameAsc(user.tenantId())
                .forEach(register -> registerNames.put(register.getId(), register.getName()));
        Set<Long> userIds = new HashSet<>();
        sales.forEach(sale -> {
            userIds.add(sale.getCashierId());
            userIds.add(sale.getVoidedBy());
        });
        Map<Long, String> userNames = directory.userNames(userIds);
        List<Long> saleIds = sales.getContent().stream().map(PosSale::getId).toList();
        Map<Long, List<PaymentMethod>> methods = new HashMap<>();
        if (!saleIds.isEmpty()) {
            for (PosPayment payment : paymentRepository.findBySaleIdInOrderBySaleIdAscIdAsc(saleIds)) {
                methods.computeIfAbsent(payment.getSaleId(), key -> new ArrayList<>()).add(payment.getMethod());
            }
        }
        return PageResponse.of(sales, sale -> new PosSaleSummaryDto(sale.getId(), sale.getTicketCode(),
                sale.getBranchId(), branchNames.get(sale.getBranchId()), sale.getRegisterId(),
                registerNames.get(sale.getRegisterId()), sale.getSessionId(), sale.getStatus(),
                PosMoney.orZero(sale.getTotal()), sale.getItemsCount(), sale.getUnits(),
                PosDirectory.nameOf(userNames, sale.getCashierId()), sale.getCreatedAt(), sale.isHasShortage(),
                sale.getVoidedAt(), PosDirectory.nameOf(userNames, sale.getVoidedBy()), sale.getVoidReason(),
                methods.getOrDefault(sale.getId(), List.of())));
    }

    // ------------------------------------------------------------------ helpers

    private PosSale require(Long tenantId, Long saleId) {
        return saleRepository.findByIdAndTenantId(saleId, tenantId)
                .orElseThrow(() -> new NotFoundException(NOT_FOUND));
    }

    private void assertCanView(AuthUser user, PosSale sale) {
        if (PosAccess.isAdmin(user)) {
            return;
        }
        PosSession session = sessionRepository.findByIdAndTenantId(sale.getSessionId(), user.tenantId())
                .orElseThrow(() -> new NotFoundException(NOT_FOUND));
        if (!PosAccess.owns(user, session)) {
            throw new ForbiddenException(ErrorCodes.FORBIDDEN, "Solo podés ver las ventas de tus turnos.");
        }
    }

    private Set<Long> ownSessionIds(AuthUser user) {
        List<Long> branchIds = branchAccess.scopeBranchIds();
        if (branchIds.isEmpty()) {
            return Set.of();
        }
        return sessionRepository.findByTenantIdAndBranchIdInOrderByOpenedAtDesc(user.tenantId(), branchIds).stream()
                .filter(session -> Objects.equals(session.getOpenedBy(), user.id()))
                .map(PosSession::getId)
                .collect(Collectors.toSet());
    }

    /** Suma las cantidades del mismo producto y conserva el orden en el que llegaron. */
    private Map<Long, Integer> mergeItems(List<PosSaleRequest.Item> items) {
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        for (PosSaleRequest.Item item : items) {
            quantities.merge(item.productId(), item.quantity(), Integer::sum);
        }
        for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {
            if (entry.getValue() > 9999) {
                throw new BadRequestException(ErrorCodes.VALIDATION_ERROR,
                        "La cantidad de un producto no puede superar 9999 unidades.");
            }
        }
        return quantities;
    }

    private void assertNotRecalled(Set<Long> productIds, Map<Long, Product> products,
                                   Map<Long, PosStockQueries.BranchStock> stock) {
        for (Long productId : productIds) {
            PosStockQueries.BranchStock counts = stock.getOrDefault(productId, PosStockQueries.BranchStock.EMPTY);
            if (counts.quarantined() > 0) {
                throw new ConflictException(PosErrorCodes.PRODUCT_RECALLED, products.get(productId).getName()
                        + " está en cuarentena por un recall: no se puede vender. Retiralo de la góndola y avisá al "
                        + "encargado.");
            }
        }
    }

    private void assertEnoughStock(Map<Long, Integer> quantities, Map<Long, Product> products,
                                   Map<Long, PosStockQueries.BranchStock> stock) {
        List<PosInsufficientStockException.Detail> details = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {
            int available = stock.getOrDefault(entry.getKey(), PosStockQueries.BranchStock.EMPTY).sellable();
            if (available < entry.getValue()) {
                details.add(new PosInsufficientStockException.Detail(entry.getKey(),
                        products.get(entry.getKey()).getName(), entry.getValue(), available));
            }
        }
        if (!details.isEmpty()) {
            String message = details.size() == 1
                    ? "No hay stock suficiente de " + details.getFirst().productName() + ": quedan "
                            + details.getFirst().available() + " u. en esta sucursal."
                    : "No hay stock suficiente de " + details.size() + " productos en esta sucursal.";
            throw new PosInsufficientStockException(message, details);
        }
    }

    /** Pagos validados: cubren el total y el excedente sale del efectivo (SPEC §15.2). */
    private Payments validatePayments(PosSaleRequest request, BigDecimal total) {
        BigDecimal paid = PosMoney.ZERO;
        BigDecimal cash = PosMoney.ZERO;
        for (PosSaleRequest.Payment payment : request.payments()) {
            BigDecimal amount = PosMoney.scale(payment.amount());
            paid = paid.add(amount);
            if (payment.method() == PaymentMethod.CASH) {
                cash = cash.add(amount);
            }
        }
        paid = PosMoney.scale(paid);
        if (paid.compareTo(total) < 0) {
            throw new BadRequestException(ErrorCodes.PAYMENT_INSUFFICIENT,
                    "Los pagos no cubren el total: faltan $ " + PosMoney.scale(total.subtract(paid)).toPlainString()
                            + ".");
        }
        BigDecimal excess = PosMoney.scale(paid.subtract(total));
        if (excess.compareTo(cash) > 0) {
            throw new BadRequestException(PosErrorCodes.CHANGE_NOT_ALLOWED,
                    "El vuelto solo se da en efectivo: bajá el monto con tarjeta, transferencia o QR.");
        }
        return new Payments(request.payments(), paid, excess);
    }

    /**
     * Próximo número de ticket de la sucursal. La fila del contador se crea si falta (sin abortar la transacción si
     * otra caja la creó primero) y después se bloquea con {@code SELECT ... FOR UPDATE}.
     */
    private long nextTicketNumber(Long branchId) {
        jdbc.update("insert into pos_branch_counters (branch_id, last_number) values (?, 0) "
                + "on conflict (branch_id) do nothing", branchId);
        PosBranchCounter counter = counterRepository.lockByBranchId(branchId)
                .orElseThrow(() -> new IllegalStateException("No se pudo bloquear el contador de la sucursal"));
        long number = counter.next();
        counterRepository.save(counter);
        return number;
    }

    private Map<Long, Lot> lotsOf(Long tenantId, List<Line> lines) {
        Set<Long> lotIds = new HashSet<>();
        for (Line line : lines) {
            for (StockMovement movement : line.result().movements()) {
                if (movement.getLotId() != null) {
                    lotIds.add(movement.getLotId());
                }
            }
        }
        if (lotIds.isEmpty()) {
            return Map.of();
        }
        return lotRepository.findByTenantIdAndIdIn(tenantId, lotIds).stream()
                .collect(Collectors.toMap(Lot::getId, lot -> lot));
    }

    private PosSaleItem buildItem(Long saleId, Line line, Map<Long, Lot> lots) {
        PosSaleItem item = new PosSaleItem();
        item.setSaleId(saleId);
        item.setProductId(line.product().getId());
        item.setBarcode(line.product().getBarcode());
        item.setProductName(line.product().getName());
        item.setQuantity(line.quantity());
        BigDecimal listPrice = PosMoney.orZero(line.product().getSalePrice());
        item.setListUnitPrice(listPrice);
        BigDecimal lineTotal = PosMoney.orZero(line.result().totalAmount());
        item.setLineTotal(lineTotal);
        item.setDiscountAmount(PosMoney.scale(listPrice.multiply(BigDecimal.valueOf(line.quantity()))
                .subtract(lineTotal)));
        item.setShortageQuantity(line.result().shortageQuantity());

        ArrayNode array = objectMapper.createArrayNode();
        for (StockMovement movement : line.result().movements()) {
            if (movement.getLotId() == null) {
                continue;
            }
            Lot lot = lots.get(movement.getLotId());
            ObjectNode node = array.addObject();
            node.put("lotId", movement.getLotId());
            node.put("lotNumber", lot == null ? null : lot.getLotNumber());
            node.put("expiryDate", lot == null || lot.getExpiryDate() == null ? null
                    : lot.getExpiryDate().toString());
            node.put("quantity", movement.getQuantity());
            node.put("unitPrice", PosMoney.orZero(movement.getUnitPrice()));
            node.put("discountPct", movement.getDiscountPct());
        }
        item.setLots(array);
        return item;
    }

    private List<PosSaleLotDto> readLots(PosSaleItem item) {
        JsonNode node = item.getLots();
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<PosSaleLotDto> lots = new ArrayList<>();
        for (JsonNode element : node) {
            JsonNode expiry = element.get("expiryDate");
            JsonNode discount = element.get("discountPct");
            JsonNode price = element.get("unitPrice");
            JsonNode lotNumber = element.get("lotNumber");
            lots.add(new PosSaleLotDto(element.path("lotId").isNumber() ? element.path("lotId").asLong() : null,
                    lotNumber == null || lotNumber.isNull() ? null : lotNumber.asText(),
                    expiry == null || expiry.isNull() ? null : LocalDate.parse(expiry.asText()),
                    element.path("quantity").asInt(),
                    price == null || price.isNull() ? PosMoney.ZERO : PosMoney.scale(price.decimalValue()),
                    discount == null || discount.isNull() ? null : discount.decimalValue()));
        }
        return lots;
    }

    private PosSaleDto toDto(AuthUser user, PosSale sale, List<PosSaleItem> items, List<PosPayment> payments) {
        Map<Long, String> branchNames = directory.branchNames(user.tenantId());
        PosRegister register = registerRepository.findById(sale.getRegisterId()).orElse(null);
        Set<Long> userIds = new HashSet<>();
        userIds.add(sale.getCashierId());
        userIds.add(sale.getVoidedBy());
        Map<Long, String> userNames = directory.userNames(userIds);

        List<PosSaleItemDto> itemDtos = items.stream()
                .map(item -> new PosSaleItemDto(item.getId(), item.getProductId(), item.getBarcode(),
                        item.getProductName(), item.getQuantity(), PosMoney.orZero(item.getListUnitPrice()),
                        PosMoney.perUnit(item.getLineTotal(), item.getQuantity()),
                        PosMoney.orZero(item.getDiscountAmount()), PosMoney.orZero(item.getLineTotal()),
                        item.getShortageQuantity(), readLots(item)))
                .toList();
        List<PosPaymentDto> paymentDtos = payments.stream()
                .map(payment -> new PosPaymentDto(payment.getId(), payment.getMethod(),
                        PaymentMethods.label(payment.getMethod()), PosMoney.orZero(payment.getAmount()),
                        payment.getReference()))
                .toList();

        return new PosSaleDto(sale.getId(), sale.getTicketCode(), sale.getNumber(), sale.getBranchId(),
                branchNames.get(sale.getBranchId()), sale.getRegisterId(),
                register == null ? null : register.getName(), sale.getSessionId(), sale.getStatus(),
                PosMoney.orZero(sale.getSubtotal()), PosMoney.orZero(sale.getDiscountTotal()),
                PosMoney.orZero(sale.getTotal()), sale.getItemsCount(), sale.getUnits(),
                PosMoney.orZero(sale.getPaidTotal()), PosMoney.orZero(sale.getChangeAmount()),
                sale.getCustomerName(), sale.getCustomerDoc(), sale.getCashierId(),
                PosDirectory.nameOf(userNames, sale.getCashierId()), sale.getBatchRef(), sale.isHasShortage(),
                sale.getCreatedAt(), sale.getVoidedAt(), PosDirectory.nameOf(userNames, sale.getVoidedBy()),
                sale.getVoidReason(), itemDtos, paymentDtos);
    }

    private static String joinAddress(Branch branch) {
        List<String> parts = new ArrayList<>();
        if (branch.getAddress() != null && !branch.getAddress().isBlank()) {
            parts.add(branch.getAddress().strip());
        }
        if (branch.getCity() != null && !branch.getCity().isBlank()) {
            parts.add(branch.getCity().strip());
        }
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private Instant startOfDay(LocalDate date) {
        return date.atStartOfDay(clock.getZone()).toInstant();
    }

    private record Line(Product product, int quantity, SaleResult result) {
    }

    private record Payments(List<PosSaleRequest.Payment> lines, BigDecimal paidTotal, BigDecimal change) {
    }
}
