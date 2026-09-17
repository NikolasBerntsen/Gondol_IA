package com.gondolia.stock;

import com.gondolia.common.error.ApiException;
import com.gondolia.common.error.BadRequestException;
import com.gondolia.common.error.ConflictException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.common.error.ForbiddenException;
import com.gondolia.common.error.NotFoundException;
import com.gondolia.common.events.LotReceivedEvent;
import com.gondolia.common.events.StockChangedEvent;
import com.gondolia.common.util.LotNumbers;
import com.gondolia.domain.alert.Alert;
import com.gondolia.domain.alert.AlertType;
import com.gondolia.domain.alert.OpenAlertWriter;
import com.gondolia.domain.announcement.RecallMatch;
import com.gondolia.domain.common.Severity;
import com.gondolia.domain.inventory.Lot;
import com.gondolia.domain.inventory.LotRepository;
import com.gondolia.domain.inventory.LotStatus;
import com.gondolia.domain.inventory.MovementSource;
import com.gondolia.domain.inventory.MovementType;
import com.gondolia.domain.inventory.Product;
import com.gondolia.domain.inventory.ProductRepository;
import com.gondolia.domain.inventory.StockMovement;
import com.gondolia.domain.inventory.StockMovementRepository;
import com.gondolia.domain.inventory.SupplierRepository;
import com.gondolia.domain.tenant.Branch;
import com.gondolia.domain.tenant.BranchRepository;
import com.gondolia.domain.tenant.StockRotation;
import com.gondolia.domain.tenant.TenantSettings;
import com.gondolia.domain.tenant.TenantSettingsRepository;
import com.gondolia.recall.RecallMatchingService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Núcleo de stock por sucursal (SPEC §4.2 y §5.3). Todas las escrituras son transaccionales, validan que sucursal,
 * producto y lote pertenezcan al {@code tenantId} (no validan permisos del usuario: eso lo hace el controlador con
 * {@code BranchAccessService}) y publican {@link StockChangedEvent} por cada sucursal y producto afectados.
 * <ul>
 *   <li>Cada ingreso crea un lote nuevo, aunque repita número o vencimiento de otro.</li>
 *   <li>Las ventas consumen lotes vendibles ({@code ACTIVE}, con remanente, no vencidos) en el orden de rotación del
 *       comercio: <b>primero los lotes en liquidación</b> ({@code discount_pct} activo) y dentro de cada grupo FIFO
 *       {@code received_at, id} o FEFO {@code expiry_date NULLS LAST, received_at, id} (SPEC §4.2). Los lotes se
 *       bloquean con {@code SELECT ... FOR UPDATE} siempre en orden de id (ventas, anulaciones, ajustes,
 *       transferencias y recalls), así las operaciones concurrentes no se bloquean mutuamente.</li>
 *   <li>Anular una venta ({@link #voidSale}) crea un {@code SALE_VOID} por cada línea del batch y devuelve las
 *       unidades a sus lotes: toda métrica de ventas tiene que descontar esos movimientos.</li>
 *   <li>Un lote que llega a 0 queda {@code DEPLETED}; si fue por {@code WASTE_EXPIRED}, {@code EXPIRED_DISCARDED}; un
 *       lote {@code RECALLED} sigue {@code RECALLED}.</li>
 * </ul>
 * Precios: en ventas {@code unitPrice} es el precio cobrado por unidad (con el descuento del lote aplicado) y
 * {@code totalAmount} el importe; en el resto de los movimientos {@code unitPrice} es el costo unitario (del lote o, si
 * no tiene, del producto) y {@code totalAmount} el valor a costo.
 * <p>
 * Las validaciones lanzan {@link ApiException} <b>antes</b> de escribir nada, y esas excepciones no marcan como
 * rollback-only la transacción que llama: un proceso por lotes (importación CSV, POS) puede capturarlas por línea y
 * seguir. Mantener ese invariante al modificar esta clase.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockService {

    public static final String ROTATION_WARNING = "Este lote vence antes que mercadería que ingresó antes: con FIFO se "
            + "venderá después. Revisalo o aplicá un descuento.";

    // ------------------------------------------------------------------ comandos y resultados

    /** Ingreso de mercadería. {@code receivedAt} null = ahora; {@code source} null = MANUAL. */
    public record ReceiveLotCommand(Long tenantId, Long branchId, Long productId, String lotNumber,
                                    LocalDate expiryDate, int quantity, BigDecimal costPrice, Long supplierId,
                                    Instant receivedAt, MovementSource source, Long userId, String reason) {
    }

    /** {@code rotationWarning} null si no aplica; {@code recallMatches} vacía si el lote no está alcanzado. */
    public record ReceiveLotResult(Lot lot, StockMovement movement, List<RecallMatch> recallMatches,
                                   String rotationWarning) {
    }

    /**
     * Venta de un producto. {@code unitPrice} null = precio de venta del producto; {@code occurredAt} null = ahora;
     * {@code batchRef} null = se genera {@code S-...} (reusalo para agrupar las líneas de un mismo ticket).
     */
    public record SaleCommand(Long tenantId, Long branchId, Long productId, int quantity, BigDecimal unitPrice,
                              Instant occurredAt, MovementSource source, Long userId, String batchRef) {
    }

    /** {@code movements}: una fila por lote consumido más, si faltó stock, una fila con {@code lotId} null. */
    public record SaleResult(List<StockMovement> movements, int shortageQuantity, BigDecimal totalAmount) {
    }

    /** Ajuste sobre un lote: ADJUSTMENT_IN, ADJUSTMENT_OUT, WASTE_EXPIRED, WASTE_DAMAGED o RECALL_REMOVAL. */
    public record AdjustCommand(Long tenantId, Long lotId, MovementType type, int quantity, String reason,
                                MovementSource source, Long userId) {
    }

    public record TransferItem(Long lotId, int quantity) {
    }

    public record TransferCommand(Long tenantId, Long fromBranchId, Long toBranchId, List<TransferItem> items,
                                  String note, Long userId) {
    }

    /** {@code outMovements} y {@code destinationLots} siguen el orden de {@code items}. */
    public record TransferResult(String batchRef, List<StockMovement> outMovements, List<Lot> destinationLots,
                                 List<RecallMatch> recallMatches) {
    }

    /**
     * Anulación de una venta completa (SPEC §15.1). {@code batchRef} es el de la venta ({@code P-...} en el POS
     * GondolIA, {@code S-...} en una venta manual); {@code reason} queda en el motivo de cada movimiento.
     */
    public record VoidSaleCommand(Long tenantId, String batchRef, Long userId, String reason) {
    }

    /** Sucursal y producto afectados por una operación (para no repetir eventos). */
    private record BranchProduct(Long branchId, Long productId) {
    }

    // ------------------------------------------------------------------ mensajes

    static final String MSG_QUANTITY = "La cantidad tiene que ser mayor a cero";
    static final String MSG_BRANCH_NOT_FOUND = "La sucursal no existe";
    static final String MSG_BRANCH_INACTIVE = "La sucursal está desactivada";
    static final String MSG_PRODUCT_NOT_FOUND = "El producto no existe";
    static final String MSG_PRODUCT_INACTIVE = "El producto está dado de baja: reactivalo para cargarle mercadería";
    static final String MSG_SUPPLIER_NOT_FOUND = "El proveedor no existe";
    static final String MSG_LOT_NOT_FOUND = "El lote no existe";
    static final String MSG_NEGATIVE_COST = "El costo no puede ser negativo";
    static final String MSG_NEGATIVE_PRICE = "El precio no puede ser negativo";
    static final String MSG_ADJUSTMENT_TYPE = "Tipo de ajuste inválido: usá ADJUSTMENT_IN, ADJUSTMENT_OUT, "
            + "WASTE_EXPIRED, WASTE_DAMAGED o RECALL_REMOVAL";
    static final String MSG_SAME_BRANCH = "La sucursal de origen y la de destino tienen que ser distintas";
    static final String MSG_NO_ITEMS = "Indicá al menos un lote para transferir";
    static final String MSG_BATCH_REF_LENGTH = "La referencia de la operación no puede superar 40 caracteres";
    static final String MSG_BATCH_REF_REQUIRED = "Indicá la referencia de la venta que querés anular";
    static final String MSG_SALE_NOT_FOUND = "La venta no existe";
    static final String MSG_ALREADY_VOIDED = "La venta ya fue anulada";
    static final String MSG_LOT_NUMBER_LENGTH =
            "El número de lote no puede superar " + Lot.MAX_LOT_NUMBER_LENGTH + " caracteres";

    private static final Set<MovementType> ADJUSTMENT_TYPES = EnumSet.of(MovementType.ADJUSTMENT_IN,
            MovementType.ADJUSTMENT_OUT, MovementType.WASTE_EXPIRED, MovementType.WASTE_DAMAGED,
            MovementType.RECALL_REMOVAL);
    private static final int MAX_REASON_LENGTH = 300;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    /** "Primero lo que entró antes": {@code received_at, id}. */
    private static final Comparator<Lot> RECEIVED_ORDER =
            Comparator.comparing(Lot::getReceivedAt).thenComparing(Lot::getId);
    /** "Primero lo que vence antes": {@code expiry_date NULLS LAST, received_at, id}. */
    private static final Comparator<Lot> EXPIRY_ORDER =
            Comparator.comparing(Lot::getExpiryDate, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(RECEIVED_ORDER);
    /** SPEC §4.2: los lotes en liquidación ({@code discount_pct} activo) salen antes que el resto. */
    private static final Comparator<Lot> DISCOUNTED_FIRST =
            Comparator.comparingInt((Lot lot) -> activeDiscount(lot) != null ? 0 : 1);
    /** SPEC §4.2: liquidación primero y después FIFO {@code received_at, id}. */
    private static final Comparator<Lot> FIFO_ORDER = DISCOUNTED_FIRST.thenComparing(RECEIVED_ORDER);
    /** SPEC §4.2: liquidación primero y después FEFO {@code expiry_date NULLS LAST, received_at, id}. */
    private static final Comparator<Lot> FEFO_ORDER = DISCOUNTED_FIRST.thenComparing(EXPIRY_ORDER);

    private final LotRepository lotRepository;
    private final StockMovementRepository movementRepository;
    private final ProductRepository productRepository;
    private final BranchRepository branchRepository;
    private final SupplierRepository supplierRepository;
    private final TenantSettingsRepository tenantSettingsRepository;
    private final OpenAlertWriter openAlertWriter;
    private final RecallMatchingService recallMatchingService;
    private final ApplicationEventPublisher eventPublisher;
    private final EntityManager entityManager;
    private final Clock clock;

    // ------------------------------------------------------------------ ingreso

    /**
     * Crea SIEMPRE un lote nuevo con su movimiento {@code ENTRY} y lo chequea contra los recalls publicados (si coincide
     * queda {@code RECALLED}). Con FIFO informa {@code rotationWarning} si el lote vence antes que lotes vendibles que
     * ingresaron antes (o sin vencimiento) en la misma sucursal. Publica {@link LotReceivedEvent} y
     * {@link StockChangedEvent}.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public ReceiveLotResult receiveLot(ReceiveLotCommand cmd) {
        Objects.requireNonNull(cmd, "cmd");
        Long tenantId = requireTenant(cmd.tenantId());
        requirePositive(cmd.quantity());
        requireActiveBranch(tenantId, cmd.branchId());
        Product product = requireProduct(tenantId, cmd.productId());
        if (!product.isActive()) {
            throw new ConflictException(ErrorCodes.CONFLICT, MSG_PRODUCT_INACTIVE);
        }
        if (cmd.supplierId() != null && supplierRepository.findByIdAndTenantId(cmd.supplierId(), tenantId).isEmpty()) {
            throw new NotFoundException(MSG_SUPPLIER_NOT_FOUND);
        }
        if (cmd.costPrice() != null && cmd.costPrice().signum() < 0) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR, MSG_NEGATIVE_COST);
        }
        String lotNumber = LotNumbers.clean(cmd.lotNumber());
        if (lotNumber != null && lotNumber.length() > Lot.MAX_LOT_NUMBER_LENGTH) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR, MSG_LOT_NUMBER_LENGTH);
        }
        Instant receivedAt = cmd.receivedAt() != null ? cmd.receivedAt().truncatedTo(ChronoUnit.MICROS) : now();
        MovementSource source = cmd.source() != null ? cmd.source() : MovementSource.MANUAL;

        Lot lot = new Lot();
        lot.setTenantId(tenantId);
        lot.setBranchId(cmd.branchId());
        lot.setProductId(product.getId());
        lot.setSupplierId(cmd.supplierId());
        lot.assignLotNumber(lotNumber);
        lot.setExpiryDate(cmd.expiryDate());
        lot.setInitialQuantity(cmd.quantity());
        lot.setQuantity(cmd.quantity());
        lot.setCostPrice(cmd.costPrice() != null ? scaleMoney(cmd.costPrice()) : null);
        lot.setReceivedAt(receivedAt);
        lot.setStatus(LotStatus.ACTIVE);
        lot.setSource(source);
        lot.setCreatedBy(cmd.userId());
        lotRepository.saveAndFlush(lot);

        StockMovement movement = movement(lot, MovementType.ENTRY, cmd.quantity(), source, cmd.userId(), cmd.reason(),
                null, receivedAt);
        applyCost(movement, lot, product);
        movementRepository.saveAndFlush(movement);

        List<RecallMatch> recallMatches = recallMatchingService.checkLot(tenantId, lot.getId());
        String rotationWarning = rotationWarning(lot, receivedAt);

        eventPublisher.publishEvent(new LotReceivedEvent(tenantId, lot.getBranchId(), lot.getProductId(), lot.getId()));
        eventPublisher.publishEvent(new StockChangedEvent(tenantId, lot.getBranchId(), lot.getProductId()));
        return new ReceiveLotResult(lot, movement, recallMatches, rotationWarning);
    }

    // ------------------------------------------------------------------ ventas

    /**
     * Descuenta la venta de los lotes vendibles en el orden de rotación del comercio. El vencimiento de los lotes se
     * evalúa a la fecha de la venta ({@code occurredAt} en la zona de negocio): una venta histórica (CSV, datos demo)
     * puede consumir un lote que vencía después de esa fecha, pero nunca uno que ya estaba vencido. Los lotes en
     * liquidación salen primero (SPEC §4.2). Cada lote aplica su
     * {@code discount_pct}. Si el stock no alcanza, el faltante se registra como {@code SALE} con {@code lotId} null
     * y se abre (una vez por sucursal y producto) la alerta {@code SALE_WITHOUT_STOCK}.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public SaleResult registerSale(SaleCommand cmd) {
        Objects.requireNonNull(cmd, "cmd");
        Long tenantId = requireTenant(cmd.tenantId());
        requirePositive(cmd.quantity());
        Branch branch = requireActiveBranch(tenantId, cmd.branchId());
        Product product = requireProduct(tenantId, cmd.productId());
        BigDecimal basePrice = cmd.unitPrice() != null ? cmd.unitPrice() : product.getSalePrice();
        if (basePrice == null) {
            basePrice = BigDecimal.ZERO;
        }
        if (basePrice.signum() < 0) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR, MSG_NEGATIVE_PRICE);
        }
        String batchRef = batchRefOrNew(cmd.batchRef());
        Instant occurredAt = cmd.occurredAt() != null ? cmd.occurredAt().truncatedTo(ChronoUnit.MICROS) : now();
        LocalDate saleDate = LocalDate.ofInstant(occurredAt, clock.getZone());
        MovementSource source = cmd.source() != null ? cmd.source() : MovementSource.MANUAL;

        List<Lot> lots = lockSellableLots(tenantId, branch.getId(), product.getId(), saleDate, rotationFor(tenantId));
        List<StockMovement> movements = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        int remaining = cmd.quantity();
        for (Lot lot : lots) {
            if (remaining == 0) {
                break;
            }
            int taken = Math.min(lot.getQuantity(), remaining);
            lot.setQuantity(lot.getQuantity() - taken);
            if (lot.getQuantity() == 0) {
                lot.setStatus(LotStatus.DEPLETED);
            }
            BigDecimal discountPct = activeDiscount(lot);
            BigDecimal unitPrice = discountPct == null
                    ? scaleMoney(basePrice)
                    : scaleMoney(basePrice.multiply(HUNDRED.subtract(discountPct)).divide(HUNDRED, 4, RoundingMode.HALF_UP));
            StockMovement movement = movement(lot, MovementType.SALE, taken, source, cmd.userId(), null, batchRef,
                    occurredAt);
            movement.setUnitPrice(unitPrice);
            movement.setDiscountPct(discountPct);
            movement.setTotalAmount(scaleMoney(unitPrice.multiply(BigDecimal.valueOf(taken))));
            movements.add(movement);
            total = total.add(movement.getTotalAmount());
            remaining -= taken;
        }

        if (remaining > 0) {
            StockMovement shortage = new StockMovement();
            shortage.setTenantId(tenantId);
            shortage.setBranchId(branch.getId());
            shortage.setProductId(product.getId());
            shortage.setLotId(null);
            shortage.setType(MovementType.SALE);
            shortage.setQuantity(remaining);
            shortage.setUnitPrice(scaleMoney(basePrice));
            shortage.setTotalAmount(scaleMoney(basePrice.multiply(BigDecimal.valueOf(remaining))));
            shortage.setSource(source);
            shortage.setBatchRef(batchRef);
            shortage.setReason("Venta sin stock registrado");
            shortage.setUserId(cmd.userId());
            shortage.setOccurredAt(occurredAt);
            movements.add(shortage);
            total = total.add(shortage.getTotalAmount());
            openSaleWithoutStockAlert(tenantId, branch, product, remaining);
        }

        movementRepository.saveAll(movements);
        movementRepository.flush();
        eventPublisher.publishEvent(new StockChangedEvent(tenantId, branch.getId(), product.getId()));
        return new SaleResult(List.copyOf(movements), remaining, scaleMoney(total));
    }

    /**
     * Anula una venta completa (SPEC §15.1): por cada movimiento {@code SALE} del batch crea un {@code SALE_VOID} con
     * la misma cantidad, lote y precio, y devuelve las unidades a ese lote (un lote {@code DEPLETED} vuelve a
     * {@code ACTIVE}; uno {@code RECALLED}, {@code EXPIRED_DISCARDED} o vencido conserva su estado). Las líneas de
     * faltante ({@code lotId} null) también se anulan para que las ventas netas cierren, pero no devuelven stock.
     * <p>
     * Es idempotente por batch: si ya existe un {@code SALE_VOID}, 409 {@code ALREADY_VOIDED}. Los lotes se bloquean
     * en orden de id y se publica un {@link StockChangedEvent} por sucursal y producto afectados.
     *
     * @return los movimientos {@code SALE_VOID} creados, en el orden de las líneas originales
     */
    @Transactional(noRollbackFor = ApiException.class)
    public List<StockMovement> voidSale(VoidSaleCommand cmd) {
        Objects.requireNonNull(cmd, "cmd");
        Long tenantId = requireTenant(cmd.tenantId());
        String batchRef = cmd.batchRef() == null ? null : cmd.batchRef().strip();
        if (batchRef == null || batchRef.isBlank()) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR, MSG_BATCH_REF_REQUIRED);
        }
        List<StockMovement> batch = movementRepository.findByTenantIdAndBatchRefOrderByIdAsc(tenantId, batchRef);
        List<StockMovement> sales = batch.stream().filter(movement -> movement.getType() == MovementType.SALE).toList();
        if (sales.isEmpty()) {
            throw new NotFoundException(MSG_SALE_NOT_FOUND);
        }
        if (batch.stream().anyMatch(movement -> movement.getType() == MovementType.SALE_VOID)) {
            throw new ConflictException(ErrorCodes.ALREADY_VOIDED, MSG_ALREADY_VOIDED);
        }

        // Bloqueo en orden de id (igual que ventas, ajustes y transferencias) y recién después el chequeo definitivo
        // de idempotencia: dos anulaciones simultáneas del mismo ticket se serializan en los lotes.
        Map<Long, Lot> lots = new HashMap<>();
        sales.stream().map(StockMovement::getLotId).filter(Objects::nonNull).distinct().sorted()
                .forEach(lotId -> lots.put(lotId, lockLot(tenantId, lotId)));
        if (movementRepository.existsByTenantIdAndBatchRefAndType(tenantId, batchRef, MovementType.SALE_VOID)) {
            throw new ConflictException(ErrorCodes.ALREADY_VOIDED, MSG_ALREADY_VOIDED);
        }

        Instant occurredAt = now();
        String reason = abbreviateReason(cmd.reason());
        List<StockMovement> voids = new ArrayList<>();
        Set<BranchProduct> touched = new LinkedHashSet<>();
        for (StockMovement sale : sales) {
            Lot lot = sale.getLotId() == null ? null : lots.get(sale.getLotId());
            if (lot != null) {
                lot.setQuantity(lot.getQuantity() + sale.getQuantity());
                if (lot.getStatus() == LotStatus.DEPLETED) {
                    lot.setStatus(LotStatus.ACTIVE);
                }
            }
            StockMovement reversal = new StockMovement();
            reversal.setTenantId(tenantId);
            reversal.setBranchId(sale.getBranchId());
            reversal.setProductId(sale.getProductId());
            reversal.setLotId(sale.getLotId());
            reversal.setType(MovementType.SALE_VOID);
            reversal.setQuantity(sale.getQuantity());
            reversal.setUnitPrice(sale.getUnitPrice());
            reversal.setDiscountPct(sale.getDiscountPct());
            reversal.setTotalAmount(sale.getTotalAmount());
            reversal.setSource(sale.getSource());
            reversal.setBatchRef(batchRef);
            reversal.setReason(reason);
            reversal.setUserId(cmd.userId());
            reversal.setOccurredAt(occurredAt);
            voids.add(reversal);
            touched.add(new BranchProduct(sale.getBranchId(), sale.getProductId()));
        }
        movementRepository.saveAll(voids);
        movementRepository.flush();

        touched.forEach(key -> eventPublisher.publishEvent(
                new StockChangedEvent(tenantId, key.branchId(), key.productId())));
        log.info("Venta {} anulada: {} movimientos devueltos a stock", batchRef, voids.size());
        return List.copyOf(voids);
    }

    // ------------------------------------------------------------------ ajustes

    /**
     * Ajuste, merma o retiro por recall sobre un lote (la sucursal es la del lote). Una salida mayor al remanente da
     * 409 {@code INSUFFICIENT_STOCK}. {@code ADJUSTMENT_IN} sobre un lote agotado ({@code DEPLETED} o
     * {@code EXPIRED_DISCARDED}) lo vuelve a {@code ACTIVE}; sobre uno {@code RECALLED} lo deja en cuarentena.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public StockMovement adjust(AdjustCommand cmd) {
        Objects.requireNonNull(cmd, "cmd");
        Long tenantId = requireTenant(cmd.tenantId());
        if (cmd.type() == null || !ADJUSTMENT_TYPES.contains(cmd.type())) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR, MSG_ADJUSTMENT_TYPE);
        }
        requirePositive(cmd.quantity());
        if (cmd.lotId() == null) {
            throw new NotFoundException(MSG_LOT_NOT_FOUND);
        }
        Lot lot = lockLot(tenantId, cmd.lotId());
        requireActiveBranch(tenantId, lot.getBranchId());
        Product product = requireProduct(tenantId, lot.getProductId());
        MovementType type = cmd.type();

        if (type.isInbound()) {
            lot.setQuantity(lot.getQuantity() + cmd.quantity());
            if (lot.getStatus() == LotStatus.DEPLETED || lot.getStatus() == LotStatus.EXPIRED_DISCARDED) {
                lot.setStatus(LotStatus.ACTIVE);
            }
        } else {
            if (lot.getQuantity() < cmd.quantity()) {
                throw insufficientStock(lot, cmd.quantity());
            }
            lot.setQuantity(lot.getQuantity() - cmd.quantity());
            if (lot.getQuantity() == 0 && lot.getStatus() != LotStatus.RECALLED) {
                lot.setStatus(type == MovementType.WASTE_EXPIRED ? LotStatus.EXPIRED_DISCARDED : LotStatus.DEPLETED);
            }
        }

        MovementSource source = cmd.source() != null ? cmd.source() : MovementSource.MANUAL;
        StockMovement movement = movement(lot, type, cmd.quantity(), source, cmd.userId(), cmd.reason(),
                BatchRefs.adjustment(clock), now());
        applyCost(movement, lot, product);
        movementRepository.saveAndFlush(movement);
        eventPublisher.publishEvent(new StockChangedEvent(tenantId, lot.getBranchId(), lot.getProductId()));
        return movement;
    }

    // ------------------------------------------------------------------ transferencias

    /**
     * Transfiere lotes entre dos sucursales activas del tenant. Por cada ítem descuenta del lote origen
     * ({@code TRANSFER_OUT}) y crea en destino un lote nuevo con el mismo número, vencimiento, costo, proveedor y
     * {@code received_at} original, {@code origin_lot_id} = lote origen ({@code TRANSFER_IN}); todos los movimientos
     * comparten el {@code batchRef} {@code T-...}. Lotes {@code RECALLED} o vencidos: 409
     * {@code LOT_NOT_TRANSFERABLE}. Cada lote destino pasa por el chequeo de recall.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public TransferResult transfer(TransferCommand cmd) {
        Objects.requireNonNull(cmd, "cmd");
        Long tenantId = requireTenant(cmd.tenantId());
        if (cmd.fromBranchId() == null || cmd.toBranchId() == null) {
            throw new BadRequestException(ErrorCodes.BRANCH_REQUIRED, "Elegí la sucursal de origen y la de destino");
        }
        if (cmd.fromBranchId().equals(cmd.toBranchId())) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR, MSG_SAME_BRANCH);
        }
        requireActiveBranch(tenantId, cmd.fromBranchId());
        requireActiveBranch(tenantId, cmd.toBranchId());
        List<TransferItem> items = cmd.items() == null ? List.of() : cmd.items();
        if (items.isEmpty()) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR, MSG_NO_ITEMS);
        }
        Set<Long> seen = new HashSet<>();
        for (TransferItem item : items) {
            if (item == null || item.lotId() == null) {
                throw new BadRequestException(ErrorCodes.VALIDATION_ERROR, "Cada ítem tiene que indicar el lote");
            }
            requirePositive(item.quantity());
            if (!seen.add(item.lotId())) {
                throw new BadRequestException(ErrorCodes.VALIDATION_ERROR,
                        "El lote " + item.lotId() + " está repetido en la transferencia");
            }
        }

        LocalDate today = LocalDate.now(clock);
        Instant occurredAt = now();
        String batchRef = BatchRefs.transfer(clock);
        String reason = cmd.note();

        // Bloqueo en orden de id para evitar deadlocks entre transferencias concurrentes.
        Map<Long, Lot> origins = new HashMap<>();
        items.stream().map(TransferItem::lotId).sorted().forEach(lotId -> origins.put(lotId, lockLot(tenantId, lotId)));
        Map<Long, Product> products = productRepository.findByTenantIdAndIdIn(tenantId,
                        origins.values().stream().map(Lot::getProductId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        for (TransferItem item : items) {
            validateTransferable(origins.get(item.lotId()), cmd.fromBranchId(), item.quantity(), today);
        }

        List<StockMovement> outMovements = new ArrayList<>();
        List<Lot> destinationLots = new ArrayList<>();
        for (TransferItem item : items) {
            Lot origin = origins.get(item.lotId());
            Product product = products.get(origin.getProductId());

            origin.setQuantity(origin.getQuantity() - item.quantity());
            if (origin.getQuantity() == 0) {
                origin.setStatus(LotStatus.DEPLETED);
            }
            StockMovement out = movement(origin, MovementType.TRANSFER_OUT, item.quantity(), MovementSource.MANUAL,
                    cmd.userId(), reason, batchRef, occurredAt);
            applyCost(out, origin, product);
            outMovements.add(out);

            Lot destination = new Lot();
            destination.setTenantId(tenantId);
            destination.setBranchId(cmd.toBranchId());
            destination.setProductId(origin.getProductId());
            destination.setOriginLotId(origin.getId());
            destination.setSupplierId(origin.getSupplierId());
            destination.setLotNumber(origin.getLotNumber());
            destination.setLotNumberNormalized(origin.getLotNumberNormalized());
            destination.setExpiryDate(origin.getExpiryDate());
            destination.setInitialQuantity(item.quantity());
            destination.setQuantity(item.quantity());
            destination.setCostPrice(origin.getCostPrice());
            destination.setReceivedAt(origin.getReceivedAt());
            destination.setStatus(LotStatus.ACTIVE);
            destination.setSource(MovementSource.MANUAL);
            destination.setCreatedBy(cmd.userId());
            lotRepository.saveAndFlush(destination);
            destinationLots.add(destination);

            StockMovement in = movement(destination, MovementType.TRANSFER_IN, item.quantity(), MovementSource.MANUAL,
                    cmd.userId(), reason, batchRef, occurredAt);
            applyCost(in, destination, product);
            movementRepository.save(out);
            movementRepository.save(in);
        }
        movementRepository.flush();

        List<RecallMatch> recallMatches = new ArrayList<>();
        for (Lot destination : destinationLots) {
            recallMatches.addAll(recallMatchingService.checkLot(tenantId, destination.getId()));
        }

        Set<Long> productIds = new LinkedHashSet<>();
        destinationLots.forEach(lot -> {
            productIds.add(lot.getProductId());
            eventPublisher.publishEvent(new LotReceivedEvent(tenantId, lot.getBranchId(), lot.getProductId(),
                    lot.getId()));
        });
        productIds.forEach(productId -> {
            eventPublisher.publishEvent(new StockChangedEvent(tenantId, cmd.fromBranchId(), productId));
            eventPublisher.publishEvent(new StockChangedEvent(tenantId, cmd.toBranchId(), productId));
        });
        log.debug("Transferencia {}: {} lotes de la sucursal {} a la {}", batchRef, items.size(), cmd.fromBranchId(),
                cmd.toBranchId());
        return new TransferResult(batchRef, List.copyOf(outMovements), List.copyOf(destinationLots),
                List.copyOf(recallMatches));
    }

    // ------------------------------------------------------------------ lecturas

    /**
     * Lotes vendibles de un producto en una sucursal, en el orden en que se venderán: primero los que están en
     * liquidación y después, dentro de cada grupo, FIFO o FEFO según el comercio (SPEC §4.2). El primero es el que la
     * UI marca como "Se vende primero" (o "En liquidación · sale primero").
     */
    @Transactional(readOnly = true)
    public List<Lot> lotsInRotationOrder(Long tenantId, Long branchId, Long productId) {
        LocalDate today = LocalDate.now(clock);
        return rotationFor(tenantId) == StockRotation.FEFO
                ? lotRepository.findSellableFefo(tenantId, branchId, productId, today)
                : lotRepository.findSellableFifo(tenantId, branchId, productId, today);
    }

    /** Stock vendible de un producto en una sucursal. */
    @Transactional(readOnly = true)
    public int sellableStock(Long tenantId, Long branchId, Long productId) {
        return Math.toIntExact(lotRepository.sumSellableQuantity(tenantId, branchId, productId, LocalDate.now(clock)));
    }

    /** productId → stock vendible sumado sobre las sucursales indicadas (solo productos con stock). */
    @Transactional(readOnly = true)
    public Map<Long, Integer> sellableStockByProduct(Long tenantId, Collection<Long> branchIds) {
        if (tenantId == null || branchIds == null || branchIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> result = new HashMap<>();
        lotRepository.sumSellableQuantityByProduct(tenantId, branchIds, LocalDate.now(clock))
                .forEach(row -> result.put(row.getProductId(), Math.toIntExact(row.getQuantity())));
        return result;
    }

    /** branchId → (productId → stock vendible), solo combinaciones con stock. */
    @Transactional(readOnly = true)
    public Map<Long, Map<Long, Integer>> sellableStockByBranchAndProduct(Long tenantId, Collection<Long> branchIds) {
        if (tenantId == null || branchIds == null || branchIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Map<Long, Integer>> result = new HashMap<>();
        lotRepository.sumSellableQuantityByBranchAndProduct(tenantId, branchIds, LocalDate.now(clock))
                .forEach(row -> result.computeIfAbsent(row.getBranchId(), id -> new HashMap<>())
                        .put(row.getProductId(), Math.toIntExact(row.getQuantity())));
        return result;
    }

    /**
     * Comparador del orden de rotación (SPEC §4.2) para ordenar en memoria lotes vendibles ya cargados: liquidación
     * primero y después FIFO o FEFO. Lo usan la IA (simulación de consumo) y el catálogo ({@code rotationRank}).
     */
    public static Comparator<Lot> rotationComparator(StockRotation rotation) {
        return rotation == StockRotation.FEFO ? FEFO_ORDER : FIFO_ORDER;
    }

    /** Rotación configurada del comercio (FIFO si no tiene configuración). */
    @Transactional(readOnly = true)
    public StockRotation rotationFor(Long tenantId) {
        if (tenantId == null) {
            return StockRotation.FIFO;
        }
        return tenantSettingsRepository.findById(tenantId)
                .map(TenantSettings::getStockRotation)
                .orElse(StockRotation.FIFO);
    }

    // ------------------------------------------------------------------ internos

    private String rotationWarning(Lot lot, Instant receivedAt) {
        if (lot.getStatus() != LotStatus.ACTIVE || lot.getExpiryDate() == null
                || rotationFor(lot.getTenantId()) != StockRotation.FIFO) {
            return null;
        }
        LocalDate receivedDate = LocalDate.ofInstant(receivedAt, clock.getZone());
        if (lot.isExpiredOn(receivedDate)) {
            return null;
        }
        long olderExpiringLater = lotRepository.countOlderSellableExpiringAfter(lot.getTenantId(), lot.getBranchId(),
                lot.getProductId(), lot.getId(), lot.getReceivedAt(), lot.getExpiryDate(), receivedDate);
        return olderExpiringLater > 0 ? ROTATION_WARNING : null;
    }

    /** Bloquea los lotes vendibles (en orden de id) y los devuelve en el orden de rotación del comercio. */
    private List<Lot> lockSellableLots(Long tenantId, Long branchId, Long productId, LocalDate date,
                                       StockRotation rotation) {
        List<Lot> lots = lotRepository.findSellableForUpdate(tenantId, branchId, productId, date);
        // La consulta con bloqueo no recarga lotes que ya estaban en el contexto de persistencia.
        lots.forEach(entityManager::refresh);
        return lots.stream()
                .filter(lot -> lot.isSellableOn(date))
                .sorted(rotationComparator(rotation))
                .toList();
    }

    private Lot lockLot(Long tenantId, Long lotId) {
        Lot lot = lotRepository.findByIdAndTenantIdForUpdate(lotId, tenantId)
                .orElseThrow(() -> new NotFoundException(MSG_LOT_NOT_FOUND));
        entityManager.refresh(lot);
        return lot;
    }

    private void validateTransferable(Lot lot, Long fromBranchId, int quantity, LocalDate today) {
        if (!lot.getBranchId().equals(fromBranchId)) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR,
                    "El lote " + label(lot) + " no pertenece a la sucursal de origen");
        }
        if (lot.getStatus() == LotStatus.RECALLED) {
            throw new ConflictException(ErrorCodes.LOT_NOT_TRANSFERABLE,
                    "El lote " + label(lot) + " está en cuarentena por un recall y no se puede transferir");
        }
        if (lot.isExpiredOn(today)) {
            throw new ConflictException(ErrorCodes.LOT_NOT_TRANSFERABLE,
                    "El lote " + label(lot) + " está vencido y no se puede transferir");
        }
        if (lot.getStatus() != LotStatus.ACTIVE || lot.getQuantity() < quantity) {
            throw insufficientStock(lot, quantity);
        }
    }

    private void openSaleWithoutStockAlert(Long tenantId, Branch branch, Product product, int missing) {
        Alert alert = new Alert();
        alert.setTenantId(tenantId);
        alert.setBranchId(branch.getId());
        alert.setType(AlertType.SALE_WITHOUT_STOCK);
        alert.setSeverity(Severity.WARNING);
        alert.setProductId(product.getId());
        alert.setTitle("Venta sin stock: " + product.getName());
        alert.setMessage("Se vendieron " + missing + " u. de " + product.getName() + " en " + branch.getName()
                + " sin stock suficiente en el sistema. Revisá el inventario y cargá la mercadería que falta.");
        alert.setDedupeKey("SALE_WITHOUT_STOCK:" + branch.getId() + ":" + product.getId());
        openAlertWriter.openIfAbsent(alert);
    }

    private StockMovement movement(Lot lot, MovementType type, int quantity, MovementSource source, Long userId,
                                   String reason, String batchRef, Instant occurredAt) {
        StockMovement movement = new StockMovement();
        movement.setTenantId(lot.getTenantId());
        movement.setBranchId(lot.getBranchId());
        movement.setProductId(lot.getProductId());
        movement.setLotId(lot.getId());
        movement.setType(type);
        movement.setQuantity(quantity);
        movement.setSource(source);
        movement.setBatchRef(batchRef);
        movement.setReason(abbreviateReason(reason));
        movement.setUserId(userId);
        movement.setOccurredAt(occurredAt);
        return movement;
    }

    private static void applyCost(StockMovement movement, Lot lot, Product product) {
        BigDecimal unitCost = lot.getCostPrice() != null ? lot.getCostPrice()
                : product != null && product.getCostPrice() != null ? product.getCostPrice() : BigDecimal.ZERO;
        movement.setUnitPrice(scaleMoney(unitCost));
        movement.setTotalAmount(scaleMoney(unitCost.multiply(BigDecimal.valueOf(movement.getQuantity()))));
    }

    private Branch requireActiveBranch(Long tenantId, Long branchId) {
        if (branchId == null) {
            throw new BadRequestException(ErrorCodes.BRANCH_REQUIRED, "Elegí una sucursal para esta operación");
        }
        Branch branch = branchRepository.findByIdAndTenantId(branchId, tenantId)
                .orElseThrow(() -> new NotFoundException(MSG_BRANCH_NOT_FOUND));
        if (!branch.isActive()) {
            throw new ForbiddenException(ErrorCodes.BRANCH_FORBIDDEN, MSG_BRANCH_INACTIVE);
        }
        return branch;
    }

    private Product requireProduct(Long tenantId, Long productId) {
        if (productId == null) {
            throw new NotFoundException(MSG_PRODUCT_NOT_FOUND);
        }
        return productRepository.findByIdAndTenantId(productId, tenantId)
                .orElseThrow(() -> new NotFoundException(MSG_PRODUCT_NOT_FOUND));
    }

    private String batchRefOrNew(String batchRef) {
        if (batchRef == null || batchRef.isBlank()) {
            return BatchRefs.sale(clock);
        }
        String value = batchRef.strip();
        if (value.length() > BatchRefs.MAX_LENGTH) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR, MSG_BATCH_REF_LENGTH);
        }
        return value;
    }

    private static Long requireTenant(Long tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId es obligatorio");
        }
        return tenantId;
    }

    private static void requirePositive(int quantity) {
        if (quantity <= 0) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR, MSG_QUANTITY);
        }
    }

    private static ConflictException insufficientStock(Lot lot, int requested) {
        return new ConflictException(ErrorCodes.INSUFFICIENT_STOCK, "Stock insuficiente en el lote " + label(lot)
                + ": quedan " + lot.getQuantity() + " u. y se quieren descontar " + requested + " u.");
    }

    /** Descuento vigente del lote (0 &lt; pct ≤ 100) o null. */
    private static BigDecimal activeDiscount(Lot lot) {
        BigDecimal pct = lot.getDiscountPct();
        if (pct == null || pct.signum() <= 0) {
            return null;
        }
        return pct.min(HUNDRED);
    }

    private static String label(Lot lot) {
        return lot.getLotNumber() != null ? lot.getLotNumber() : "#" + lot.getId();
    }

    private static String abbreviateReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String value = reason.strip();
        return value.length() <= MAX_REASON_LENGTH ? value : value.substring(0, MAX_REASON_LENGTH - 1) + "…";
    }

    private static BigDecimal scaleMoney(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
