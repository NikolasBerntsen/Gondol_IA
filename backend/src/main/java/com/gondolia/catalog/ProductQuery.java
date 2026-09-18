package com.gondolia.catalog;

import com.gondolia.common.error.BadRequestException;
import com.gondolia.common.error.ErrorCodes;
import java.util.Locale;
import java.util.Set;

/**
 * Filtros del inventario (SPEC §6.3).
 *
 * @param q           texto libre: busca en nombre, marca y código de barras
 * @param categoryId  categoría exacta (null = todas)
 * @param stockStatus {@code ALL}, {@code OK}, {@code LOW}, {@code OUT} o {@code EXPIRING}
 * @param active      {@code true} solo activos, {@code false} solo dados de baja, {@code null} todos
 * @param page        página 0-based
 * @param size        tamaño de página (1..100)
 * @param sort        {@code campo,asc|desc}
 */
public record ProductQuery(String q, Long categoryId, String stockStatus, Boolean active, int page, int size,
                           String sort) {

    public static final String STATUS_ALL = "ALL";
    public static final String STATUS_EXPIRING = "EXPIRING";

    private static final Set<String> STATUSES = Set.of(STATUS_ALL, StockStatuses.OK, StockStatuses.LOW,
            StockStatuses.OUT, STATUS_EXPIRING);

    /** Campos por los que se puede ordenar el inventario. */
    public static final Set<String> SORT_FIELDS = Set.of("name", "barcode", "brand", "categoryName", "salePrice",
            "costPrice", "minStock", "sellableStock", "nextExpiryDate", "stockStatus", "createdAt");

    public ProductQuery {
        q = q == null || q.isBlank() ? null : q.trim();
        stockStatus = stockStatus == null || stockStatus.isBlank()
                ? STATUS_ALL
                : stockStatus.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(stockStatus)) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR,
                    "El filtro de stock tiene que ser ALL, OK, LOW, OUT o EXPIRING.");
        }
        page = Math.max(page, 0);
        size = size <= 0 ? 20 : Math.min(size, 100);
        sort = sort == null || sort.isBlank() ? "name,asc" : sort.trim();
    }

    public boolean filtersStock() {
        return !STATUS_ALL.equals(stockStatus);
    }

    /** Campo de ordenamiento validado. */
    public String sortField() {
        String field = sort.split(",", 2)[0].trim();
        if (!SORT_FIELDS.contains(field)) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR,
                    "No se puede ordenar por \"" + field + "\".");
        }
        return field;
    }

    public boolean sortDescending() {
        String[] parts = sort.split(",", 2);
        return parts.length > 1 && parts[1].trim().equalsIgnoreCase("desc");
    }
}
