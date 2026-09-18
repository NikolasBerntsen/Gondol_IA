package com.gondolia.imports.validate;

import com.gondolia.common.util.Barcodes;
import com.gondolia.domain.imports.ImportRowAction;
import com.gondolia.domain.imports.ImportRowStatus;
import com.gondolia.domain.inventory.Category;
import com.gondolia.domain.inventory.CategoryRepository;
import com.gondolia.domain.inventory.Product;
import com.gondolia.domain.inventory.ProductRepository;
import com.gondolia.domain.inventory.Supplier;
import com.gondolia.domain.inventory.SupplierRepository;
import com.gondolia.imports.parse.ImportField;
import com.gondolia.imports.parse.ImportRowParser;
import com.gondolia.imports.parse.ImportValues;
import com.gondolia.imports.parse.ParsedRow;
import com.gondolia.recall.RecallMatchingService;
import com.gondolia.security.BranchAccessService.BranchRef;
import com.gondolia.stock.StockService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Validación de las filas de una importación con las reglas exactas de SPEC §16.2. Una corrida
 * ({@link Run}) toma una foto del catálogo del comercio y valida todas las filas del archivo en orden, así puede
 * detectar duplicados y avisar qué categorías y proveedores se van a crear.
 */
@Service
@RequiredArgsConstructor
public class ImportValidator {

    /** Prefijo del aviso de recall (lo usa el resumen del paso «Confirmar»). */
    public static final String RECALL_MESSAGE_PREFIX = "Este lote coincide con una alerta de recall";

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final SupplierRepository supplierRepository;
    private final StockService stockService;
    private final RecallMatchingService recallMatchingService;
    private final Clock clock;

    /**
     * Arranca una corrida de validación sobre el catálogo actual del comercio.
     *
     * @param branches  sucursales accesibles por quien importa
     * @param singleRow {@code true} al revalidar una sola fila editada (no se controlan duplicados del archivo)
     */
    public Run start(Long tenantId, ImportOptions options, List<BranchRef> branches, boolean singleRow) {
        return new Run(tenantId, options, branches, singleRow);
    }

    /** Estado de una corrida de validación (no es thread-safe: se usa dentro de un request). */
    public class Run {

        private final Long tenantId;
        private final ImportOptions options;
        private final LocalDate today;
        private final Map<String, Product> productsByBarcode = new HashMap<>();
        private final Map<String, Product> productsByName = new HashMap<>();
        private final Set<String> categorySlugs = new LinkedHashSet<>();
        private final Set<String> supplierSlugs = new LinkedHashSet<>();
        private final Map<String, BranchRef> branchesBySlug = new LinkedHashMap<>();
        private final Map<Long, Map<Long, Integer>> stockByBranch;
        private final Map<String, List<RecallMatchingService.RecallInfo>> recallCache = new HashMap<>();
        private final Map<String, FirstOccurrence> firstOccurrences = new HashMap<>();
        private final Set<String> newCategories = new LinkedHashSet<>();
        private final Set<String> newSuppliers = new LinkedHashSet<>();
        private final boolean singleRow;
        private final BranchRef defaultBranch;

        private record FirstOccurrence(int rowNumber, ParsedRow parsed) {
        }

        private Run(Long tenantId, ImportOptions options, List<BranchRef> branches, boolean singleRow) {
            this.tenantId = tenantId;
            this.options = options;
            this.singleRow = singleRow;
            this.today = LocalDate.now(clock);
            for (Product product : productRepository.findByTenantId(tenantId)) {
                if (product.getBarcode() != null) {
                    productsByBarcode.putIfAbsent(product.getBarcode(), product);
                }
                String slug = ImportValues.slug(product.getName());
                if (!slug.isEmpty()) {
                    productsByName.putIfAbsent(slug, product);
                }
            }
            for (Category category : categoryRepository.findByTenantIdOrderByNameAsc(tenantId)) {
                categorySlugs.add(ImportValues.slug(category.getName()));
            }
            for (Supplier supplier : supplierRepository.findByTenantIdOrderByNameAsc(tenantId)) {
                supplierSlugs.add(ImportValues.slug(supplier.getName()));
            }
            for (BranchRef branch : branches) {
                branchesBySlug.putIfAbsent(ImportValues.slug(branch.name()), branch);
                if (branch.code() != null && !branch.code().isBlank()) {
                    branchesBySlug.putIfAbsent(ImportValues.slug(branch.code()), branch);
                }
            }
            this.defaultBranch = branches.stream()
                    .filter(b -> Objects.equals(b.id(), options.defaultBranchId()))
                    .findFirst()
                    .orElse(null);
            List<Long> branchIds = branches.stream().map(BranchRef::id).toList();
            this.stockByBranch = branchIds.isEmpty()
                    ? Map.of()
                    : stockService.sellableStockByBranchAndProduct(tenantId, branchIds);
        }

        /** Categorías nuevas que se van a crear con las filas validadas hasta ahora. */
        public List<String> newCategories() {
            return List.copyOf(newCategories);
        }

        /** Proveedores nuevos que se van a crear con las filas validadas hasta ahora. */
        public List<String> newSuppliers() {
            return List.copyOf(newSuppliers);
        }

        public ImportOptions options() {
            return options;
        }

        /**
         * Valida una fila. {@code userSkipped} conserva el estado {@code SKIPPED} de las filas que el usuario omitió.
         */
        public RowValidation validate(int rowNumber, Map<String, String> data, boolean userSkipped) {
            ParsedRow parsed = ImportRowParser.parse(data, options.dateFormat());
            List<RowValidation.Message> messages = new ArrayList<>();
            for (ImportRowParser.ParseIssue issue : parsed.messages()) {
                messages.add(new RowValidation.Message(issue.field(),
                        issue.error() ? RowValidation.Level.ERROR : RowValidation.Level.WARNING, issue.message()));
            }

            if (parsed.name() == null) {
                messages.add(error(ImportField.NAME, "Falta el nombre del producto."));
            }
            checkBarcode(parsed, messages);

            Product existing = findExisting(parsed);
            boolean duplicate = !singleRow && registerDuplicate(rowNumber, parsed, messages);
            ImportRowAction action = resolveAction(parsed, existing, duplicate, messages);

            boolean createsCategory = checkCategory(parsed, messages);
            boolean createsSupplier = checkSupplier(parsed, messages);
            if (parsed.unitUnknown()) {
                messages.add(warning(ImportField.UNIT, "No reconocimos la unidad: se carga como UNIDAD."));
            }
            checkPrices(parsed, existing, messages);

            BranchRef branch = resolveBranch(parsed, messages);
            checkStock(parsed, existing, branch, messages);

            ImportRowStatus status;
            if (userSkipped) {
                status = ImportRowStatus.SKIPPED;
            } else if (messages.stream().anyMatch(m -> m.level() == RowValidation.Level.ERROR)) {
                status = ImportRowStatus.ERROR;
            } else if (!messages.isEmpty()) {
                status = ImportRowStatus.WARNING;
            } else {
                status = ImportRowStatus.VALID;
            }
            return new RowValidation(status, action, messages, parsed, branch == null ? null : branch.id(),
                    existing == null ? null : existing.getId(), createsCategory, createsSupplier);
        }

        // -------------------------------------------------------------------

        private void checkBarcode(ParsedRow parsed, List<RowValidation.Message> messages) {
            String barcode = parsed.barcode();
            if (barcode == null) {
                return;
            }
            if (!Barcodes.isValid(barcode)) {
                messages.add(error(ImportField.BARCODE,
                        "El código de barras solo puede tener letras y números: «" + barcode + "»."));
                return;
            }
            if (barcode.matches("\\d{8}|\\d{12}|\\d{13}") && !Barcodes.isValidGtin(barcode)) {
                messages.add(warning(ImportField.BARCODE,
                        "El dígito verificador del código no es válido: revisá que esté bien copiado."));
            }
        }

        private Product findExisting(ParsedRow parsed) {
            if (parsed.barcode() != null) {
                return productsByBarcode.get(parsed.barcode());
            }
            if (parsed.name() != null) {
                return productsByName.get(ImportValues.slug(parsed.name()));
            }
            return null;
        }

        /** Registra la fila y avisa si contradice a una anterior del mismo producto ({@code true} si es repetida). */
        private boolean registerDuplicate(int rowNumber, ParsedRow parsed, List<RowValidation.Message> messages) {
            String key = duplicateKey(parsed);
            if (key == null) {
                return false;
            }
            FirstOccurrence first = firstOccurrences.get(key);
            if (first == null) {
                firstOccurrences.put(key, new FirstOccurrence(rowNumber, parsed));
                return false;
            }
            if (first.rowNumber() == rowNumber) {
                return false;   // revalidación de la misma fila
            }
            ParsedRow other = first.parsed();
            record Comparison(ImportField field, Object first, Object current, String label) {
            }
            List<Comparison> comparisons = List.of(
                    new Comparison(ImportField.NAME, other.name(), parsed.name(), "el nombre"),
                    new Comparison(ImportField.BRAND, other.brand(), parsed.brand(), "la marca"),
                    new Comparison(ImportField.CATEGORY, other.category(), parsed.category(), "la categoría"),
                    new Comparison(ImportField.SUPPLIER, other.supplier(), parsed.supplier(), "el proveedor"),
                    new Comparison(ImportField.SALE_PRICE, other.salePrice(), parsed.salePrice(), "el precio de venta"),
                    new Comparison(ImportField.COST_PRICE, other.costPrice(), parsed.costPrice(), "el precio de costo"),
                    new Comparison(ImportField.MIN_STOCK, other.minStock(), parsed.minStock(), "el stock mínimo"));
            for (Comparison comparison : comparisons) {
                if (comparison.current() == null || comparison.first() == null) {
                    continue;
                }
                boolean same = comparison.first() instanceof BigDecimal a && comparison.current() instanceof BigDecimal b
                        ? a.compareTo(b) == 0
                        : Objects.equals(comparison.first(), comparison.current());
                if (!same) {
                    messages.add(warning(comparison.field(), "La fila " + first.rowNumber()
                            + " ya carga este producto con otro valor en " + comparison.label()
                            + ": vale el de la primera fila."));
                }
            }
            return true;
        }

        private String duplicateKey(ParsedRow parsed) {
            if (parsed.barcode() != null) {
                return "B:" + parsed.barcode();
            }
            if (parsed.name() != null) {
                return "N:" + ImportValues.slug(parsed.name());
            }
            return null;
        }

        private ImportRowAction resolveAction(ParsedRow parsed, Product existing, boolean duplicate,
                                              List<RowValidation.Message> messages) {
            if (duplicate) {
                messages.add(warning(null, "Este producto ya viene en una fila anterior: acá solo se carga el lote."));
                return ImportRowAction.SKIP;
            }
            if (existing == null) {
                return ImportRowAction.CREATE;
            }
            String reference = existing.getBarcode() != null ? "código " + existing.getBarcode()
                    : "nombre «" + existing.getName() + "»";
            if (options.updateExisting()) {
                messages.add(warning(null,
                        "El producto ya existe en tu catálogo (" + reference + "): se van a actualizar sus datos."));
                return ImportRowAction.UPDATE;
            }
            messages.add(warning(null, "El producto ya existe en tu catálogo (" + reference
                    + "): se mantienen sus datos" + (options.importStock() ? " y solo se carga el stock." : ".")));
            return ImportRowAction.SKIP;
        }

        private boolean checkCategory(ParsedRow parsed, List<RowValidation.Message> messages) {
            String name = parsed.category();
            if (name == null) {
                return false;
            }
            String slug = ImportValues.slug(name);
            if (categorySlugs.contains(slug)) {
                return false;
            }
            if (!options.createCategories()) {
                messages.add(error(ImportField.CATEGORY, "La categoría «" + name
                        + "» no existe. Creala primero o activá «Crear las categorías nuevas»."));
                return false;
            }
            messages.add(warning(ImportField.CATEGORY, "La categoría «" + name + "» es nueva: se va a crear."));
            newCategories.add(name);
            return true;
        }

        private boolean checkSupplier(ParsedRow parsed, List<RowValidation.Message> messages) {
            String name = parsed.supplier();
            if (name == null) {
                return false;
            }
            String slug = ImportValues.slug(name);
            if (supplierSlugs.contains(slug)) {
                return false;
            }
            if (!options.createSuppliers()) {
                messages.add(error(ImportField.SUPPLIER, "El proveedor «" + name
                        + "» no existe. Cargalo primero o activá «Crear los proveedores nuevos»."));
                return false;
            }
            messages.add(warning(ImportField.SUPPLIER, "El proveedor «" + name + "» es nuevo: se va a crear."));
            newSuppliers.add(name);
            return true;
        }

        private void checkPrices(ParsedRow parsed, Product existing, List<RowValidation.Message> messages) {
            BigDecimal sale = parsed.salePrice() != null ? parsed.salePrice()
                    : existing != null ? existing.getSalePrice() : null;
            BigDecimal cost = parsed.costPrice() != null ? parsed.costPrice()
                    : existing != null ? existing.getCostPrice() : null;
            if (sale == null || cost == null || cost.signum() == 0 || sale.signum() == 0) {
                return;
            }
            if (sale.compareTo(cost) < 0) {
                messages.add(warning(ImportField.SALE_PRICE,
                        "El precio de venta es menor al costo: revisá que no estén invertidos."));
            }
        }

        private BranchRef resolveBranch(ParsedRow parsed, List<RowValidation.Message> messages) {
            if (parsed.branch() != null) {
                BranchRef branch = branchesBySlug.get(ImportValues.slug(parsed.branch()));
                if (branch == null) {
                    messages.add(error(ImportField.BRANCH, "La sucursal «" + parsed.branch()
                            + "» no existe o no tenés acceso. Sucursales válidas: " + branchNames() + "."));
                    return null;
                }
                return branch;
            }
            return defaultBranch;
        }

        private String branchNames() {
            return branchesBySlug.values().stream().map(BranchRef::name).distinct()
                    .reduce((a, b) -> a + ", " + b).orElse("ninguna");
        }

        private void checkStock(ParsedRow parsed, Product existing, BranchRef branch,
                                List<RowValidation.Message> messages) {
            int quantity = parsed.quantityOrZero();
            if (quantity > 0 && !options.importStock()) {
                messages.add(warning(ImportField.QUANTITY,
                        "Esta importación no carga stock: la cantidad se ignora."));
                return;
            }
            if (quantity > 0 && branch == null) {
                // Si la sucursal vino escrita pero no existe, el error ya lo puso resolveBranch: no lo repetimos.
                if (parsed.branch() == null) {
                    messages.add(error(ImportField.BRANCH,
                            "Esta fila carga stock: indicá la sucursal en el archivo o elegí una sucursal por defecto."));
                }
                return;
            }
            if (quantity == 0) {
                if (parsed.lotNumber() != null || parsed.expiryDate() != null) {
                    messages.add(warning(ImportField.QUANTITY,
                            "La fila trae datos de lote pero no trae cantidad: no se carga stock."));
                }
                return;
            }
            if (parsed.expiryDate() != null && parsed.expiryDate().isBefore(today)) {
                messages.add(warning(ImportField.EXPIRY_DATE,
                        "El vencimiento ya pasó: el lote entra como vencido pendiente de descarte."));
            }
            if (existing != null && branch != null) {
                int current = stockByBranch.getOrDefault(branch.id(), Map.of())
                        .getOrDefault(existing.getId(), 0);
                if (current > 0) {
                    messages.add(warning(ImportField.QUANTITY, "El producto ya tiene " + current + " unidades en "
                            + branch.name() + ": se suma un lote nuevo."));
                }
            }
            checkRecall(parsed, messages);
        }

        private void checkRecall(ParsedRow parsed, List<RowValidation.Message> messages) {
            if (parsed.barcode() == null) {
                return;
            }
            String key = parsed.barcode() + "|" + (parsed.lotNumber() == null ? "" : parsed.lotNumber()) + "|"
                    + (parsed.expiryDate() == null ? "" : parsed.expiryDate());
            List<RecallMatchingService.RecallInfo> recalls = recallCache.computeIfAbsent(key,
                    ignored -> recallMatchingService.findActiveRecalls(parsed.barcode(), parsed.lotNumber(),
                            parsed.expiryDate()));
            if (!recalls.isEmpty()) {
                messages.add(warning(ImportField.LOT_NUMBER, RECALL_MESSAGE_PREFIX + " («"
                        + recalls.getFirst().title() + "»): entra en cuarentena y no se va a poder vender."));
            }
        }

        private RowValidation.Message error(ImportField field, String text) {
            return new RowValidation.Message(field, RowValidation.Level.ERROR, text);
        }

        private RowValidation.Message warning(ImportField field, String text) {
            return new RowValidation.Message(field, RowValidation.Level.WARNING, text);
        }
    }
}
