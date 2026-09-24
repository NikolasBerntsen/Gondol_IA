package com.gondolia.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gondolia.catalog.dto.BarcodeLookupResponse;
import com.gondolia.common.util.Barcodes;
import java.io.StringReader;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Catálogo de referencia de productos argentinos ({@code catalog/productos-argentina.csv}): el archivo publicado carga
 * entero, sin códigos repetidos ni dígitos verificadores inválidos, y responde como el autocompletado espera.
 */
class ReferenceCatalogTest {

    private static final String HEADER = "codigo;nombre;marca;contenido;categoria;fuente\n";
    private static final List<String> SOURCES = List.of("OFF", "OPF", "OBF");

    private final ReferenceCatalog catalog = new ReferenceCatalog();

    @Test
    void publishedCatalogLoadsEveryRow() {
        assertThat(catalog.rejected()).isEmpty();
        assertThat(catalog.entries()).hasSizeGreaterThanOrEqualTo(200);

        Set<String> barcodes = new HashSet<>();
        for (ReferenceCatalog.Entry entry : catalog.entries()) {
            assertThat(Barcodes.isValidGtin(entry.barcode())).as(entry.barcode()).isTrue();
            assertThat(barcodes.add(entry.barcode())).as("código repetido: " + entry.barcode()).isTrue();
            assertThat(entry.name()).as(entry.barcode()).isNotBlank();
            assertThat(entry.brand()).as(entry.barcode()).isNotBlank();
            assertThat(entry.category()).as(entry.barcode()).isNotBlank();
            assertThat(entry.source()).as(entry.barcode()).isIn(SOURCES);
        }
        // Productos de Argentina: casi todos con el prefijo GS1 779.
        long argentine = barcodes.stream().filter(barcode -> barcode.startsWith("779")).count();
        assertThat(argentine).isGreaterThan(barcodes.size() * 9L / 10);
    }

    @Test
    void knownBarcodeReturnsTheCatalogData() {
        assertThat(catalog.find("7793704000911")).contains(new BarcodeLookupResponse(true,
                BarcodeLookupResponse.SOURCE_REFERENCE_CATALOG, "7793704000911", "Yerba mate Playadito 500 g",
                "Playadito", "500 g", "Almacén", null));
        assertThat(catalog.find(" 779 3704 000911 ")).get()
                .extracting(BarcodeLookupResponse::barcode).isEqualTo("7793704000911");
    }

    @Test
    void unknownOrEmptyBarcodeIsNotFound() {
        assertThat(catalog.find("7791234500017")).isEmpty();  // la sopa ficticia del mundo demo
        assertThat(catalog.find("   ")).isEmpty();
        assertThat(catalog.find(null)).isEmpty();
    }

    @Test
    void upcAAndItsEan13FormAreTheSameProduct() {
        ReferenceCatalog upc = new ReferenceCatalog(new StringReader(HEADER
                + "036000291452;Producto de prueba 1 kg;Marca;1 kg;Almacén;OFF\n"));

        assertThat(upc.find("036000291452")).get().extracting(BarcodeLookupResponse::name)
                .isEqualTo("Producto de prueba 1 kg");
        assertThat(upc.find("0036000291452")).get().extracting(BarcodeLookupResponse::barcode)
                .isEqualTo("0036000291452");
    }

    @Test
    void badRowsAreSkippedWithTheReason() {
        ReferenceCatalog partial = new ReferenceCatalog(new StringReader("""
                # comentario de cabecera
                codigo;nombre;marca;contenido;categoria;fuente
                7793704000911;Yerba mate Playadito 500 g;Playadito;500 g;Almacén;OFF
                7793704000912;Dígito verificador mal;Marca;1 kg;Almacén;OFF

                7793704000911;Repetido;Playadito;500 g;Almacén;OFF
                7790895000782; ;Coca-Cola;500 ml;Bebidas;OFF
                7790895000782;Faltan columnas
                7790895000430;Gaseosa Coca-Cola sabor original 1,5 L;Coca-Cola;;;OFF
                """));

        assertThat(partial.entries()).extracting(ReferenceCatalog.Entry::barcode)
                .containsExactly("7793704000911", "7790895000430");
        assertThat(partial.find("7790895000430")).get().satisfies(found -> {
            assertThat(found.quantity()).isNull();
            assertThat(found.categoryHint()).isNull();
        });
        assertThat(partial.rejected()).containsExactly(
                "línea 4: 7793704000912 no es un EAN-13/EAN-8/UPC-A con dígito verificador válido",
                "línea 6: 7793704000911 está repetido",
                "línea 7: 7790895000782 no tiene nombre",
                "línea 8: tiene 2 columnas en lugar de 6");
    }

    @Test
    void unexpectedColumnsFailFast() {
        assertThatThrownBy(() -> new ReferenceCatalog(new StringReader("codigo;nombre\n7793704000911;Yerba\n")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Columnas inesperadas");
    }
}
