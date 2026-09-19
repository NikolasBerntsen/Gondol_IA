package com.gondolia.imports;

import com.gondolia.common.error.ApiException;
import com.gondolia.domain.common.Severity;
import com.gondolia.domain.imports.ImportJob;
import com.gondolia.domain.imports.ImportJobRepository;
import com.gondolia.domain.imports.ImportRowAction;
import com.gondolia.domain.imports.ImportRowStatus;
import com.gondolia.domain.imports.ImportStatus;
import com.gondolia.domain.inventory.Category;
import com.gondolia.domain.inventory.CategoryRepository;
import com.gondolia.domain.inventory.MovementSource;
import com.gondolia.domain.inventory.Product;
import com.gondolia.domain.inventory.ProductRepository;
import com.gondolia.domain.inventory.Supplier;
import com.gondolia.domain.inventory.SupplierRepository;
import com.gondolia.domain.notification.NotificationType;
import com.gondolia.imports.dto.ImportDtos;
import com.gondolia.imports.parse.ImportRowParser;
import com.gondolia.imports.parse.ImportValues;
import com.gondolia.imports.parse.ParsedRow;
import com.gondolia.imports.validate.ImportOptions;
import com.gondolia.notification.NotificationDraft;
import com.gondolia.notification.NotificationService;
import com.gondolia.security.BranchAccessService.BranchRef;
import com.gondolia.stock.StockService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aplica un bloque de filas de una importación dentro de su propia transacción: crea categorías y proveedores,
 * hace el alta o la actualización del producto y carga el lote con {@link StockService#receiveLot} (SPEC §16.3).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ImportChunkProcessor {

    /** Acumulador de lo que va haciendo la importación. */
    public static class Totals {
        int productsCreated;
        int productsUpdated;
        int lotsCreated;
        long unitsLoaded;
        int categoriesCreated;
        int suppliersCreated;
        int rowsSkipped;
        int rowsFailed;
        int recallMatches;

        public ImportDtos.ResultDto toResult() {
            return new ImportDtos.ResultDto(productsCreated, productsUpdated, lotsCreated, unitsLoaded,
                    categoriesCreated, suppliersCreated, rowsSkipped, rowsFailed, recallMatches);
        }
    }

    private final ImportJobRepository jobRepository;
    private final ImportRowStore rowStore;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final SupplierRepository supplierRepository;
    private final StockService stockService;
    private final NotificationService notificationService;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processChunk(Long jobId, Long tenantId, Long userId, List<BranchRef> branches,
                             List<ImportRowStore.StoredRow> rows, Totals totals, Instant base) {
        ImportJob job = jobRepository.findById(jobId).orElseThrow();
        ImportOptions options = ImportOptions.fromJson(job.getOptions());
        Map<String, Long> branchIdsBySlug = new HashMap<>();
        for (BranchRef branch : branches) {
            branchIdsBySlug.putIfAbsent(ImportValues.slug(branch.name()), branch.id());
            if (branch.code() != null && !branch.code().isBlank()) {
                branchIdsBySlug.putIfAbsent(ImportValues.slug(branch.code()), branch.id());
            }
        }
        CatalogCache cache = new CatalogCache(tenantId);
        for (ImportRowStore.StoredRow row : rows) {
            try {
                applyRow(jobId, tenantId, userId, options, branchIdsBySlug, cache, row, totals, base);
            } catch (ApiException e) {
                totals.rowsFailed++;
                List<ImportDtos.RowMessageDto> messages = new ArrayList<>(row.messages());
                messages.add(new ImportDtos.RowMessageDto(null, "ERROR", e.getMessage()));
                rowStore.markApplied(row.id(), ImportRowStatus.FAILED, null, null, messages);
                log.warn("Importación {}: la fila {} no se pudo aplicar ({})", jobId, row.rowNumber(), e.getMessage());
            }
        }
        job.setProcessedRows(job.getProcessedRows() + rows.size());
        jobRepository.save(job);
    }

    private void applyRow(Long jobId, Long tenantId, Long userId, ImportOptions options,
                          Map<String, Long> branchIdsBySlug, CatalogCache cache, ImportRowStore.StoredRow row,
                          Totals totals, Instant base) {
        ParsedRow parsed = ImportRowParser.parse(row.data(), options.dateFormat());
        if (parsed.name() == null) {
            List<ImportDtos.RowMessageDto> messages = new ArrayList<>(row.messages());
            messages.add(new ImportDtos.RowMessageDto("name", "ERROR", "Falta el nombre del producto."));
            rowStore.markApplied(row.id(), ImportRowStatus.FAILED, null, null, messages);
            totals.rowsFailed++;
            return;
        }
        Long categoryId = cache.category(parsed.category(), options, totals);
        Long supplierId = cache.supplier(parsed.supplier(), options, totals);
        Product product = cache.product(parsed);
        if (product == null) {
            product = createProduct(tenantId, parsed, categoryId, supplierId);
            cache.remember(product);
            totals.productsCreated++;
        } else if (row.action() == ImportRowAction.UPDATE) {
            updateProduct(product, parsed, categoryId, supplierId);
            cache.remember(product);
            totals.productsUpdated++;
        }

        Long lotId = null;
        int quantity = parsed.quantityOrZero();
        if (options.importStock() && quantity > 0) {
            Long branchId = resolveBranch(parsed, options, branchIdsBySlug);
            if (branchId == null) {
                throw new com.gondolia.common.error.BadRequestException(
                        com.gondolia.common.error.ErrorCodes.BRANCH_REQUIRED,
                        "No pudimos determinar la sucursal para cargar el stock de esta fila.");
            }
            Instant receivedAt = receivedAt(parsed, base, row.rowNumber());
            StockService.ReceiveLotResult result = stockService.receiveLot(new StockService.ReceiveLotCommand(
                    tenantId, branchId, product.getId(), parsed.lotNumber(), parsed.expiryDate(), quantity,
                    parsed.costPrice(), supplierId, receivedAt, MovementSource.IMPORT, userId,
                    "Importación masiva #" + jobId));
            lotId = result.lot().getId();
            totals.lotsCreated++;
            totals.unitsLoaded += quantity;
            totals.recallMatches += result.recallMatches().size();
        }
        rowStore.markApplied(row.id(), ImportRowStatus.IMPORTED, product.getId(), lotId, row.messages());
    }

    /**
     * Instante de ingreso: la fecha de la columna (a la hora de apertura del negocio) o el momento de la
     * importación, más un milisegundo por número de fila para conservar el orden del archivo en FIFO (SPEC §16.3).
     */
    private Instant receivedAt(ParsedRow parsed, Instant base, int rowNumber) {
        Instant start = parsed.receivedAt() == null
                ? base
                : parsed.receivedAt().atStartOfDay(clock.getZone()).toInstant();
        return start.plusMillis(rowNumber);
    }

    private Long resolveBranch(ParsedRow parsed, ImportOptions options, Map<String, Long> branchIdsBySlug) {
        if (parsed.branch() != null) {
            return branchIdsBySlug.get(ImportValues.slug(parsed.branch()));
        }
        return options.defaultBranchId();
    }

    private Product createProduct(Long tenantId, ParsedRow parsed, Long categoryId, Long supplierId) {
        Product product = new Product();
        product.setTenantId(tenantId);
        product.setBarcode(parsed.barcode());
        product.setName(parsed.name());
        product.setBrand(parsed.brand());
        product.setDescription(parsed.description());
        product.setCategoryId(categoryId);
        product.setSupplierId(supplierId);
        if (parsed.unit() != null) {
            product.setUnit(parsed.unit());
        }
        if (parsed.costPrice() != null) {
            product.setCostPrice(parsed.costPrice());
        }
        if (parsed.salePrice() != null) {
            product.setSalePrice(parsed.salePrice());
        }
        if (parsed.minStock() != null) {
            product.setMinStock(parsed.minStock());
        }
        if (parsed.perishable() != null) {
            product.setPerishable(parsed.perishable());
        } else {
            product.setPerishable(parsed.expiryDate() != null);
        }
        product.setActive(true);
        return productRepository.saveAndFlush(product);
    }

    private void updateProduct(Product product, ParsedRow parsed, Long categoryId, Long supplierId) {
        if (parsed.barcode() != null) {
            product.setBarcode(parsed.barcode());
        }
        product.setName(parsed.name());
        if (parsed.brand() != null) {
            product.setBrand(parsed.brand());
        }
        if (parsed.description() != null) {
            product.setDescription(parsed.description());
        }
        if (categoryId != null) {
            product.setCategoryId(categoryId);
        }
        if (supplierId != null) {
            product.setSupplierId(supplierId);
        }
        if (parsed.unit() != null) {
            product.setUnit(parsed.unit());
        }
        if (parsed.costPrice() != null) {
            product.setCostPrice(parsed.costPrice());
        }
        if (parsed.salePrice() != null) {
            product.setSalePrice(parsed.salePrice());
        }
        if (parsed.minStock() != null) {
            product.setMinStock(parsed.minStock());
        }
        if (parsed.perishable() != null) {
            product.setPerishable(parsed.perishable());
        }
        // Reimportar un producto dado de baja con «Actualizar los existentes» lo vuelve a activar (la validación avisa).
        product.setActive(true);
        productRepository.saveAndFlush(product);
    }

    /**
     * Catálogo del comercio cargado una vez por bloque. Categorías, proveedores y productos sin código se buscan por
     * nombre sin acentos ni mayúsculas, igual que en la validación: «Almacen» usa la categoría «Almacén» existente.
     */
    private final class CatalogCache {

        private final Long tenantId;
        private Map<String, Long> categories;
        private Map<String, Long> suppliers;
        private Map<String, Product> productsByName;

        private CatalogCache(Long tenantId) {
            this.tenantId = tenantId;
        }

        Long category(String name, ImportOptions options, Totals totals) {
            if (name == null) {
                return null;
            }
            if (categories == null) {
                categories = new HashMap<>();
                categoryRepository.findByTenantIdOrderByNameAsc(tenantId)
                        .forEach(c -> categories.putIfAbsent(ImportValues.slug(c.getName()), c.getId()));
            }
            String slug = ImportValues.slug(name);
            Long existing = categories.get(slug);
            if (existing != null || !options.createCategories()) {
                return existing;
            }
            Category category = new Category();
            category.setTenantId(tenantId);
            category.setName(name);
            Long id = categoryRepository.saveAndFlush(category).getId();
            categories.put(slug, id);
            totals.categoriesCreated++;
            return id;
        }

        Long supplier(String name, ImportOptions options, Totals totals) {
            if (name == null) {
                return null;
            }
            if (suppliers == null) {
                suppliers = new HashMap<>();
                supplierRepository.findByTenantIdOrderByNameAsc(tenantId)
                        .forEach(s -> suppliers.putIfAbsent(ImportValues.slug(s.getName()), s.getId()));
            }
            String slug = ImportValues.slug(name);
            Long existing = suppliers.get(slug);
            if (existing != null || !options.createSuppliers()) {
                return existing;
            }
            Supplier supplier = new Supplier();
            supplier.setTenantId(tenantId);
            supplier.setName(name);
            supplier.setActive(true);
            Long id = supplierRepository.saveAndFlush(supplier).getId();
            suppliers.put(slug, id);
            totals.suppliersCreated++;
            return id;
        }

        /** Producto existente: por código de barras o, si la fila no trae código, por nombre exacto (SPEC §16.2). */
        Product product(ParsedRow parsed) {
            if (parsed.barcode() != null) {
                return productRepository.findByTenantIdAndBarcode(tenantId, parsed.barcode()).orElse(null);
            }
            if (productsByName == null) {
                productsByName = new HashMap<>();
                productRepository.findByTenantId(tenantId).forEach(this::remember);
            }
            return productsByName.get(ImportValues.slug(parsed.name()));
        }

        void remember(Product product) {
            if (productsByName != null) {
                productsByName.putIfAbsent(ImportValues.slug(product.getName()), product);
            }
        }
    }

    /** Cierra la importación: guarda el resultado y avisa al administrador que la lanzó. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(Long jobId, ImportStatus status, ImportDtos.ResultDto result, String message, Long userId) {
        ImportJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            return;
        }
        job.setStatus(status);
        job.setErrorMessage(message);
        if (result != null) {
            // Las filas omitidas se cuentan en la base: incluyen las que el usuario omitió y las de error ignoradas.
            int skipped = rowStore.countsByStatus(jobId).getOrDefault(ImportRowStatus.SKIPPED, 0);
            result = new ImportDtos.ResultDto(result.productsCreated(), result.productsUpdated(), result.lotsCreated(),
                    result.unitsLoaded(), result.categoriesCreated(), result.suppliersCreated(), skipped,
                    result.rowsFailed(), result.recallMatches());
            job.setResult(rowStore.toNode(result));
        }
        if (status == ImportStatus.APPLIED) {
            job.setAppliedAt(Instant.now(clock));
        }
        jobRepository.save(job);
        if (userId == null) {
            return;
        }
        notificationService.notifyUser(userId, new NotificationDraft(NotificationType.SYSTEM,
                status == ImportStatus.APPLIED ? Severity.INFO : Severity.WARNING,
                title(status, job.getFileName()), body(status, result, message),
                "/app/imports/" + jobId, "IMPORT_JOB", jobId));
    }

    private String title(ImportStatus status, String fileName) {
        return switch (status) {
            case APPLIED -> "Importación terminada: " + fileName;
            case CANCELLED -> "Importación cancelada: " + fileName;
            default -> "La importación no terminó: " + fileName;
        };
    }

    private String body(ImportStatus status, ImportDtos.ResultDto result, String message) {
        if (status != ImportStatus.APPLIED || result == null) {
            return Objects.requireNonNullElse(message, "Revisá el detalle de la importación.");
        }
        Map<String, Object> parts = new LinkedHashMap<>();
        parts.put("productos nuevos", result.productsCreated());
        parts.put("actualizados", result.productsUpdated());
        parts.put("lotes", result.lotsCreated());
        parts.put("unidades", result.unitsLoaded());
        List<String> pieces = new ArrayList<>();
        parts.forEach((label, value) -> pieces.add(value + " " + label));
        String base = String.join(" · ", pieces);
        return result.recallMatches() > 0
                ? base + ". Atención: " + result.recallMatches()
                        + " lote(s) quedaron en cuarentena por una alerta de recall."
                : base + ".";
    }
}
