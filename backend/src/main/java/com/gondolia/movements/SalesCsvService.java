package com.gondolia.movements;

import com.gondolia.common.error.ApiException;
import com.gondolia.common.error.BadRequestException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.common.util.Barcodes;
import com.gondolia.domain.inventory.MovementSource;
import com.gondolia.domain.inventory.Product;
import com.gondolia.domain.inventory.ProductRepository;
import com.gondolia.movements.dto.SaleDtos.SalesImportErrorDto;
import com.gondolia.movements.dto.SaleDtos.SalesImportResultDto;
import com.gondolia.security.BranchAccessService;
import com.gondolia.security.CurrentUser;
import com.gondolia.stock.BatchRefs;
import com.gondolia.stock.StockService;
import com.gondolia.stock.StockService.SaleCommand;
import com.gondolia.stock.StockService.SaleResult;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Importación de ventas históricas del POS propio del cliente desde un CSV
 * ({@code fecha,codigo_barras,cantidad,precio_unitario}, SPEC §6.4). Requiere {@code POS_INTEGRATION}.
 * <p>
 * Cada fecha del archivo genera una venta ({@code batchRef} propio) y dentro de ella las líneas se registran
 * **ordenadas por productId**, igual que una venta manual. Las filas con error se informan y no frenan al resto.
 */
@Service
@RequiredArgsConstructor
public class SalesCsvService {

    /** Encabezados esperados (el orden del archivo no importa si trae encabezado). */
    static final String HEADER_DATE = "fecha";
    static final String HEADER_BARCODE = "codigo_barras";
    static final String HEADER_QUANTITY = "cantidad";
    static final String HEADER_PRICE = "precio_unitario";

    static final long MAX_SIZE_BYTES = 5L * 1024 * 1024;
    static final int MAX_ROWS = 5_000;
    private static final int MAX_ERRORS = 100;

    // ResolverStyle.STRICT (con "uuuu", no "yyyy"): una fecha que no existe se informa como error de la
    // línea en vez de ajustarse sola (con el resolutor SMART "31/02/2026" pasaría a ser el 28/02).
    private static final DateTimeFormatter ISO = strict("uuuu-MM-dd");
    private static final DateTimeFormatter DMY = strict("dd/MM/uuuu");
    private static final DateTimeFormatter DMY_DASH = strict("dd-MM-uuuu");

    private static DateTimeFormatter strict(String pattern) {
        return DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT);
    }

    /** Plantilla que se descarga desde la pantalla de integración. */
    public static final String TEMPLATE = """
            fecha,codigo_barras,cantidad,precio_unitario
            2026-09-15,7791234000012,3,1850.00
            2026-09-15,7791234000029,1,2100.50
            15/09/2026,7791234000036,2,980
            """;

    private final StockService stockService;
    private final BranchAccessService branchAccessService;
    private final ProductRepository productRepository;
    private final Clock clock;

    /** Fila ya normalizada del CSV. */
    record Row(int line, LocalDate date, String barcode, int quantity, BigDecimal unitPrice) {
    }

    /** Resultado del parseo: filas válidas y errores por línea. */
    record ParseResult(List<Row> rows, List<SalesImportErrorDto> errors) {
    }

    /**
     * Importa el CSV sobre una sucursal. No es transaccional: cada llamada a
     * {@link StockService#registerSale} confirma por su cuenta, así una fila rechazada no anula las anteriores.
     */
    public SalesImportResultDto importSales(MultipartFile file, Long branchId) {
        Long tenantId = CurrentUser.tenantId();
        Long branch = branchAccessService.requireSingleBranch(branchId);
        ParseResult parsed = parse(file);

        Map<String, Product> products = loadProducts(tenantId, parsed.rows());
        List<SalesImportErrorDto> errors = new ArrayList<>(parsed.errors());

        // Agrupadas por fecha: cada día es una venta del historial.
        Map<LocalDate, List<Row>> byDate = new LinkedHashMap<>();
        for (Row row : parsed.rows()) {
            byDate.computeIfAbsent(row.date(), d -> new ArrayList<>()).add(row);
        }

        int imported = 0;
        int units = 0;
        BigDecimal total = BigDecimal.ZERO;
        List<String> batchRefs = new ArrayList<>();

        List<LocalDate> dates = new ArrayList<>(byDate.keySet());
        dates.sort(Comparator.naturalOrder());
        for (LocalDate date : dates) {
            List<Row> rows = byDate.get(date);
            rows.sort(Comparator.comparing((Row r) -> {
                Product product = products.get(r.barcode());
                return product == null ? Long.MAX_VALUE : product.getId();
            }).thenComparingInt(Row::line));
            String batchRef = BatchRefs.sale(clock);
            boolean used = false;
            Instant occurredAt = occurredAt(date);
            for (Row row : rows) {
                Product product = products.get(row.barcode());
                if (product == null) {
                    addError(errors, row.line(), "No encontramos ningún producto con el código " + row.barcode());
                    continue;
                }
                try {
                    SaleResult result = stockService.registerSale(new SaleCommand(tenantId, branch, product.getId(),
                            row.quantity(), row.unitPrice(), occurredAt, MovementSource.CSV, CurrentUser.id(),
                            batchRef));
                    imported++;
                    used = true;
                    units += row.quantity();
                    total = total.add(result.totalAmount() == null ? BigDecimal.ZERO : result.totalAmount());
                } catch (ApiException ex) {
                    addError(errors, row.line(), ex.getMessage());
                }
            }
            if (used) {
                batchRefs.add(batchRef);
            }
        }

        int skipped = parsed.rows().size() + parsed.errors().size() - imported;
        return new SalesImportResultDto(imported, Math.max(skipped, 0), units, total, batchRefs, errors);
    }

    /** Las ventas históricas se fechan al mediodía del día del archivo (nunca en el futuro). */
    private Instant occurredAt(LocalDate date) {
        Instant now = clock.instant();
        Instant midday = date.atTime(LocalTime.NOON).atZone(clock.getZone()).toInstant();
        return midday.isAfter(now) ? now : midday;
    }

    private Map<String, Product> loadProducts(Long tenantId, List<Row> rows) {
        LinkedHashSet<String> barcodes = new LinkedHashSet<>();
        rows.forEach(row -> barcodes.add(row.barcode()));
        Map<String, Product> products = new LinkedHashMap<>();
        if (barcodes.isEmpty()) {
            return products;
        }
        productRepository.findByTenantIdAndBarcodeIn(tenantId, barcodes)
                .forEach(product -> products.put(product.getBarcode(), product));
        return products;
    }

    private void addError(List<SalesImportErrorDto> errors, int line, String message) {
        if (errors.size() < MAX_ERRORS) {
            errors.add(new SalesImportErrorDto(line, message));
        }
    }

    // ------------------------------------------------------------------ parseo

    /** Lee y valida el archivo. Lanza 400 {@code INVALID_FILE} si no se puede procesar. */
    ParseResult parse(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException(ErrorCodes.INVALID_FILE, "Subí un archivo CSV con las ventas");
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new BadRequestException(ErrorCodes.INVALID_FILE, "El archivo no puede superar los 5 MB");
        }
        List<Row> rows = new ArrayList<>();
        List<SalesImportErrorDto> errors = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String first = reader.readLine();
            if (first == null) {
                throw new BadRequestException(ErrorCodes.INVALID_FILE, "El archivo está vacío");
            }
            first = stripBom(first);
            char separator = detectSeparator(first);
            int[] columns = headerColumns(first, separator);
            int lineNumber = 1;
            if (columns == null) {
                // Sin encabezado: el orden es fecha, código, cantidad, precio.
                columns = new int[] {0, 1, 2, 3};
                parseRow(first, separator, columns, lineNumber, rows, errors);
            }
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (rows.size() + errors.size() >= MAX_ROWS) {
                    addError(errors, lineNumber, "El archivo supera las " + MAX_ROWS + " filas: cortamos acá");
                    break;
                }
                if (line.isBlank()) {
                    continue;
                }
                parseRow(line, separator, columns, lineNumber, rows, errors);
            }
        } catch (IOException ex) {
            throw new BadRequestException(ErrorCodes.INVALID_FILE, "No pudimos leer el archivo: " + ex.getMessage());
        }
        if (rows.isEmpty() && errors.isEmpty()) {
            throw new BadRequestException(ErrorCodes.INVALID_FILE, "El archivo no tiene ninguna venta para importar");
        }
        return new ParseResult(rows, errors);
    }

    private void parseRow(String line, char separator, int[] columns, int lineNumber, List<Row> rows,
                          List<SalesImportErrorDto> errors) {
        String[] cells = splitLine(line, separator);
        String rawDate = cell(cells, columns[0]);
        String rawBarcode = cell(cells, columns[1]);
        String rawQuantity = cell(cells, columns[2]);
        String rawPrice = cell(cells, columns[3]);

        if (rawDate.isEmpty() && rawBarcode.isEmpty() && rawQuantity.isEmpty()) {
            return;
        }
        Optional<LocalDate> date = parseDate(rawDate);
        if (date.isEmpty()) {
            addError(errors, lineNumber, "Fecha inválida: «" + rawDate + "». Usá aaaa-mm-dd o dd/mm/aaaa");
            return;
        }
        if (date.get().isAfter(LocalDate.now(clock))) {
            addError(errors, lineNumber, "La fecha «" + rawDate + "» es futura");
            return;
        }
        String barcode = Barcodes.normalize(rawBarcode);
        if (barcode == null || barcode.isEmpty()) {
            addError(errors, lineNumber, "Falta el código de barras");
            return;
        }
        int quantity;
        try {
            quantity = Integer.parseInt(rawQuantity.strip());
        } catch (NumberFormatException ex) {
            addError(errors, lineNumber, "Cantidad inválida: «" + rawQuantity + "». Tiene que ser un número entero");
            return;
        }
        if (quantity <= 0) {
            addError(errors, lineNumber, "La cantidad tiene que ser mayor a cero");
            return;
        }
        BigDecimal unitPrice = null;
        if (!rawPrice.isEmpty()) {
            unitPrice = parseAmount(rawPrice);
            if (unitPrice == null) {
                addError(errors, lineNumber, "Precio inválido: «" + rawPrice + "». Usá 1234.50 o 1.234,50");
                return;
            }
            if (unitPrice.signum() < 0) {
                addError(errors, lineNumber, "El precio no puede ser negativo");
                return;
            }
        }
        rows.add(new Row(lineNumber, date.get(), barcode, quantity, unitPrice));
    }

    private static String stripBom(String line) {
        return line.isEmpty() || line.charAt(0) != '﻿' ? line : line.substring(1);
    }

    private static char detectSeparator(String header) {
        int semicolons = count(header, ';');
        int commas = count(header, ',');
        int tabs = count(header, '\t');
        if (semicolons >= commas && semicolons >= tabs && semicolons > 0) {
            return ';';
        }
        if (tabs > commas && tabs > 0) {
            return '\t';
        }
        return ',';
    }

    private static int count(String text, char ch) {
        int total = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == ch) {
                total++;
            }
        }
        return total;
    }

    /** Índices de las 4 columnas si la primera línea es un encabezado; null si es una fila de datos. */
    private static int[] headerColumns(String header, char separator) {
        String[] cells = splitLine(header, separator);
        int date = -1;
        int barcode = -1;
        int quantity = -1;
        int price = -1;
        for (int i = 0; i < cells.length; i++) {
            String name = normalizeHeader(cells[i]);
            switch (name) {
                case HEADER_DATE -> date = i;
                case HEADER_BARCODE, "codigodebarras", "codigo", "ean", "barcode" -> barcode = i;
                case HEADER_QUANTITY, "cant", "unidades" -> quantity = i;
                case HEADER_PRICE, "precio", "preciounitario", "pvp" -> price = i;
                default -> { }
            }
        }
        if (date < 0 || barcode < 0 || quantity < 0) {
            return null;
        }
        return new int[] {date, barcode, quantity, price < 0 ? cells.length : price};
    }

    private static String normalizeHeader(String raw) {
        String value = raw == null ? "" : raw.strip().toLowerCase();
        value = value.replace('á', 'a').replace('é', 'e').replace('í', 'i').replace('ó', 'o').replace('ú', 'u');
        return value.replace(" ", "").replace("\"", "");
    }

    /** Separa una línea respetando las comillas dobles de Excel. */
    static String[] splitLine(String line, char separator) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == separator && !quoted) {
                cells.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        cells.add(current.toString());
        return cells.toArray(String[]::new);
    }

    private static String cell(String[] cells, int index) {
        return index < 0 || index >= cells.length ? "" : cells[index].strip();
    }

    static Optional<LocalDate> parseDate(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty()) {
            return Optional.empty();
        }
        for (DateTimeFormatter formatter : List.of(ISO, DMY, DMY_DASH)) {
            try {
                return Optional.of(LocalDate.parse(value, formatter));
            } catch (DateTimeParseException ignored) {
                // se prueba el próximo formato
            }
        }
        return Optional.empty();
    }

    /** Acepta "1234.50", "1.234,50", "$ 1.234,50" y "1234". */
    static BigDecimal parseAmount(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.strip().replace("$", "").replace(" ", "").replace(" ", "");
        if (value.isEmpty()) {
            return null;
        }
        int lastComma = value.lastIndexOf(',');
        int lastDot = value.lastIndexOf('.');
        if (lastComma >= 0 && lastComma > lastDot) {
            value = value.replace(".", "").replace(',', '.');
        } else if (lastComma >= 0) {
            value = value.replace(",", "");
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
