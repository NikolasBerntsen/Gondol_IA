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
        for (ImportRowStore.StoredRow row : rows) {
            try {
                applyRow(jobId, tenantId, userId, options, branchIdsBySlug, row, totals, base);
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
                          Map<String, Long> branchIdsBySlug, ImportRowStore.StoredRow row, Totals totals,
                          Instant base) {
        ParsedRow parsed = ImportRowParser.parse(row.data(), options.dateFormat());
        if (parsed.name() == null) {
            List<ImportDtos.RowMessageDto> messages = new ArrayList<>(row.messages());
            messages.add(new ImportDtos.RowMessageDto("name", "ERROR", "Falta el nombre del producto."));
            rowStore.markApplied(row.id(), ImportRowStatus.FAILED, null, null, messages);
            totals.rowsFailed++;
            return;
        }
        Long categoryId = resolveCategory(tenantId, parsed.category(), options, totals);
        Long supplierId = resolveSupplier(tenantId, parsed.supplier(), options, totals);
        Product product = findProduct(tenantId, parsed);
        if (product == null) {
            product = createProduct(tenantId, parsed, categoryId, supplierId);
            totals.productsCreated++;
        } else if (row.action() == ImportRowAction.UPDATE) {
            updateProduct(product, parsed, categoryId, supplierId);
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

    private Product findProduct(Long tenantId, ParsedRow parsed) {
        if (parsed.barcode() != null) {
            return productRepository.findByTenantIdAndBarcode(tenantId, parsed.barcode()).orElse(null);
        }
        String slug = ImportValues.slug(parsed.name());
        return productRepository.findByTenantId(tenantId).stream()
                .filter(p -> ImportValues.slug(p.getName()).equals(slug))
                .findFirst()
                .orElse(null);
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
        productRepository.saveAndFlush(product);
    }

    private Long resolveCategory(Long tenantId, String name, ImportOptions options, Totals totals) {
        if (name == null) {
            return null;
        }
        return categoryRepository.findByTenantIdAndNameIgnoreCase(tenantId, name)
                .map(Category::getId)
                .orElseGet(() -> {
                    if (!options.createCategories()) {
                        return null;
                    }
                    Category category = new Category();
                    category.setTenantId(tenantId);
                    category.setName(name);
                    totals.categoriesCreated++;
                    return categoryRepository.saveAndFlush(category).getId();
                });
    }

    private Long resolveSupplier(Long tenantId, String name, ImportOptions options, Totals totals) {
        if (name == null) {
            return null;
        }
        String slug = ImportValues.slug(name);
        Supplier existing = supplierRepository.findByTenantIdOrderByNameAsc(tenantId).stream()
                .filter(s -> ImportValues.slug(s.getName()).equals(slug))
                .findFirst()
                .orElse(null);
        if (existing != null) {
            return existing.getId();
        }
        if (!options.createSuppliers()) {
            return null;
        }
        Supplier supplier = new Supplier();
        supplier.setTenantId(tenantId);
        supplier.setName(name);
        supplier.setActive(true);
        totals.suppliersCreated++;
        return supplierRepository.saveAndFlush(supplier).getId();
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
