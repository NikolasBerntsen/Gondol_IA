package com.gondolia.imports.parse;

import java.util.List;

/**
 * Campo importable de un producto (SPEC §16.1). El {@code key} es la clave que usa el mapeo de columnas y el JSON
 * {@code data} de cada fila; {@code label} es el encabezado que lleva la plantilla y la exportación del catálogo.
 */
public enum ImportField {

    BARCODE("barcode", "Código de barras", ImportFieldType.TEXT, false,
            "EAN-13, EAN-8, UPC-A o código interno. Si se repite en varias filas, es el mismo producto con varios lotes.",
            List.of("codigo", "cod", "ean", "codigo de barras", "cod barra", "cod_barra", "barcode", "codigo barras",
                    "sku", "upc")),

    NAME("name", "Nombre", ImportFieldType.TEXT, true,
            "Nombre del producto. Es el único campo obligatorio.",
            List.of("nombre", "descripcion", "producto", "articulo", "detalle", "nombre del producto")),

    BRAND("brand", "Marca", ImportFieldType.TEXT, false,
            "Marca comercial.",
            List.of("marca", "fabricante")),

    CATEGORY("category", "Categoría", ImportFieldType.TEXT, false,
            "Nombre de la categoría. Si no existe, se crea (opción «Crear categorías nuevas»).",
            List.of("categoria", "rubro", "familia", "seccion", "grupo")),

    SUPPLIER("supplier", "Proveedor", ImportFieldType.TEXT, false,
            "Nombre del proveedor. Si no existe, se crea (opción «Crear proveedores nuevos»).",
            List.of("proveedor", "distribuidor", "fabricante proveedor")),

    UNIT("unit", "Unidad", ImportFieldType.UNIT, false,
            "UNIDAD, KG, LITRO, PAQUETE o CAJA. Si no se reconoce, se usa UNIDAD.",
            List.of("unidad", "u medida", "unidad de medida", "medida", "um")),

    COST_PRICE("costPrice", "Precio de costo", ImportFieldType.NUMBER, false,
            "Costo unitario. Acepta «$ 1.234,50» o «1234.5».",
            List.of("costo", "precio costo", "precio de costo", "precio compra", "precio de compra", "costo unitario")),

    SALE_PRICE("salePrice", "Precio de venta", ImportFieldType.NUMBER, false,
            "Precio de venta al público. Acepta «$ 1.234,50» o «1234.5».",
            List.of("precio", "pvp", "precio venta", "precio de venta", "precio publico", "precio de venta al publico",
                    "venta")),

    MIN_STOCK("minStock", "Stock mínimo", ImportFieldType.INTEGER, false,
            "Cantidad mínima por sucursal: debajo de ese número se avisa «stock bajo».",
            List.of("minimo", "stock minimo", "stock min", "min", "punto de reposicion")),

    PERISHABLE("perishable", "Perecedero", ImportFieldType.BOOLEAN, false,
            "Si vence: sí/no, true/false, 1/0 o X.",
            List.of("perecedero", "perecible", "vence", "con vencimiento")),

    DESCRIPTION("description", "Descripción", ImportFieldType.TEXT, false,
            "Texto libre con detalles del producto.",
            List.of("descripcion larga", "detalle largo", "observaciones", "notas", "comentarios")),

    QUANTITY("quantity", "Cantidad en stock", ImportFieldType.INTEGER, false,
            "Unidades a cargar como lote inicial en la sucursal de la fila.",
            List.of("stock", "cantidad", "existencia", "existencias", "unidades", "cant", "stock actual")),

    LOT_NUMBER("lotNumber", "Número de lote", ImportFieldType.TEXT, false,
            "Identificación del lote del proveedor.",
            List.of("lote", "nro lote", "n lote", "numero de lote", "partida", "batch")),

    EXPIRY_DATE("expiryDate", "Fecha de vencimiento", ImportFieldType.DATE, false,
            "Vencimiento del lote (dd/mm/aaaa).",
            List.of("vencimiento", "vto", "fecha venc", "fecha de vencimiento", "fecha vto", "caducidad", "vence el")),

    RECEIVED_AT("receivedAt", "Fecha de ingreso", ImportFieldType.DATE, false,
            "Cuándo entró la mercadería: preserva el orden FIFO. Si falta, se usa el momento de la importación.",
            List.of("fecha ingreso", "ingreso", "fecha compra", "fecha de ingreso", "fecha de compra", "alta",
                    "fecha alta")),

    BRANCH("branch", "Sucursal", ImportFieldType.BRANCH, false,
            "Nombre o código de la sucursal donde entra el stock. Si falta, se usa la sucursal por defecto.",
            List.of("sucursal", "local", "tienda", "deposito", "punto de venta"));

    private final String key;
    private final String label;
    private final ImportFieldType type;
    private final boolean required;
    private final String description;
    private final List<String> synonyms;

    ImportField(String key, String label, ImportFieldType type, boolean required, String description,
                List<String> synonyms) {
        this.key = key;
        this.label = label;
        this.type = type;
        this.required = required;
        this.description = description;
        this.synonyms = List.copyOf(synonyms);
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    public ImportFieldType type() {
        return type;
    }

    public boolean required() {
        return required;
    }

    public String description() {
        return description;
    }

    public List<String> synonyms() {
        return synonyms;
    }

    /** Campos que describen el producto (los que se crean o actualizan en el catálogo). */
    public boolean isProductField() {
        return switch (this) {
            case QUANTITY, LOT_NUMBER, EXPIRY_DATE, RECEIVED_AT, BRANCH -> false;
            default -> true;
        };
    }

    /** Campos del lote inicial (solo se usan si {@code importStock} está activo). */
    public boolean isStockField() {
        return !isProductField();
    }

    public static java.util.Optional<ImportField> byKey(String key) {
        if (key == null) {
            return java.util.Optional.empty();
        }
        for (ImportField field : values()) {
            if (field.key.equalsIgnoreCase(key.trim())) {
                return java.util.Optional.of(field);
            }
        }
        return java.util.Optional.empty();
    }
}
