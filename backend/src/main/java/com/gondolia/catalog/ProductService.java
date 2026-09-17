package com.gondolia.catalog;

import com.gondolia.catalog.CatalogStockReader.ProductStock;
import com.gondolia.catalog.dto.BranchStockDto;
import com.gondolia.catalog.dto.LotDto;
import com.gondolia.catalog.dto.ProductDetail;
import com.gondolia.catalog.dto.ProductListItem;
import com.gondolia.catalog.dto.ProductRequest;
import com.gondolia.common.PageResponse;
import com.gondolia.common.error.BadRequestException;
import com.gondolia.common.error.ConflictException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.common.error.NotFoundException;
import com.gondolia.common.util.Barcodes;
import com.gondolia.domain.inventory.Category;
import com.gondolia.domain.inventory.CategoryRepository;
import com.gondolia.domain.inventory.Lot;
import com.gondolia.domain.inventory.LotRepository;
import com.gondolia.domain.inventory.Product;
import com.gondolia.domain.inventory.ProductRepository;
import com.gondolia.domain.inventory.ProductUnit;
import com.gondolia.domain.inventory.StockMovementRepository;
import com.gondolia.domain.inventory.Supplier;
import com.gondolia.domain.inventory.SupplierRepository;
import com.gondolia.domain.tenant.TenantSettings;
import com.gondolia.domain.tenant.TenantSettingsRepository;
import com.gondolia.security.BranchAccessService;
import com.gondolia.security.BranchAccessService.BranchRef;
import com.gondolia.security.CurrentUser;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Catálogo de productos (SPEC §6.3). El catálogo es <b>del comercio</b> (lo ven todos sus usuarios de inventario),
 * pero el stock que se informa en cada fila es el del <b>alcance de sucursales</b> del request (SPEC §3.5): se suma
 * sobre las sucursales accesibles y el estado consolidado es el peor de todas.
 * <p>
 * El filtro por estado de stock y el orden por columnas calculadas se resuelven en memoria: el catálogo de un
 * comercio chico entra holgadamente y así el estado consolidado coincide siempre con el que se muestra.
 */
@Service
@RequiredArgsConstructor
public class ProductService {

    static final String MSG_NOT_FOUND = "El producto no existe.";
    static final String MSG_DUPLICATE_BARCODE = "Ya tenés un producto con ese código de barras.";
    static final String MSG_INVALID_BARCODE =
            "El código de barras solo puede tener letras y números (hasta 32 caracteres).";
    static final String MSG_CATEGORY_NOT_FOUND = "La categoría elegida no existe.";
    static final String MSG_SUPPLIER_NOT_FOUND = "El proveedor elegido no existe.";
    static final String DUPLICATE_BARCODE_CODE = "DUPLICATE_BARCODE";

    /** Ventana de lotes agotados que igual se muestran en la ficha del producto. */
    private static final int RECENT_EMPTY_LOT_DAYS = 30;

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final SupplierRepository supplierRepository;
    private final LotRepository lotRepository;
    private final StockMovementRepository movementRepository;
    private final TenantSettingsRepository tenantSettingsRepository;
    private final BranchAccessService branchAccessService;
    private final CatalogStockReader stockReader;
    private final CategoryService categoryService;
    private final LotMapper lotMapper;
    private final Clock clock;

    // ------------------------------------------------------------------ lecturas

    @Transactional(readOnly = true)
    public PageResponse<ProductListItem> list(ProductQuery query) {
        Long tenantId = CurrentUser.tenantId();
        CatalogScope scope = scope();
        List<Product> products = productRepository.findAll(filter(tenantId, query));
        Map<Long, String> categories = categoryNames(tenantId);
        Map<Long, ProductStock> stocks = stockReader.statsByProduct(tenantId, scope.branchIds(),
                products.stream().map(Product::getId).toList());
        TenantSettings settings = settings(tenantId);
        LocalDate today = LocalDate.now(clock);

        List<ProductListItem> rows = products.stream()
                .map(product -> toListItem(product, categories, stocks, scope))
                .filter(row -> matchesStockFilter(row, query, settings, today))
                .sorted(comparator(query))
                .toList();

        int from = Math.min(query.page() * query.size(), rows.size());
        int to = Math.min(from + query.size(), rows.size());
        return PageResponse.of(rows.subList(from, to), query.page(), query.size(), rows.size());
    }

    @Transactional(readOnly = true)
    public ProductDetail get(Long id) {
        Long tenantId = CurrentUser.tenantId();
        Product product = productRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NotFoundException(MSG_NOT_FOUND));
        return detail(product);
    }

    @Transactional(readOnly = true)
    public ProductDetail getByBarcode(String rawBarcode) {
        Long tenantId = CurrentUser.tenantId();
        String barcode = Barcodes.normalize(rawBarcode);
        if (barcode == null) {
            throw new NotFoundException(MSG_NOT_FOUND);
        }
        Product product = productRepository.findByTenantIdAndBarcode(tenantId, barcode)
                .orElseThrow(() -> new NotFoundException(MSG_NOT_FOUND));
        return detail(product);
    }

    /** Fila de inventario del producto con ese código, para enriquecer la lectura de una etiqueta. */
    @Transactional(readOnly = true)
    public Optional<ProductListItem> findListItemByBarcode(String rawBarcode) {
        Long tenantId = CurrentUser.tenantId();
        String barcode = Barcodes.normalize(rawBarcode);
        if (barcode == null) {
            return Optional.empty();
        }
        return productRepository.findByTenantIdAndBarcode(tenantId, barcode).map(this::listItem);
    }

    // ------------------------------------------------------------------ escrituras

    @Transactional
    public ProductDetail create(ProductRequest request) {
        Long tenantId = CurrentUser.tenantId();
        Product product = new Product();
        product.setTenantId(tenantId);
        apply(product, request, tenantId);
        productRepository.save(product);
        return detail(product);
    }

    @Transactional
    public ProductDetail update(Long id, ProductRequest request) {
        Long tenantId = CurrentUser.tenantId();
        Product product = productRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NotFoundException(MSG_NOT_FOUND));
        apply(product, request, tenantId);
        productRepository.save(product);
        return detail(product);
    }

    /**
     * Baja de un producto (SPEC §6.3): si ya tiene movimientos o lotes se desactiva para conservar la historia; si
     * nunca se usó, se elimina.
     *
     * @return {@code true} si quedó desactivado en vez de eliminado
     */
    @Transactional
    public boolean delete(Long id) {
        Long tenantId = CurrentUser.tenantId();
        Product product = productRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NotFoundException(MSG_NOT_FOUND));
        boolean used = movementRepository.existsByTenantIdAndProductId(tenantId, id)
                || lotRepository.exists((root, query, cb) -> cb.and(
                        cb.equal(root.get("tenantId"), tenantId), cb.equal(root.get("productId"), id)));
        if (used) {
            product.setActive(false);
            productRepository.save(product);
            return true;
        }
        productRepository.delete(product);
        return false;
    }

    // ------------------------------------------------------------------ armado de DTOs

    /** Alcance de sucursales del request actual. */
    public CatalogScope scope() {
        return CatalogScope.of(branchAccessService.accessibleBranches(), branchAccessService.scopeBranchIds());
    }

    private ProductListItem listItem(Product product) {
        Long tenantId = product.getTenantId();
        CatalogScope scope = scope();
        return toListItem(product, categoryNames(tenantId),
                stockReader.statsByProduct(tenantId, scope.branchIds(), List.of(product.getId())), scope);
    }

    private ProductDetail detail(Product product) {
        Long tenantId = product.getTenantId();
        CatalogScope scope = scope();
        ProductListItem item = toListItem(product, categoryNames(tenantId),
                stockReader.statsByProduct(tenantId, scope.branchIds(), List.of(product.getId())), scope);
        String supplierName = product.getSupplierId() == null ? null
                : supplierRepository.findByIdAndTenantId(product.getSupplierId(), tenantId)
                        .map(Supplier::getName).orElse(null);
        return new ProductDetail(item.id(), item.barcode(), item.name(), item.brand(), product.getDescription(),
                item.categoryId(), item.categoryName(), product.getSupplierId(), supplierName, item.unit(),
                item.costPrice(), item.salePrice(), item.minStock(), item.perishable(), item.active(),
                item.sellableStock(), item.expiredStock(), item.quarantinedStock(), item.nextExpiryDate(),
                item.lotsCount(), item.stockStatus(), item.stockByBranch(), lotsOf(tenantId, product.getId(), scope),
                product.getCreatedAt(), product.getUpdatedAt());
    }

    /** Lotes del producto en el alcance: los que tienen remanente y los agotados de los últimos 30 días. */
    private List<LotDto> lotsOf(Long tenantId, Long productId, CatalogScope scope) {
        if (scope.isEmpty()) {
            return List.of();
        }
        Instant recentSince = clock.instant().minus(RECENT_EMPTY_LOT_DAYS, ChronoUnit.DAYS);
        List<Lot> lots = lotRepository
                .findByTenantIdAndBranchIdInAndProductIdOrderByBranchIdAscReceivedAtAscIdAsc(
                        tenantId, scope.branchIds(), productId)
                .stream()
                .filter(lot -> lot.getQuantity() > 0
                        || (lot.getReceivedAt() != null && lot.getReceivedAt().isAfter(recentSince)))
                .toList();
        LotMapper.Context context = lotMapper.context(tenantId, List.of(productId));
        return lotMapper.sortForDisplay(lotMapper.toDtos(lots, context));
    }

    private ProductListItem toListItem(Product product, Map<Long, String> categories,
                                       Map<Long, ProductStock> stocks, CatalogScope scope) {
        ProductStock stock = stocks.getOrDefault(product.getId(), ProductStock.EMPTY);
        List<BranchStockDto> byBranch = new ArrayList<>();
        List<String> statuses = new ArrayList<>();
        for (BranchRef branch : scope.branches()) {
            int sellable = stock.sellableIn(branch.id());
            String status = StockStatuses.of(sellable, product.getMinStock());
            statuses.add(status);
            byBranch.add(new BranchStockDto(branch.id(), branch.name(), sellable, status));
        }
        String consolidated = scope.isEmpty()
                ? StockStatuses.of(stock.sellableStock(), product.getMinStock())
                : StockStatuses.worst(statuses);
        return new ProductListItem(product.getId(), product.getBarcode(), product.getName(), product.getBrand(),
                product.getCategoryId(),
                product.getCategoryId() == null ? null : categories.get(product.getCategoryId()),
                product.getUnit(), product.getCostPrice(), product.getSalePrice(), product.getMinStock(),
                product.isPerishable(), product.isActive(), stock.sellableStock(), stock.expiredStock(),
                stock.quarantinedStock(), stock.nextExpiryDate(), stock.lotsCount(), consolidated, byBranch);
    }

    // ------------------------------------------------------------------ internos

    private void apply(Product product, ProductRequest request, Long tenantId) {
        String barcode = Barcodes.normalize(request.barcode());
        if (request.barcode() != null && !request.barcode().isBlank() && barcode == null) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR, MSG_INVALID_BARCODE);
        }
        if (barcode != null) {
            productRepository.findByTenantIdAndBarcode(tenantId, barcode)
                    .filter(other -> !other.getId().equals(product.getId()))
                    .ifPresent(other -> {
                        throw new ConflictException(DUPLICATE_BARCODE_CODE, MSG_DUPLICATE_BARCODE);
                    });
        }
        Long categoryId = request.categoryId();
        if (categoryId != null) {
            categoryRepository.findByIdAndTenantId(categoryId, tenantId)
                    .orElseThrow(() -> new BadRequestException(ErrorCodes.VALIDATION_ERROR, MSG_CATEGORY_NOT_FOUND));
        } else if (request.categoryName() != null && !request.categoryName().isBlank()) {
            categoryId = categoryService.resolveOrCreate(tenantId, request.categoryName());
        }
        if (request.supplierId() != null) {
            supplierRepository.findByIdAndTenantId(request.supplierId(), tenantId)
                    .orElseThrow(() -> new BadRequestException(ErrorCodes.VALIDATION_ERROR, MSG_SUPPLIER_NOT_FOUND));
        }
        product.setBarcode(barcode);
        product.setName(request.name().trim());
        product.setBrand(trimToNull(request.brand()));
        product.setDescription(trimToNull(request.description()));
        product.setCategoryId(categoryId);
        product.setSupplierId(request.supplierId());
        product.setUnit(request.unit() == null ? ProductUnit.UNIDAD : request.unit());
        product.setCostPrice(request.costPrice() == null ? BigDecimal.ZERO : request.costPrice());
        product.setSalePrice(request.salePrice() == null ? BigDecimal.ZERO : request.salePrice());
        product.setMinStock(request.minStock() == null ? 0 : request.minStock());
        product.setPerishable(request.perishable() == null || request.perishable());
        if (request.active() != null) {
            product.setActive(request.active());
        }
    }

    private static Specification<Product> filter(Long tenantId, ProductQuery query) {
        return (root, criteria, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), tenantId));
            if (query.active() != null) {
                predicates.add(cb.equal(root.get("active"), query.active()));
            }
            if (query.categoryId() != null) {
                predicates.add(cb.equal(root.get("categoryId"), query.categoryId()));
            }
            if (query.q() != null) {
                String like = "%" + query.q().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), like),
                        cb.like(cb.lower(root.get("brand")), like),
                        cb.like(cb.lower(root.get("barcode")), like)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private boolean matchesStockFilter(ProductListItem row, ProductQuery query, TenantSettings settings,
                                       LocalDate today) {
        if (!query.filtersStock()) {
            return true;
        }
        if (ProductQuery.STATUS_EXPIRING.equals(query.stockStatus())) {
            if (row.expiredStock() > 0) {
                return true;
            }
            Integer days = ExpiryBuckets.daysToExpiry(row.nextExpiryDate(), today);
            return days != null && days <= settings.getExpiryWarningDays();
        }
        return query.stockStatus().equals(row.stockStatus());
    }

    private static Comparator<ProductListItem> comparator(ProductQuery query) {
        Comparator<ProductListItem> comparator = switch (query.sortField()) {
            case "barcode" -> Comparator.comparing(ProductListItem::barcode, nullsLastText());
            case "brand" -> Comparator.comparing(ProductListItem::brand, nullsLastText());
            case "categoryName" -> Comparator.comparing(ProductListItem::categoryName, nullsLastText());
            case "salePrice" -> Comparator.comparing(ProductListItem::salePrice,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case "costPrice" -> Comparator.comparing(ProductListItem::costPrice,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case "minStock" -> Comparator.comparingInt(ProductListItem::minStock);
            case "sellableStock" -> Comparator.comparingInt(ProductListItem::sellableStock);
            case "nextExpiryDate" -> Comparator.comparing(ProductListItem::nextExpiryDate,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case "stockStatus" -> Comparator.comparingInt(row -> StockStatuses.rank(row.stockStatus()));
            case "createdAt" -> Comparator.comparing(ProductListItem::id);
            default -> Comparator.comparing(ProductListItem::name, String.CASE_INSENSITIVE_ORDER);
        };
        if (query.sortDescending()) {
            comparator = comparator.reversed();
        }
        return comparator.thenComparing(ProductListItem::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ProductListItem::id);
    }

    private static Comparator<String> nullsLastText() {
        return Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER);
    }

    private Map<Long, String> categoryNames(Long tenantId) {
        return categoryRepository.findByTenantIdOrderByNameAsc(tenantId).stream()
                .collect(Collectors.toMap(Category::getId, Category::getName, (a, b) -> a, HashMap::new));
    }

    private TenantSettings settings(Long tenantId) {
        return tenantSettingsRepository.findById(tenantId).orElseGet(() -> TenantSettings.defaultsFor(tenantId));
    }

    /** Productos del comercio por id (para validar referencias desde otros servicios del módulo). */
    Product require(Long tenantId, Long productId) {
        return productRepository.findByIdAndTenantId(productId, tenantId)
                .orElseThrow(() -> new NotFoundException(MSG_NOT_FOUND));
    }

    static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
