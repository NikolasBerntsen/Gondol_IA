package com.gondolia.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.gondolia.imports.parse.HeaderMatching;
import com.gondolia.imports.parse.ImportField;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Auto-mapeo de encabezados por sinónimos (SPEC §16.1). */
class HeaderMatchingTest {

    @Test
    void mapsTheHeadersOfARealSpreadsheet() {
        Map<ImportField, String> mapping = HeaderMatching.suggest(List.of(
                "Cod. Barra", "Descripcion", "Marca", "Rubro", "Proveedor", "U. Medida", "Precio Costo",
                "Precio Venta", "Stock Min", "Perecedero", "Cantidad", "Nro Lote", "Vto", "Fecha Ingreso",
                "Sucursal"));

        assertThat(mapping)
                .containsEntry(ImportField.BARCODE, "Cod. Barra")
                // SPEC §16.1: «descripción» es sinónimo de Nombre, no de Descripción.
                .containsEntry(ImportField.NAME, "Descripcion")
                .containsEntry(ImportField.BRAND, "Marca")
                .containsEntry(ImportField.CATEGORY, "Rubro")
                .containsEntry(ImportField.SUPPLIER, "Proveedor")
                .containsEntry(ImportField.UNIT, "U. Medida")
                .containsEntry(ImportField.COST_PRICE, "Precio Costo")
                .containsEntry(ImportField.SALE_PRICE, "Precio Venta")
                .containsEntry(ImportField.MIN_STOCK, "Stock Min")
                .containsEntry(ImportField.PERISHABLE, "Perecedero")
                .containsEntry(ImportField.QUANTITY, "Cantidad")
                .containsEntry(ImportField.LOT_NUMBER, "Nro Lote")
                .containsEntry(ImportField.EXPIRY_DATE, "Vto")
                .containsEntry(ImportField.RECEIVED_AT, "Fecha Ingreso")
                .containsEntry(ImportField.BRANCH, "Sucursal")
                .doesNotContainKey(ImportField.DESCRIPTION);
    }

    @Test
    void prefersTheExactHeaderWhenBothColumnsExist() {
        Map<ImportField, String> mapping = HeaderMatching.suggest(
                List.of("Descripción", "Nombre", "Precio", "Código de barras"));

        assertThat(mapping)
                .containsEntry(ImportField.NAME, "Nombre")
                .containsEntry(ImportField.DESCRIPTION, "Descripción")
                .containsEntry(ImportField.SALE_PRICE, "Precio")
                .containsEntry(ImportField.BARCODE, "Código de barras");
    }

    @Test
    void ignoresAccentsCaseAndPunctuation() {
        assertThat(HeaderMatching.suggest(List.of("CÓDIGO DE BARRAS", "artículo", "stock mínimo", "N° Lote")))
                .containsEntry(ImportField.BARCODE, "CÓDIGO DE BARRAS")
                .containsEntry(ImportField.NAME, "artículo")
                .containsEntry(ImportField.MIN_STOCK, "stock mínimo")
                .containsEntry(ImportField.LOT_NUMBER, "N° Lote");
    }

    @Test
    void usesEachHeaderOnlyOnce() {
        Map<ImportField, String> mapping = HeaderMatching.suggest(List.of("Producto", "Nombre"));

        assertThat(mapping.values()).doesNotHaveDuplicates();
        assertThat(mapping).containsEntry(ImportField.NAME, "Nombre");
    }

    @Test
    void leavesUnknownHeadersUnmapped() {
        assertThat(HeaderMatching.suggest(List.of("Columna rara", "xyz", "")).values())
                .doesNotContain("xyz", "");
    }

    @Test
    void mapsTheTemplateLabels() {
        List<String> labels = java.util.Arrays.stream(ImportField.values()).map(ImportField::label).toList();
        Map<ImportField, String> mapping = HeaderMatching.suggest(labels);

        for (ImportField field : ImportField.values()) {
            assertThat(mapping).as("campo %s", field.key()).containsEntry(field, field.label());
        }
    }
}
