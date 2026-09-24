package com.gondolia.catalog;

import com.gondolia.catalog.dto.BarcodeLookupResponse;
import com.gondolia.common.util.Barcodes;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Catálogo de referencia de productos argentinos reales para autocompletar un producto nuevo por su código de barras
 * (SPEC §6.3). Viene empaquetado en {@code catalog/productos-argentina.csv}: datos de Open Food Facts (ODbL) revisados
 * y normalizados con {@code backend/scripts/catalogo_argentina.py}.
 * <p>
 * El autocompletado lo consulta antes que a Open Food Facts: responde al instante, sin internet y aunque
 * {@code app.openfoodfacts-enabled} esté en {@code false}. Una fila mal armada (código sin dígito verificador válido,
 * repetido o sin nombre) se descarta con un aviso en el log en lugar de impedir el arranque; las pruebas exigen que el
 * archivo publicado no tenga ninguna.
 */
@Slf4j
@Component
public class ReferenceCatalog {

    static final String RESOURCE = "catalog/productos-argentina.csv";
    static final List<String> COLUMNS = List.of("codigo", "nombre", "marca", "contenido", "categoria", "fuente");

    /** Producto del catálogo tal como figura en el archivo. */
    public record Entry(String barcode, String name, String brand, String quantity, String category, String source) {
    }

    private final Map<String, Entry> byBarcode;
    private final List<String> rejected;

    public ReferenceCatalog() {
        this(openResource());
    }

    ReferenceCatalog(Reader reader) {
        Map<String, Entry> entries = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        read(reader, entries, problems);
        this.byBarcode = Collections.unmodifiableMap(entries);
        this.rejected = List.copyOf(problems);
        problems.forEach(problem -> log.warn("Catálogo de referencia: {}", problem));
        log.info("Catálogo de referencia de productos: {} códigos", entries.size());
    }

    /**
     * Datos del producto con ese código (se aceptan espacios y el UPC-A de 12 dígitos del mismo EAN-13), con
     * {@code source = REFERENCE_CATALOG}; vacío si no está en el catálogo.
     */
    public Optional<BarcodeLookupResponse> find(String barcode) {
        String normalized = Barcodes.normalize(barcode);
        Entry entry = normalized == null ? null : byBarcode.get(key(normalized));
        if (entry == null) {
            return Optional.empty();
        }
        return Optional.of(new BarcodeLookupResponse(true, BarcodeLookupResponse.SOURCE_REFERENCE_CATALOG,
                normalized, entry.name(), entry.brand(), entry.quantity(), entry.category(), null));
    }

    /** Productos cargados, en el orden del archivo. */
    public List<Entry> entries() {
        return List.copyOf(byBarcode.values());
    }

    /** Filas descartadas al cargar, con el motivo (vacío si el archivo está bien). */
    public List<String> rejected() {
        return rejected;
    }

    private static void read(Reader source, Map<String, Entry> entries, List<String> problems) {
        try (BufferedReader reader = new BufferedReader(source)) {
            boolean header = true;
            int lineNumber = 0;
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                lineNumber++;
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                List<String> fields = List.of(line.split(";", -1));
                if (header) {
                    if (!fields.equals(COLUMNS)) {
                        throw new IllegalStateException("Columnas inesperadas en " + RESOURCE + ": " + line);
                    }
                    header = false;
                    continue;
                }
                String problem = add(fields, entries);
                if (problem != null) {
                    problems.add("línea " + lineNumber + ": " + problem);
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("No se pudo leer " + RESOURCE, ex);
        }
    }

    /** Agrega la fila; devuelve el motivo si la descarta. */
    private static String add(List<String> fields, Map<String, Entry> entries) {
        if (fields.size() != COLUMNS.size()) {
            return "tiene " + fields.size() + " columnas en lugar de " + COLUMNS.size();
        }
        String barcode = fields.get(0).strip();
        if (!Barcodes.isValidGtin(barcode)) {
            return barcode + " no es un EAN-13/EAN-8/UPC-A con dígito verificador válido";
        }
        String name = blankToNull(fields.get(1));
        if (name == null) {
            return barcode + " no tiene nombre";
        }
        Entry entry = new Entry(barcode, name, blankToNull(fields.get(2)), blankToNull(fields.get(3)),
                blankToNull(fields.get(4)), blankToNull(fields.get(5)));
        if (entries.putIfAbsent(key(barcode), entry) != null) {
            return barcode + " está repetido";
        }
        return null;
    }

    /** El mismo producto en UPC-A (12 dígitos) y en EAN-13 (con un 0 adelante) comparte la clave. */
    private static String key(String barcode) {
        return barcode.length() == 12 && Barcodes.isValidGtin(barcode) ? "0" + barcode : barcode;
    }

    private static String blankToNull(String value) {
        String stripped = value == null ? "" : value.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static Reader openResource() {
        InputStream stream = ReferenceCatalog.class.getClassLoader().getResourceAsStream(RESOURCE);
        if (stream == null) {
            throw new IllegalStateException("Falta el recurso " + RESOURCE);
        }
        return new InputStreamReader(stream, StandardCharsets.UTF_8);
    }
}
