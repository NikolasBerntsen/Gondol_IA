package com.gondolia.catalog.dto;

/**
 * Autocompletado de un producto por código de barras (SPEC §6.3): primero el catálogo de referencia de productos
 * argentinos que viene con el sistema ({@code source = REFERENCE_CATALOG}) y, si no lo tiene, Open Food Facts
 * ({@code source = OPEN_FOOD_FACTS}). Nunca falla el request: si ninguno conoce el código, el servicio está
 * deshabilitado o no responde a tiempo, devuelve {@code found = false}.
 */
public record BarcodeLookupResponse(boolean found, String source, String barcode, String name, String brand,
                                    String quantity, String categoryHint, String imageUrl) {

    public static final String SOURCE_REFERENCE_CATALOG = "REFERENCE_CATALOG";
    public static final String SOURCE_OPEN_FOOD_FACTS = "OPEN_FOOD_FACTS";

    public static BarcodeLookupResponse notFound(String barcode) {
        return new BarcodeLookupResponse(false, null, barcode, null, null, null, null, null);
    }
}
