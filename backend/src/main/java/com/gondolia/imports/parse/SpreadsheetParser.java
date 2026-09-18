package com.gondolia.imports.parse;

import com.gondolia.common.error.ApiException;
import com.gondolia.common.error.BadRequestException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.domain.imports.ImportFileFormat;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * Lectura de la planilla del cliente (SPEC §16.2): XLSX/XLS con Apache POI (fechas nativas o seriales, fórmulas
 * evaluadas) y CSV con Apache Commons CSV, detectando el separador ({@code ;}, {@code ,} o tabulación) y la
 * codificación (UTF-8 con o sin BOM, o Windows-1252, que es como exporta el Excel en español).
 */
@Component
public class SpreadsheetParser {

    /** Tamaño máximo del archivo (SPEC §16.2). */
    public static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    /** Cantidad máxima de filas de datos (SPEC §16.2). */
    public static final int MAX_ROWS = 10_000;
    /** Cantidad máxima de columnas leídas de una fila. */
    private static final int MAX_COLUMNS = 80;

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final char[] CANDIDATE_DELIMITERS = {';', ',', '\t', '|'};
    /**
     * {@code true} si Apache Commons CSV puede arrancar. {@code commons-csv 1.12.0} necesita
     * {@code commons-io 2.17+} y el pom de la fundación trae la 2.16.1 (la que fija Apache POI 5.3.0): mientras
     * eso no cambie, el CSV se lee con {@link SimpleCsvReader}.
     */
    private static final boolean COMMONS_CSV_AVAILABLE = commonsCsvAvailable();

    private static boolean commonsCsvAvailable() {
        try {
            Class.forName("org.apache.commons.io.input.UnsynchronizedBufferedReader", false,
                    SpreadsheetParser.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /** Codificación de los CSV que exporta Excel en español. */
    public static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    /**
     * Lee el archivo completo. {@code sheetName} vacío = primera hoja con datos.
     *
     * @throws ApiException 400 {@code INVALID_FILE} si el archivo no se puede leer o está vacío
     */
    public ParsedSheet parse(byte[] content, String fileName, String sheetName) {
        if (content == null || content.length == 0) {
            throw new BadRequestException(ErrorCodes.INVALID_FILE, "El archivo está vacío.");
        }
        if (content.length > MAX_FILE_SIZE) {
            throw new BadRequestException(ErrorCodes.INVALID_FILE, "El archivo supera el máximo de 10 MB.");
        }
        ImportFileFormat format = detectFormat(content, fileName);
        return format == ImportFileFormat.CSV ? parseCsv(content) : parseExcel(content, format, sheetName);
    }

    /** Formato según la firma del archivo y, si no alcanza, la extensión. */
    public ImportFileFormat detectFormat(byte[] content, String fileName) {
        if (content.length > 4 && content[0] == 'P' && content[1] == 'K' && content[2] == 3 && content[3] == 4) {
            return ImportFileFormat.XLSX;
        }
        if (content.length > 8 && (content[0] & 0xFF) == 0xD0 && (content[1] & 0xFF) == 0xCF) {
            return ImportFileFormat.XLS;
        }
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".xlsx") || lower.endsWith(".xlsm")) {
            return ImportFileFormat.XLSX;
        }
        if (lower.endsWith(".xls")) {
            return ImportFileFormat.XLS;
        }
        if (lower.endsWith(".csv") || lower.endsWith(".txt") || lower.endsWith(".tsv")) {
            return ImportFileFormat.CSV;
        }
        throw new BadRequestException(ErrorCodes.INVALID_FILE,
                "Solo se aceptan planillas Excel (.xlsx, .xls) o archivos CSV.");
    }

    // -----------------------------------------------------------------------
    // Excel
    // -----------------------------------------------------------------------

    private ParsedSheet parseExcel(byte[] content, ImportFileFormat format, String requestedSheet) {
        try (Workbook workbook = format == ImportFileFormat.XLSX
                ? new XSSFWorkbook(new ByteArrayInputStream(content))
                : new HSSFWorkbook(new ByteArrayInputStream(content))) {
            List<String> sheetNames = new ArrayList<>();
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                sheetNames.add(workbook.getSheetName(i));
            }
            if (sheetNames.isEmpty()) {
                throw new BadRequestException(ErrorCodes.INVALID_FILE, "El archivo no tiene ninguna hoja.");
            }
            Sheet sheet = pickSheet(workbook, requestedSheet);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            List<List<String>> raw = new ArrayList<>();
            int lastRow = sheet.getLastRowNum();
            for (int index = sheet.getFirstRowNum(); index <= lastRow; index++) {
                Row row = sheet.getRow(index);
                List<String> cells = new ArrayList<>();
                if (row != null) {
                    int lastCell = Math.min(row.getLastCellNum(), MAX_COLUMNS);
                    for (int c = 0; c < lastCell; c++) {
                        cells.add(cellText(row.getCell(c), evaluator));
                    }
                }
                raw.add(cells);
                if (raw.size() > MAX_ROWS + 50) {
                    break;   // corte defensivo: el límite exacto se valida más abajo
                }
            }
            return toSheet(raw, format, sheetNames, sheet.getSheetName(), null, null);
        } catch (ApiException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new BadRequestException(ErrorCodes.INVALID_FILE,
                    "No pudimos leer la planilla. Verificá que sea un Excel válido y volvé a subirla.");
        }
    }

    private Sheet pickSheet(Workbook workbook, String requestedSheet) {
        String requested = ImportValues.text(requestedSheet);
        if (requested != null) {
            Sheet sheet = workbook.getSheet(requested);
            if (sheet == null) {
                throw new BadRequestException(ErrorCodes.VALIDATION_ERROR,
                        "La hoja «" + requested + "» no existe en el archivo.");
            }
            return sheet;
        }
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            Sheet candidate = workbook.getSheetAt(i);
            if (candidate.getPhysicalNumberOfRows() > 0
                    && !ImportValues.slug(candidate.getSheetName()).startsWith("instruccion")) {
                return candidate;
            }
        }
        return workbook.getSheetAt(0);
    }

    private String cellText(Cell cell, FormulaEvaluator evaluator) {
        if (cell == null) {
            return "";
        }
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            try {
                type = evaluator.evaluateFormulaCell(cell);
            } catch (RuntimeException e) {
                type = cell.getCachedFormulaResultType();
            }
        }
        return switch (type) {
            case STRING -> cell.getStringCellValue().trim();
            case BOOLEAN -> cell.getBooleanCellValue() ? "SI" : "NO";
            case NUMERIC -> numericText(cell);
            case ERROR, BLANK, _NONE, FORMULA -> "";
        };
    }

    private String numericText(Cell cell) {
        double value = cell.getNumericCellValue();
        if (DateUtil.isCellDateFormatted(cell)) {
            LocalDate date = cell.getLocalDateTimeCellValue().toLocalDate();
            return date.format(DMY);
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    // -----------------------------------------------------------------------
    // CSV
    // -----------------------------------------------------------------------

    private ParsedSheet parseCsv(byte[] content) {
        Charset charset = detectCharset(content);
        String text = new String(stripBom(content, charset), charset);
        char delimiter = detectDelimiter(text);
        List<List<String>> raw = COMMONS_CSV_AVAILABLE
                ? readWithCommonsCsv(text, delimiter)
                : SimpleCsvReader.read(text, delimiter, MAX_ROWS + 50, MAX_COLUMNS);
        return toSheet(raw, ImportFileFormat.CSV, List.of(), null, String.valueOf(delimiter), charset.name());
    }

    private List<List<String>> readWithCommonsCsv(String text, char delimiter) {
        CSVFormat format = CSVFormat.Builder.create(CSVFormat.DEFAULT)
                .setDelimiter(delimiter)
                .setQuote('"')
                .setIgnoreSurroundingSpaces(true)
                .setIgnoreEmptyLines(true)
                .setAllowMissingColumnNames(true)
                .setTrim(true)
                .build();
        List<List<String>> raw = new ArrayList<>();
        try (Reader reader = new java.io.StringReader(text);
             CSVParser parser = CSVParser.parse(reader, format)) {
            for (CSVRecord record : parser) {
                List<String> cells = new ArrayList<>();
                for (int i = 0; i < Math.min(record.size(), MAX_COLUMNS); i++) {
                    cells.add(record.get(i) == null ? "" : record.get(i).trim());
                }
                raw.add(cells);
                if (raw.size() > MAX_ROWS + 50) {
                    break;
                }
            }
            return raw;
        } catch (IOException | RuntimeException e) {
            throw new BadRequestException(ErrorCodes.INVALID_FILE,
                    "No pudimos leer el CSV. Revisá que las columnas estén separadas por «;», «,» o tabulaciones.");
        }
    }

    /** UTF-8 si hay BOM o si el contenido decodifica sin errores; si no, Windows-1252 (Excel en español). */
    public Charset detectCharset(byte[] content) {
        if (content.length >= 3 && (content[0] & 0xFF) == 0xEF && (content[1] & 0xFF) == 0xBB
                && (content[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            // Si decodifica sin errores es UTF-8 (un archivo ASCII puro decodifica igual con las dos).
            decoder.decode(ByteBuffer.wrap(content));
            return StandardCharsets.UTF_8;
        } catch (CharacterCodingException e) {
            return WINDOWS_1252;
        }
    }

    private byte[] stripBom(byte[] content, Charset charset) {
        if (charset.equals(StandardCharsets.UTF_8) && content.length >= 3 && (content[0] & 0xFF) == 0xEF
                && (content[1] & 0xFF) == 0xBB && (content[2] & 0xFF) == 0xBF) {
            byte[] stripped = new byte[content.length - 3];
            System.arraycopy(content, 3, stripped, 0, stripped.length);
            return stripped;
        }
        return content;
    }

    /** Separador con más apariciones consistentes en las primeras líneas (fuera de comillas). */
    public char detectDelimiter(String text) {
        List<String> lines = text.lines().filter(line -> !line.isBlank()).limit(20).toList();
        if (lines.isEmpty()) {
            throw new BadRequestException(ErrorCodes.INVALID_FILE, "El archivo está vacío.");
        }
        char best = ';';
        int bestScore = -1;
        for (char candidate : CANDIDATE_DELIMITERS) {
            int first = countOutsideQuotes(lines.get(0), candidate);
            if (first == 0) {
                continue;
            }
            int consistent = 0;
            for (String line : lines) {
                if (countOutsideQuotes(line, candidate) == first) {
                    consistent++;
                }
            }
            int score = first * 100 + consistent;
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return bestScore < 0 ? ';' : best;
    }

    private int countOutsideQuotes(String line, char delimiter) {
        int count = 0;
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (c == delimiter && !quoted) {
                count++;
            }
        }
        return count;
    }

    // -----------------------------------------------------------------------
    // Común
    // -----------------------------------------------------------------------

    private ParsedSheet toSheet(List<List<String>> raw, ImportFileFormat format, List<String> sheetNames,
                                String sheetName, String delimiter, String charset) {
        int headerIndex = -1;
        for (int i = 0; i < raw.size(); i++) {
            if (raw.get(i).stream().anyMatch(cell -> !cell.isBlank())) {
                headerIndex = i;
                break;
            }
        }
        if (headerIndex < 0) {
            throw new BadRequestException(ErrorCodes.INVALID_FILE,
                    "El archivo no tiene datos: la primera fila tiene que ser el encabezado con los nombres de las columnas.");
        }
        List<String> headers = uniqueHeaders(raw.get(headerIndex));
        List<List<String>> rows = new ArrayList<>();
        for (int i = headerIndex + 1; i < raw.size(); i++) {
            List<String> cells = raw.get(i);
            if (cells.stream().allMatch(String::isBlank)) {
                continue;
            }
            List<String> aligned = new ArrayList<>(headers.size());
            for (int c = 0; c < headers.size(); c++) {
                aligned.add(c < cells.size() ? cells.get(c) : "");
            }
            rows.add(aligned);
            if (rows.size() > MAX_ROWS) {
                throw new BadRequestException(ErrorCodes.INVALID_FILE,
                        "El archivo tiene más de " + MAX_ROWS + " filas. Dividilo en varias importaciones.");
            }
        }
        if (rows.isEmpty()) {
            throw new BadRequestException(ErrorCodes.INVALID_FILE,
                    "El archivo no tiene filas de datos debajo del encabezado.");
        }
        return new ParsedSheet(format, sheetNames, sheetName, headers, rows, delimiter, charset);
    }

    /** Encabezados no vacíos y sin repetidos ("Precio", "Precio (2)"). */
    private List<String> uniqueHeaders(List<String> rawHeaders) {
        List<String> headers = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int lastWithText = -1;
        for (int i = 0; i < rawHeaders.size(); i++) {
            if (!rawHeaders.get(i).isBlank()) {
                lastWithText = i;
            }
        }
        for (int i = 0; i <= lastWithText; i++) {
            String header = ImportValues.text(rawHeaders.get(i));
            String base = header == null ? "Columna " + (i + 1) : header;
            if (base.length() > 120) {
                base = base.substring(0, 120);
            }
            String candidate = base;
            int suffix = 2;
            while (!seen.add(candidate.toLowerCase(Locale.ROOT))) {
                candidate = base + " (" + suffix++ + ")";
            }
            headers.add(candidate);
        }
        if (headers.isEmpty()) {
            throw new BadRequestException(ErrorCodes.INVALID_FILE, "No encontramos el encabezado con los nombres de las columnas.");
        }
        return headers;
    }
}
