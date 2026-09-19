package com.gondolia.imports.parse;

import com.gondolia.common.util.Barcodes;
import com.gondolia.domain.inventory.Lot;
import com.gondolia.domain.inventory.ProductUnit;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Convierte el texto editable de una fila ({@code data}) en valores tipados, con los errores de formato de
 * SPEC §16.2 (números es-AR, fechas DMY, booleanos, seriales de Excel, textos demasiado largos).
 */
public final class ImportRowParser {

    /** Problema detectado al parsear una celda. */
    public record ParseIssue(ImportField field, boolean error, String message) {
    }

    private static final int MAX_DESCRIPTION = 2_000;

    private ImportRowParser() {
    }

    public static ParsedRow parse(Map<String, String> data, ImportValues.DateOrder order) {
        List<ParseIssue> issues = new ArrayList<>();

        String barcode = Barcodes.normalize(value(data, ImportField.BARCODE));
        if (barcode != null && barcode.length() > Barcodes.MAX_LENGTH) {
            issues.add(error(ImportField.BARCODE,
                    "El código no puede tener más de " + Barcodes.MAX_LENGTH + " caracteres."));
            barcode = barcode.substring(0, Barcodes.MAX_LENGTH);
        }

        String name = limited(issues, ImportField.NAME, value(data, ImportField.NAME), 200, "El nombre");
        String brand = limited(issues, ImportField.BRAND, value(data, ImportField.BRAND), 100, "La marca");
        String category = limited(issues, ImportField.CATEGORY, value(data, ImportField.CATEGORY), 100, "La categoría");
        String supplier = limited(issues, ImportField.SUPPLIER, value(data, ImportField.SUPPLIER), 150, "El proveedor");
        String description = limited(issues, ImportField.DESCRIPTION, value(data, ImportField.DESCRIPTION),
                MAX_DESCRIPTION, "La descripción");
        String lotNumber = limited(issues, ImportField.LOT_NUMBER, value(data, ImportField.LOT_NUMBER),
                Lot.MAX_LOT_NUMBER_LENGTH, "El número de lote");
        String branch = limited(issues, ImportField.BRANCH, value(data, ImportField.BRANCH), 100, "La sucursal");

        String rawUnit = value(data, ImportField.UNIT);
        ProductUnit unit = null;
        boolean unitUnknown = false;
        if (rawUnit != null) {
            Optional<ProductUnit> parsed = unit(rawUnit);
            if (parsed.isPresent()) {
                unit = parsed.get();
            } else {
                unitUnknown = true;
                unit = ProductUnit.UNIDAD;
            }
        }

        BigDecimal costPrice = money(issues, data, ImportField.COST_PRICE, "El precio de costo");
        BigDecimal salePrice = money(issues, data, ImportField.SALE_PRICE, "El precio de venta");
        Integer minStock = integer(issues, data, ImportField.MIN_STOCK, "El stock mínimo", 0, 1_000_000);
        Integer quantity = integer(issues, data, ImportField.QUANTITY, "La cantidad", 0, 10_000_000);

        Boolean perishable = null;
        String rawPerishable = value(data, ImportField.PERISHABLE);
        if (rawPerishable != null) {
            Optional<Boolean> parsed = ImportValues.bool(rawPerishable);
            if (parsed.isPresent()) {
                perishable = parsed.get();
            } else {
                issues.add(warning(ImportField.PERISHABLE,
                        "No entendimos «" + rawPerishable + "»: se toma como perecedero. Usá sí o no."));
                perishable = true;
            }
        }

        LocalDate expiryDate = date(issues, data, ImportField.EXPIRY_DATE, order, "El vencimiento");
        LocalDate receivedAt = date(issues, data, ImportField.RECEIVED_AT, order, "La fecha de ingreso");

        return new ParsedRow(barcode, name, brand, category, supplier, unit, unitUnknown, costPrice, salePrice,
                minStock, perishable, description, quantity, lotNumber, expiryDate, receivedAt, branch, issues);
    }

    /** Unidad del producto por nombre, abreviatura o sinónimo. */
    public static Optional<ProductUnit> unit(String raw) {
        String slug = ImportValues.slug(raw);
        if (slug.isEmpty()) {
            return Optional.empty();
        }
        return switch (slug) {
            case "unidad", "unidades", "u", "un", "uni", "c u", "cu", "ud" -> Optional.of(ProductUnit.UNIDAD);
            case "kg", "kilo", "kilos", "kilogramo", "kilogramos", "k", "grs", "gramos" -> Optional.of(ProductUnit.KG);
            case "litro", "litros", "l", "lt", "lts", "cc", "ml" -> Optional.of(ProductUnit.LITRO);
            case "paquete", "paquetes", "paq", "pack", "pq", "bolsa" -> Optional.of(ProductUnit.PAQUETE);
            case "caja", "cajas", "cj", "bulto", "bultos" -> Optional.of(ProductUnit.CAJA);
            default -> Optional.empty();
        };
    }

    private static String value(Map<String, String> data, ImportField field) {
        return data == null ? null : ImportValues.text(data.get(field.key()));
    }

    private static String limited(List<ParseIssue> issues, ImportField field, String value, int max, String subject) {
        if (value == null) {
            return null;
        }
        if (value.length() > max) {
            issues.add(error(field, subject + " no puede tener más de " + max + " caracteres (tiene "
                    + value.length() + ")."));
            return value.substring(0, max);
        }
        return value;
    }

    private static BigDecimal money(List<ParseIssue> issues, Map<String, String> data, ImportField field,
                                    String subject) {
        String raw = value(data, field);
        if (raw == null) {
            return null;
        }
        Optional<BigDecimal> parsed = ImportValues.number(raw);
        if (parsed.isEmpty()) {
            issues.add(error(field, subject + " no es un número: «" + raw + "»."));
            return null;
        }
        BigDecimal number = parsed.get();
        if (number.signum() < 0) {
            issues.add(error(field, subject + " no puede ser negativo: «" + raw + "»."));
            return null;
        }
        if (number.compareTo(new BigDecimal("9999999999")) > 0) {
            issues.add(error(field, subject + " es demasiado grande: «" + raw + "»."));
            return null;
        }
        return number.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static Integer integer(List<ParseIssue> issues, Map<String, String> data, ImportField field,
                                   String subject, int min, int max) {
        String raw = value(data, field);
        if (raw == null) {
            return null;
        }
        Optional<BigDecimal> parsed = ImportValues.number(raw);
        if (parsed.isEmpty()) {
            issues.add(error(field, subject + " no es un número: «" + raw + "»."));
            return null;
        }
        BigDecimal number = parsed.get();
        if (number.signum() < 0) {
            issues.add(error(field, subject + " no puede ser negativa: «" + raw + "»."));
            return null;
        }
        if (!ImportValues.isInteger(raw)) {
            issues.add(error(field, subject + " tiene que ser un número entero: «" + raw + "»."));
            return null;
        }
        if (number.compareTo(BigDecimal.valueOf(max)) > 0 || number.compareTo(BigDecimal.valueOf(min)) < 0) {
            issues.add(error(field, subject + " tiene que estar entre " + min + " y " + max + ": «" + raw + "»."));
            return null;
        }
        return number.intValueExact();
    }

    private static LocalDate date(List<ParseIssue> issues, Map<String, String> data, ImportField field,
                                  ImportValues.DateOrder order, String subject) {
        String raw = value(data, field);
        if (raw == null) {
            return null;
        }
        Optional<LocalDate> parsed = ImportValues.date(raw, order);
        if (parsed.isEmpty()) {
            issues.add(error(field, subject + " no es una fecha válida: «" + raw + "». Usá dd/mm/aaaa."));
            return null;
        }
        return parsed.get();
    }

    private static ParseIssue error(ImportField field, String message) {
        return new ParseIssue(field, true, message);
    }

    private static ParseIssue warning(ImportField field, String message) {
        return new ParseIssue(field, false, message);
    }
}
