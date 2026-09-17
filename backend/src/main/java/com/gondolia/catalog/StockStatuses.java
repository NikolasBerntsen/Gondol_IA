package com.gondolia.catalog;

import java.util.Collection;

/**
 * Estado de stock de un producto en una sucursal (SPEC §4.2): {@code OUT} sin stock vendible, {@code LOW} cuando el
 * vendible no supera el mínimo del producto y {@code OK} el resto. En un alcance de varias sucursales se informa el
 * <b>peor</b> estado.
 */
public final class StockStatuses {

    public static final String OK = "OK";
    public static final String LOW = "LOW";
    public static final String OUT = "OUT";

    private StockStatuses() {
    }

    public static String of(int sellableStock, int minStock) {
        if (sellableStock <= 0) {
            return OUT;
        }
        return sellableStock <= minStock ? LOW : OK;
    }

    /** El peor estado de la lista ({@code OUT} &gt; {@code LOW} &gt; {@code OK}); {@code OK} si está vacía. */
    public static String worst(Collection<String> statuses) {
        boolean low = false;
        for (String status : statuses) {
            if (OUT.equals(status)) {
                return OUT;
            }
            low |= LOW.equals(status);
        }
        return low ? LOW : OK;
    }

    /** Gravedad del estado para ordenar listados: {@code OUT} 2, {@code LOW} 1, {@code OK} 0. */
    public static int rank(String status) {
        return switch (status) {
            case OUT -> 2;
            case LOW -> 1;
            default -> 0;
        };
    }
}
