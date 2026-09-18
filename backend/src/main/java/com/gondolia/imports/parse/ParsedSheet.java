package com.gondolia.imports.parse;

import com.gondolia.domain.imports.ImportFileFormat;
import java.util.List;

/**
 * Contenido útil de un archivo importado: encabezados (primera fila no vacía) y filas de datos ya recortadas,
 * sin las filas totalmente vacías (SPEC §16.2).
 *
 * @param format     formato detectado por la extensión y el contenido
 * @param sheetNames hojas del libro (vacío en CSV)
 * @param sheetName  hoja leída ({@code null} en CSV)
 * @param headers    encabezados únicos, en el orden del archivo
 * @param rows       cada fila alineada a {@code headers} (se completa con vacíos si la fila es más corta)
 * @param delimiter  separador detectado en CSV ({@code null} en Excel)
 * @param charset    codificación detectada en CSV ({@code null} en Excel)
 */
public record ParsedSheet(ImportFileFormat format, List<String> sheetNames, String sheetName, List<String> headers,
                          List<List<String>> rows, String delimiter, String charset) {

    public ParsedSheet {
        sheetNames = sheetNames == null ? List.of() : List.copyOf(sheetNames);
        headers = headers == null ? List.of() : List.copyOf(headers);
        rows = rows == null ? List.of() : List.copyOf(rows);
    }
}
