package com.gondolia.catalog;

import com.gondolia.catalog.dto.LotDto;
import com.gondolia.domain.inventory.Lot;
import com.gondolia.domain.inventory.Product;
import com.gondolia.domain.inventory.ProductRepository;
import com.gondolia.domain.inventory.Supplier;
import com.gondolia.domain.inventory.SupplierRepository;
import com.gondolia.domain.tenant.StockRotation;
import com.gondolia.domain.tenant.TenantSettings;
import com.gondolia.domain.tenant.TenantSettingsRepository;
import com.gondolia.security.BranchAccessService;
import com.gondolia.stock.StockService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Arma los {@link LotDto} de una respuesta: nombres de sucursal, producto y proveedor, bucket de vencimiento y
 * {@code rotationRank}.
 * <p>
 * El {@code rotationRank} es la posición del lote dentro de los lotes <b>vendibles</b> de su mismo producto y
 * sucursal, en el orden en que se venderán (liquidación primero y después FIFO o FEFO, SPEC §4.2). Por eso la lista
 * que se mapea tiene que traer <b>todos</b> los lotes vendibles de cada par producto/sucursal que aparezca; los lotes
 * vencidos, agotados o en cuarentena reciben {@code null}.
 */
@Component
@RequiredArgsConstructor
public class LotMapper {

    private final ProductRepository productRepository;
    private final SupplierRepository supplierRepository;
    private final TenantSettingsRepository tenantSettingsRepository;
    private final BranchAccessService branchAccessService;
    private final Clock clock;

    /** Contexto compartido para no repetir consultas al mapear varios lotes. */
    public record Context(Map<Long, String> branchNames, Map<Long, String> productNames,
                          Map<Long, String> supplierNames, TenantSettings settings, StockRotation rotation,
                          LocalDate today) {
    }

    /**
     * Contexto para los lotes de los productos indicados ({@code productIds} null = todo el catálogo del comercio).
     */
    public Context context(Long tenantId, Collection<Long> productIds) {
        TenantSettings settings = tenantSettingsRepository.findById(tenantId)
                .orElseGet(() -> TenantSettings.defaultsFor(tenantId));
        List<Long> ids = productIds == null
                ? null
                : productIds.stream().filter(Objects::nonNull).distinct().toList();
        List<Product> products = ids == null
                ? productRepository.findByTenantId(tenantId)
                : ids.isEmpty() ? List.of() : productRepository.findByTenantIdAndIdIn(tenantId, ids);
        return new Context(
                branchAccessService.branchNames(tenantId),
                products.stream().collect(Collectors.toMap(Product::getId, Product::getName, (a, b) -> a)),
                supplierRepository.findByTenantIdOrderByNameAsc(tenantId).stream()
                        .collect(Collectors.toMap(Supplier::getId, Supplier::getName, (a, b) -> a)),
                settings, settings.getStockRotation(), LocalDate.now(clock));
    }

    /** Mapea los lotes calculando el {@code rotationRank} por sucursal y producto. */
    public List<LotDto> toDtos(List<Lot> lots, Context context) {
        Map<Long, Integer> ranks = rotationRanks(lots, context);
        return lots.stream().map(lot -> toDto(lot, context, ranks.get(lot.getId()))).toList();
    }

    /** Mapea un lote suelto con un rank ya calculado (o {@code null} si no es vendible). */
    public LotDto toDto(Lot lot, Context context, Integer rotationRank) {
        return new LotDto(
                lot.getId(),
                lot.getBranchId(),
                context.branchNames().get(lot.getBranchId()),
                lot.getProductId(),
                context.productNames().get(lot.getProductId()),
                lot.getLotNumber(),
                lot.getExpiryDate(),
                ExpiryBuckets.daysToExpiry(lot.getExpiryDate(), context.today()),
                lot.getInitialQuantity(),
                lot.getQuantity(),
                lot.getCostPrice(),
                lot.getReceivedAt(),
                lot.getStatus(),
                lot.getSource(),
                lot.getDiscountPct(),
                lot.getSupplierId(),
                lot.getSupplierId() == null ? null : context.supplierNames().get(lot.getSupplierId()),
                ExpiryBuckets.of(lot.getExpiryDate(), context.today(), context.settings()),
                lot.getOriginLotId(),
                rotationRank);
    }

    /** lotId → posición de venta (1 = se vende primero) dentro de su sucursal y producto. */
    public Map<Long, Integer> rotationRanks(List<Lot> lots, Context context) {
        Map<String, List<Lot>> sellableByGroup = new LinkedHashMap<>();
        for (Lot lot : lots) {
            if (lot.isSellableOn(context.today())) {
                sellableByGroup.computeIfAbsent(lot.getBranchId() + ":" + lot.getProductId(), key -> new ArrayList<>())
                        .add(lot);
            }
        }
        Comparator<Lot> rotation = StockService.rotationComparator(context.rotation());
        Map<Long, Integer> ranks = new HashMap<>();
        sellableByGroup.values().forEach(group -> {
            List<Lot> ordered = group.stream().sorted(rotation).toList();
            for (int i = 0; i < ordered.size(); i++) {
                ranks.put(ordered.get(i).getId(), i + 1);
            }
        });
        return ranks;
    }

    /**
     * Ordena los lotes para mostrarlos: por sucursal (nombre) y, dentro de cada una, en orden de rotación; los que ya
     * no se venden (vencidos, agotados o en cuarentena) van al final, del más nuevo al más viejo.
     */
    public List<LotDto> sortForDisplay(List<LotDto> lots) {
        Comparator<LotDto> byBranch = Comparator.comparing(
                dto -> dto.branchName() == null ? "" : dto.branchName(), String.CASE_INSENSITIVE_ORDER);
        Comparator<LotDto> byRank = Comparator.comparingInt(
                dto -> dto.rotationRank() == null ? Integer.MAX_VALUE : dto.rotationRank());
        Comparator<LotDto> byReceived = Comparator.comparing(LotDto::receivedAt,
                Comparator.nullsLast(Comparator.reverseOrder()));
        return lots.stream().sorted(byBranch.thenComparing(byRank).thenComparing(byReceived)).toList();
    }
}
