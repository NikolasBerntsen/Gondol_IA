package com.gondolia.imports.parse;

import com.gondolia.domain.inventory.ProductUnit;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Valores tipados de una fila ya normalizada. Los campos en {@code null} no vinieron en el archivo (o su celda
 * estaba vacía); {@code messages} tiene los problemas de formato detectados al parsear.
 *
 * @param unitUnknown {@code true} si la unidad del archivo no se reconoció (se usa {@code UNIDAD})
 */
public record ParsedRow(String barcode, String name, String brand, String category, String supplier, ProductUnit unit,
                        boolean unitUnknown, BigDecimal costPrice, BigDecimal salePrice, Integer minStock,
                        Boolean perishable, String description, Integer quantity, String lotNumber,
                        LocalDate expiryDate, LocalDate receivedAt, String branch,
                        List<ImportRowParser.ParseIssue> messages) {

    public ParsedRow {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    public boolean hasErrors() {
        return messages.stream().anyMatch(ImportRowParser.ParseIssue::error);
    }

    /** Unidades a cargar como lote inicial (0 si la fila no trae cantidad). */
    public int quantityOrZero() {
        return quantity == null ? 0 : quantity;
    }
}
