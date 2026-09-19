package com.gondolia.imports;

import com.gondolia.common.error.ApiException;
import com.gondolia.common.error.BadRequestException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.imports.dto.ImportDtos;
import com.gondolia.imports.parse.ImportField;
import com.gondolia.imports.parse.SpreadsheetParser;
import com.gondolia.security.BranchAccessService;
import com.gondolia.security.CurrentUser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Archivos de la importación (SPEC §16.3): plantilla en blanco con instrucciones, exportación del catálogo actual
 * con las mismas columnas (para editar en Excel y reimportar) y CSV de errores de una importación.
 */
@Service
@RequiredArgsConstructor
public class ImportFileService {

    /** Archivo generado listo para descargar. */
    public record GeneratedFile(String fileName, String contentType, byte[] content) {
    }

    public static final String XLSX_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    public static final String CSV_TYPE = "text/csv; charset=UTF-8";
    /** Separador del CSV: Excel en español abre los `;` en columnas sin pedir nada. */
    private static final char CSV_DELIMITER = ';';
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final NamedParameterJdbcTemplate jdbc;
    private final BranchAccessService branchAccess;
    private final ImportService importService;
    private final ImportRowStore rowStore;
    private final Clock clock;

    // -----------------------------------------------------------------------
    // Plantilla
    // -----------------------------------------------------------------------

    /** Plantilla con los encabezados en español, 4 filas de ejemplo y la hoja «Instrucciones». */
    public GeneratedFile template(String format) {
        List<String> headers = headers();
        List<List<String>> examples = exampleRows();
        if (isCsv(format)) {
            return new GeneratedFile("gondolia-plantilla-productos.csv", CSV_TYPE, csv(headers, examples));
        }
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Productos");
            writeHeader(workbook, sheet, headers);
            int rowIndex = 1;
            for (List<String> example : examples) {
                Row row = sheet.createRow(rowIndex++);
                for (int c = 0; c < example.size(); c++) {
                    row.createCell(c).setCellValue(example.get(c));
                }
            }
            autoSize(sheet, headers.size());
            sheet.createFreezePane(0, 1);
            writeInstructions(workbook);
            workbook.write(out);
            return new GeneratedFile("gondolia-plantilla-productos.xlsx", XLSX_TYPE, out.toByteArray());
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.INTERNAL_ERROR,
                    "No pudimos generar la plantilla. Intentá de nuevo.");
        }
    }

    private List<String> headers() {
        List<String> headers = new ArrayList<>();
        for (ImportField field : ImportField.values()) {
            headers.add(field.label());
        }
        return headers;
    }

    /** Cuatro filas de ejemplo: el primer producto viene con dos lotes distintos (SPEC §16.3). */
    private List<List<String>> exampleRows() {
        return List.of(
                List.of("7791234000012", "Leche entera La Pradera 1 L", "La Pradera", "Lácteos",
                        "Distribuidora La Pampa", "LITRO", "950,00", "1.350,00", "12", "sí",
                        "Sachet de 1 litro", "24", "L2409A", "15/10/2026", "01/09/2026", "Sucursal Centro"),
                List.of("7791234000012", "Leche entera La Pradera 1 L", "La Pradera", "Lácteos",
                        "Distribuidora La Pampa", "LITRO", "950,00", "1.350,00", "12", "sí",
                        "Sachet de 1 litro", "18", "L2410B", "28/10/2026", "10/09/2026", "Sucursal Centro"),
                List.of("7791234000029", "Yogur bebible frutilla Vaquita 1 L", "Vaquita", "Lácteos",
                        "Distribuidora La Pampa", "LITRO", "1.180,00", "1.690,00", "8", "sí",
                        "", "12", "Y2411", "05/11/2026", "12/09/2026", "Sucursal Centro"),
                List.of("7791234000036", "Galletitas de agua Crocantes 200 g", "Crocantes", "Almacén",
                        "", "PAQUETE", "620,00", "980,00", "10", "no", "", "30", "", "", "", "Sucursal Norte"));
    }

    private void writeInstructions(Workbook workbook) {
        Sheet sheet = workbook.createSheet("Instrucciones");
        CellStyle title = workbook.createCellStyle();
        Font titleFont = workbook.createFont();
        titleFont.setBold(true);
        titleFont.setFontHeightInPoints((short) 13);
        title.setFont(titleFont);
        CellStyle bold = workbook.createCellStyle();
        Font boldFont = workbook.createFont();
        boldFont.setBold(true);
        bold.setFont(boldFont);
        CellStyle wrap = workbook.createCellStyle();
        wrap.setWrapText(true);
        wrap.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.TOP);

        int index = 0;
        Row header = sheet.createRow(index++);
        Cell headerCell = header.createCell(0);
        headerCell.setCellValue("Cómo completar la planilla de GondolIA");
        headerCell.setCellStyle(title);
        index++;
        for (String line : List.of(
                "Completá la hoja «Productos»: una fila por producto. El único campo obligatorio es el Nombre.",
                "Si un producto tiene varios lotes o vencimientos, repetí el mismo Código de barras en varias filas "
                        + "y cambiá el lote, el vencimiento y la cantidad: se carga un lote por fila.",
                "Podés borrar las columnas que no uses y cambiar el orden: al importar elegís qué columna es cada campo.",
                "Números: «1.234,50» o «1234.5». Fechas: dd/mm/aaaa. Sí/No: sí, no, 1, 0, x.",
                "La Fecha de ingreso conserva el orden FIFO: lo que entró antes se vende antes.",
                "Cada fila con Cantidad en stock suma un lote nuevo. Para actualizar solo precios o datos de un "
                        + "catálogo exportado, dejá la cantidad vacía o desactivá «Cargar el stock de la planilla» "
                        + "al importar.",
                "Máximo 10.000 filas y 10 MB por archivo. Si tenés más, dividilo en varias importaciones.")) {
            Row row = sheet.createRow(index++);
            Cell cell = row.createCell(0);
            cell.setCellValue("• " + line);
            cell.setCellStyle(wrap);
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(row.getRowNum(), row.getRowNum(), 0, 3));
            row.setHeightInPoints(28);
        }
        index++;
        Row fieldsHeader = sheet.createRow(index++);
        String[] columns = {"Columna", "¿Obligatoria?", "Tipo", "Qué poner"};
        for (int c = 0; c < columns.length; c++) {
            Cell cell = fieldsHeader.createCell(c);
            cell.setCellValue(columns[c]);
            cell.setCellStyle(bold);
        }
        for (ImportField field : ImportField.values()) {
            Row row = sheet.createRow(index++);
            row.createCell(0).setCellValue(field.label());
            row.createCell(1).setCellValue(field.required() ? "Sí" : "No");
            row.createCell(2).setCellValue(typeLabel(field));
            Cell description = row.createCell(3);
            description.setCellValue(field.description());
            description.setCellStyle(wrap);
        }
        sheet.setColumnWidth(0, 6_500);
        sheet.setColumnWidth(1, 3_500);
        sheet.setColumnWidth(2, 4_000);
        sheet.setColumnWidth(3, 22_000);
    }

    private String typeLabel(ImportField field) {
        return switch (field.type()) {
            case TEXT -> "Texto";
            case NUMBER -> "Número";
            case INTEGER -> "Número entero";
            case BOOLEAN -> "Sí / No";
            case DATE -> "Fecha (dd/mm/aaaa)";
            case UNIT -> "UNIDAD, KG, LITRO, PAQUETE o CAJA";
            case BRANCH -> "Nombre o código de sucursal";
        };
    }

    // -----------------------------------------------------------------------
    // Exportación del catálogo
    // -----------------------------------------------------------------------

    /** Catálogo actual con las mismas columnas de la plantilla, para editar y reimportar (SPEC §16.3). */
    @Transactional(readOnly = true)
    public GeneratedFile export(String format, boolean includeStock) {
        Long tenantId = CurrentUser.tenantId();
        List<Long> branchIds = branchAccess.scopeBranchIds();
        List<List<String>> rows = catalogRows(tenantId, branchIds, includeStock);
        String date = LocalDate.now(clock).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        List<String> headers = headers();
        if (isCsv(format)) {
            return new GeneratedFile("gondolia-catalogo-" + date + ".csv", CSV_TYPE, csv(headers, rows));
        }
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Productos");
            writeHeader(workbook, sheet, headers);
            int rowIndex = 1;
            for (List<String> values : rows) {
                Row row = sheet.createRow(rowIndex++);
                for (int c = 0; c < values.size(); c++) {
                    row.createCell(c).setCellValue(values.get(c));
                }
            }
            autoSize(sheet, headers.size());
            sheet.createFreezePane(0, 1);
            workbook.write(out);
            return new GeneratedFile("gondolia-catalogo-" + date + ".xlsx", XLSX_TYPE, out.toByteArray());
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.INTERNAL_ERROR,
                    "No pudimos generar la exportación. Intentá de nuevo.");
        }
    }

    private List<List<String>> catalogRows(Long tenantId, List<Long> branchIds, boolean includeStock) {
        boolean withStock = includeStock && !branchIds.isEmpty();
        String sql = """
                select p.barcode, p.name, p.brand, c.name as category_name, s.name as supplier_name, p.unit,
                       p.cost_price, p.sale_price, p.min_stock, p.perishable, p.description,
                       %s
                  from products p
                  left join categories c on c.id = p.category_id
                  left join suppliers s on s.id = p.supplier_id
                  %s
                 where p.tenant_id = :tenantId and p.active = true
                 order by p.name asc, p.id asc%s
                """.formatted(
                withStock ? "l.quantity, l.lot_number, l.expiry_date, l.received_at, b.name as branch_name"
                        : "null::int as quantity, null::text as lot_number, null::date as expiry_date, "
                        + "null::timestamptz as received_at, null::text as branch_name",
                withStock ? """
                        left join lots l on l.product_id = p.id and l.tenant_id = p.tenant_id and l.quantity > 0
                                        and l.status in ('ACTIVE', 'RECALLED') and l.branch_id in (:branchIds)
                        left join branches b on b.id = l.branch_id
                        """ : "",
                withStock ? ", b.name asc nulls first, l.received_at asc, l.id asc" : "");
        MapSqlParameterSource params = new MapSqlParameterSource("tenantId", tenantId);
        if (withStock) {
            params.addValue("branchIds", branchIds);
        }
        return jdbc.query(sql, params, (rs, index) -> {
            List<String> values = new ArrayList<>(ImportField.values().length);
            values.add(nullToEmpty(rs.getString("barcode")));
            values.add(nullToEmpty(rs.getString("name")));
            values.add(nullToEmpty(rs.getString("brand")));
            values.add(nullToEmpty(rs.getString("category_name")));
            values.add(nullToEmpty(rs.getString("supplier_name")));
            values.add(nullToEmpty(rs.getString("unit")));
            values.add(money(rs.getBigDecimal("cost_price")));
            values.add(money(rs.getBigDecimal("sale_price")));
            values.add(String.valueOf(rs.getInt("min_stock")));
            values.add(rs.getBoolean("perishable") ? "sí" : "no");
            values.add(nullToEmpty(rs.getString("description")));
            Object quantity = rs.getObject("quantity");
            values.add(quantity == null ? "" : String.valueOf(((Number) quantity).intValue()));
            values.add(nullToEmpty(rs.getString("lot_number")));
            java.sql.Date expiry = rs.getDate("expiry_date");
            values.add(expiry == null ? "" : expiry.toLocalDate().format(DMY));
            Timestamp received = rs.getTimestamp("received_at");
            values.add(received == null ? "" : received.toInstant().atZone(clock.getZone()).toLocalDate().format(DMY));
            values.add(nullToEmpty(rs.getString("branch_name")));
            return values;
        });
    }

    // -----------------------------------------------------------------------
    // CSV de errores
    // -----------------------------------------------------------------------

    /** Filas con error o advertencia y el motivo de cada celda (SPEC §16.3). */
    @Transactional(readOnly = true)
    public GeneratedFile errorsCsv(Long jobId) {
        var job = importService.job(jobId);
        List<String> headers = new ArrayList<>(List.of("Fila", "Estado", "Problemas"));
        for (ImportField field : ImportField.values()) {
            headers.add(field.label());
        }
        List<List<String>> rows = new ArrayList<>();
        for (ImportRowStore.StoredRow row : rowStore.rowsWithMessages(job.getId())) {
            List<String> values = new ArrayList<>();
            values.add(String.valueOf(row.rowNumber()));
            values.add(statusLabel(row.status()));
            values.add(describe(row.messages()));
            for (ImportField field : ImportField.values()) {
                values.add(nullToEmpty(row.data().get(field.key())));
            }
            rows.add(values);
        }
        return new GeneratedFile("importacion-" + job.getId() + "-errores.csv", CSV_TYPE, csv(headers, rows));
    }

    private String describe(List<ImportDtos.RowMessageDto> messages) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (ImportField field : ImportField.values()) {
            labels.put(field.key(), field.label());
        }
        List<String> lines = new ArrayList<>();
        for (ImportDtos.RowMessageDto message : messages) {
            String prefix = "ERROR".equals(message.level()) ? "Error" : "Advertencia";
            String column = message.field() == null ? null : labels.get(message.field());
            lines.add(prefix + (column == null ? "" : " en " + column) + ": " + message.message());
        }
        return String.join(" | ", lines);
    }

    private String statusLabel(com.gondolia.domain.imports.ImportRowStatus status) {
        return switch (status) {
            case ERROR -> "Error";
            case WARNING -> "Advertencia";
            case FAILED -> "No se pudo aplicar";
            case SKIPPED -> "Omitida";
            case IMPORTED -> "Importada";
            case VALID -> "Válida";
            case PENDING -> "Pendiente";
        };
    }

    // -----------------------------------------------------------------------
    // Utilidades
    // -----------------------------------------------------------------------

    private boolean isCsv(String format) {
        if (format == null || format.isBlank()) {
            return false;
        }
        String normalized = format.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("csv")) {
            return true;
        }
        if (normalized.equals("xlsx") || normalized.equals("excel") || normalized.equals("xls")) {
            return false;
        }
        throw new BadRequestException(ErrorCodes.VALIDATION_ERROR, "El formato tiene que ser «xlsx» o «csv».");
    }

    /** CSV con BOM UTF-8 y separador «;»: Excel en español lo abre en columnas sin configurar nada. */
    byte[] csv(List<String> headers, List<List<String>> rows) {
        StringBuilder builder = new StringBuilder("﻿");
        appendCsvRow(builder, headers);
        for (List<String> row : rows) {
            appendCsvRow(builder, row);
        }
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void appendCsvRow(StringBuilder builder, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(CSV_DELIMITER);
            }
            builder.append(escape(values.get(i)));
        }
        builder.append("\r\n");
    }

    private String escape(String value) {
        String text = value == null ? "" : value;
        if (text.indexOf(CSV_DELIMITER) >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0
                || text.indexOf('\r') >= 0) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }

    private void writeHeader(Workbook workbook, Sheet sheet, List<String> headers) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.SEA_GREEN.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.LEFT);
        style.setBorderBottom(BorderStyle.THIN);
        Row row = sheet.createRow(0);
        for (int c = 0; c < headers.size(); c++) {
            Cell cell = row.createCell(c);
            cell.setCellValue(headers.get(c));
            cell.setCellStyle(style);
        }
    }

    private void autoSize(Sheet sheet, int columns) {
        for (int c = 0; c < columns; c++) {
            sheet.autoSizeColumn(c);
            int width = Math.min(Math.max(sheet.getColumnWidth(c) + 600, 2_800), 12_000);
            sheet.setColumnWidth(c, width);
        }
    }

    private String money(BigDecimal value) {
        if (value == null) {
            return "";
        }
        return String.format(Locale.GERMANY, "%,.2f", value.setScale(2, RoundingMode.HALF_UP));
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Tamaño máximo aceptado por la subida (lo reexporta para la documentación y las pruebas). */
    public static long maxFileSize() {
        return SpreadsheetParser.MAX_FILE_SIZE;
    }
}
