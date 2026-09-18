package com.gondolia.catalog;

import com.gondolia.catalog.dto.LotDto;
import com.gondolia.catalog.dto.LotRequest;
import com.gondolia.catalog.dto.LotUpdateRequest;
import com.gondolia.catalog.dto.ReceiveLotResponse;
import com.gondolia.common.error.BadRequestException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.common.error.NotFoundException;
import com.gondolia.domain.inventory.Lot;
import com.gondolia.domain.inventory.LotRepository;
import com.gondolia.domain.inventory.LotStatus;
import com.gondolia.domain.inventory.MovementSource;
import com.gondolia.domain.inventory.Product;
import com.gondolia.domain.inventory.SupplierRepository;
import com.gondolia.recall.RecallMatchingService;
import com.gondolia.security.BranchAccessService;
import com.gondolia.security.CurrentUser;
import com.gondolia.stock.StockService;
import com.gondolia.stock.StockService.ReceiveLotCommand;
import com.gondolia.stock.StockService.ReceiveLotResult;
import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Carga de mercadería y corrección de lotes (SPEC §6.3). La escritura siempre resuelve <b>una</b> sucursal con
 * {@link BranchAccessService#requireSingleBranch(Long)} y delega el trabajo en {@code StockService.receiveLot}: cada
 * ingreso crea un lote nuevo, se chequea contra los recalls publicados y, con FIFO, avisa si el lote nuevo vence
 * antes que mercadería que ya estaba (SPEC §4.2).
 */
@Service
@RequiredArgsConstructor
public class LotService {

    static final String MSG_LOT_NOT_FOUND = "El lote no existe.";
    static final String MSG_SUPPLIER_NOT_FOUND = "El proveedor elegido no existe.";
    static final String MSG_INACTIVE_PRODUCT =
            "El producto está dado de baja: activalo antes de cargar mercadería.";
    static final String MSG_EXPIRY_TOO_OLD = "El vencimiento no puede ser de hace más de 5 años.";
    static final String MSG_EXPIRY_TOO_FAR = "El vencimiento no puede ser de más de 20 años.";
    static final String MSG_LOT_NOT_EDITABLE =
            "Solo se pueden corregir lotes activos o en cuarentena.";

    /** Orígenes que puede declarar la pantalla de carga (SPEC §6.3). */
    private static final Set<MovementSource> ALLOWED_SOURCES =
            EnumSet.of(MovementSource.MANUAL, MovementSource.SCAN, MovementSource.OCR);

    private static final Set<LotStatus> EDITABLE_STATUSES = EnumSet.of(LotStatus.ACTIVE, LotStatus.RECALLED);

    private final LotRepository lotRepository;
    private final SupplierRepository supplierRepository;
    private final StockService stockService;
    private final RecallMatchingService recallMatchingService;
    private final BranchAccessService branchAccessService;
    private final ProductService productService;
    private final LotMapper lotMapper;
    private final Clock clock;

    /** Carga de mercadería: crea el lote, chequea recalls y devuelve el contexto para la pantalla. */
    @Transactional
    public ReceiveLotResponse receive(LotRequest request) {
        Long tenantId = CurrentUser.tenantId();
        Long branchId = branchAccessService.requireSingleBranch(request.branchId());
        Product product = productService.require(tenantId, request.productId());
        if (!product.isActive()) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR, MSG_INACTIVE_PRODUCT);
        }
        validateExpiry(request.expiryDate());
        validateSupplier(tenantId, request.supplierId());
        MovementSource source = request.source() == null ? MovementSource.MANUAL : request.source();
        if (!ALLOWED_SOURCES.contains(source)) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR,
                    "El origen de la carga tiene que ser MANUAL, SCAN u OCR.");
        }

        ReceiveLotResult result = stockService.receiveLot(new ReceiveLotCommand(tenantId, branchId,
                product.getId(), request.lotNumber(), request.expiryDate(), request.quantity(), request.costPrice(),
                request.supplierId(), null, source, CurrentUser.id(), null));

        Lot lot = result.lot();
        List<Lot> sellable = stockService.lotsInRotationOrder(tenantId, branchId, product.getId());
        LotMapper.Context context = lotMapper.context(tenantId, List.of(product.getId()));
        Map<Long, Integer> ranks = lotMapper.rotationRanks(sellable, context);

        List<LotDto> existing = sellable.stream()
                .filter(other -> !Objects.equals(other.getId(), lot.getId()))
                .map(other -> lotMapper.toDto(other, context, ranks.get(other.getId())))
                .toList();

        return new ReceiveLotResponse(
                lotMapper.toDto(lot, context, ranks.get(lot.getId())),
                lot.getStatus() == LotStatus.RECALLED,
                recallMatchingService.toRecallInfos(result.recallMatches()),
                result.rotationWarning(),
                existing);
    }

    /** Corrección del número de lote o del vencimiento; vuelve a chequear los recalls publicados. */
    @Transactional
    public LotDto update(Long lotId, LotUpdateRequest request) {
        Long tenantId = CurrentUser.tenantId();
        Lot lot = lotRepository.findByIdAndTenantId(lotId, tenantId)
                .orElseThrow(() -> new NotFoundException(MSG_LOT_NOT_FOUND));
        branchAccessService.assertAccess(lot.getBranchId());
        if (!EDITABLE_STATUSES.contains(lot.getStatus())) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR, MSG_LOT_NOT_EDITABLE);
        }
        validateExpiry(request.expiryDate());
        lot.assignLotNumber(request.lotNumber());
        lot.setExpiryDate(request.expiryDate());
        lotRepository.saveAndFlush(lot);

        recallMatchingService.checkLot(tenantId, lot.getId());
        Lot refreshed = lotRepository.findByIdAndTenantId(lotId, tenantId).orElse(lot);

        LotMapper.Context context = lotMapper.context(tenantId, List.of(refreshed.getProductId()));
        List<Lot> sellable = stockService.lotsInRotationOrder(tenantId, refreshed.getBranchId(),
                refreshed.getProductId());
        Map<Long, Integer> ranks = lotMapper.rotationRanks(sellable, context);
        return lotMapper.toDto(refreshed, context, ranks.get(refreshed.getId()));
    }

    /**
     * Lotes de un producto en el alcance de sucursales, por sucursal y en orden de rotación.
     * {@code includeEmpty} suma los lotes sin remanente (agotados, descartados).
     */
    @Transactional(readOnly = true)
    public List<LotDto> list(Long productId, boolean includeEmpty) {
        Long tenantId = CurrentUser.tenantId();
        Product product = productService.require(tenantId, productId);
        CatalogScope scope = productService.scope();
        if (scope.isEmpty()) {
            return List.of();
        }
        List<Lot> lots = lotRepository
                .findByTenantIdAndBranchIdInAndProductIdOrderByBranchIdAscReceivedAtAscIdAsc(
                        tenantId, scope.branchIds(), product.getId())
                .stream()
                .filter(lot -> includeEmpty || lot.getQuantity() > 0)
                .toList();
        LotMapper.Context context = lotMapper.context(tenantId, List.of(product.getId()));
        return lotMapper.sortForDisplay(lotMapper.toDtos(lots, context));
    }

    private void validateSupplier(Long tenantId, Long supplierId) {
        if (supplierId != null && supplierRepository.findByIdAndTenantId(supplierId, tenantId).isEmpty()) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR, MSG_SUPPLIER_NOT_FOUND);
        }
    }

    /** Evita fechas imposibles por un error de tipeo o una lectura mala del OCR. */
    private void validateExpiry(LocalDate expiryDate) {
        if (expiryDate == null) {
            return;
        }
        LocalDate today = LocalDate.now(clock);
        if (expiryDate.isBefore(today.minusYears(5))) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR, MSG_EXPIRY_TOO_OLD);
        }
        if (expiryDate.isAfter(today.plusYears(20))) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR, MSG_EXPIRY_TOO_FAR);
        }
    }
}
