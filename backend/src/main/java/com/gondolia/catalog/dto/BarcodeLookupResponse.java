package com.gondolia.catalog.dto;

/**
 * Autocompletado de un producto por código de barras contra una base pública (Open Food Facts, SPEC §6.3).
 * Nunca falla el request: si el servicio está deshabilitado, no responde a tiempo o el código no existe,
 * devuelve {@code found = false}.
 */
public record BarcodeLookupResponse(boolean found, String source, String barcode, String name, String brand,
                                    String quantity, String categoryHint, String imageUrl) {

    public static final String SOURCE_OPEN_FOOD_FACTS = "OPEN_FOOD_FACTS";

    public static BarcodeLookupResponse notFound(String barcode) {
        return new BarcodeLookupResponse(false, null, barcode, null, null, null, null, null);
    }
}
