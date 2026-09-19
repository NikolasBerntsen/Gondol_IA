package com.gondolia.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.gondolia.imports.parse.ImportField;
import com.gondolia.imports.parse.ImportRowParser;
import com.gondolia.imports.parse.ImportValues;
import com.gondolia.imports.parse.ParsedRow;
import com.gondolia.imports.parse.ParsedSheet;
import com.gondolia.imports.parse.SpreadsheetParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.IgnoredErrorType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/**
 * Archivos de la importación (SPEC §16.3): la plantilla y la exportación escriben números y fechas de Excel de verdad
 * (no texto) y la importación los vuelve a leer con el mismo valor.
 */
class ImportFileServiceTest {

    private final ImportFileService service = new ImportFileService(null, null, null, null, Clock.systemUTC());
    private final SpreadsheetParser parser = new SpreadsheetParser();

    @Test
    void writesAmountsQuantitiesAndDatesAsTypedExcelCells() throws IOException {
        byte[] content = service.template("xlsx").content();

        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(content))) {
            Sheet sheet = workbook.getSheet("Productos");
            Row first = sheet.getRow(1);

            Cell barcode = first.getCell(ImportField.BARCODE.ordinal());
            assertThat(barcode.getCellType()).isEqualTo(CellType.STRING);
            assertThat(barcode.getStringCellValue()).isEqualTo("7791234000012");
            // La columna del código queda con formato Texto: lo que se tipee abajo no pierde ceros ni sale en E+12.
            assertThat(sheet.getColumnStyle(ImportField.BARCODE.ordinal()).getDataFormatString()).isEqualTo("@");
            assertThat(sheet.getColumnStyle(ImportField.LOT_NUMBER.ordinal()).getDataFormatString()).isEqualTo("@");
            // Y Excel no los marca como «número almacenado como texto»: son identificadores, no cantidades.
            assertThat(((XSSFSheet) sheet).getIgnoredErrors().get(IgnoredErrorType.NUMBER_STORED_AS_TEXT))
                    .extracting(CellRangeAddress::formatAsString)
                    .containsExactlyInAnyOrder("A1:A1048576", "M1:M1048576");

            assertNumeric(first, ImportField.COST_PRICE, 950, "#,##0.00");
            assertNumeric(first, ImportField.SALE_PRICE, 1350, "#,##0.00");
            assertNumeric(first, ImportField.MIN_STOCK, 12, "0");
            assertNumeric(first, ImportField.QUANTITY, 24, "0");
            assertDate(first, ImportField.EXPIRY_DATE, LocalDate.of(2026, 10, 15));
            assertDate(first, ImportField.RECEIVED_AT, LocalDate.of(2026, 9, 1));
            assertThat(first.getCell(ImportField.PERISHABLE.ordinal()).getStringCellValue()).isEqualTo("sí");

            // Las celdas vacías no se crean como texto vacío.
            Row withoutLot = sheet.getRow(4);
            assertThat(withoutLot.getCell(ImportField.LOT_NUMBER.ordinal())).isNull();
            assertThat(withoutLot.getCell(ImportField.EXPIRY_DATE.ordinal())).isNull();
        }
    }

    @Test
    void readsTheTypedCellsBackWithTheSameValues() {
        ParsedSheet sheet = parser.parse(service.template("xlsx").content(), "gondolia-plantilla-productos.xlsx", null);

        assertThat(sheet.sheetName()).isEqualTo("Productos");
        ParsedRow row = ImportRowParser.parse(data(sheet, 0), ImportValues.DateOrder.DMY);
        assertThat(row.messages()).isEmpty();
        assertThat(row.barcode()).isEqualTo("7791234000012");
        assertThat(row.costPrice()).isEqualByComparingTo("950");
        assertThat(row.salePrice()).isEqualByComparingTo("1350");
        assertThat(row.minStock()).isEqualTo(12);
        assertThat(row.quantity()).isEqualTo(24);
        assertThat(row.perishable()).isTrue();
        assertThat(row.expiryDate()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(row.receivedAt()).isEqualTo(LocalDate.of(2026, 9, 1));

        ParsedRow crackers = ImportRowParser.parse(data(sheet, 3), ImportValues.DateOrder.DMY);
        assertThat(crackers.salePrice()).isEqualByComparingTo("980");
        assertThat(crackers.expiryDate()).isNull();
        assertThat(crackers.lotNumber()).isNull();
    }

    @Test
    void keepsTheCsvTemplateInArgentineFormat() {
        String csv = new String(service.template("csv").content(), StandardCharsets.UTF_8);

        assertThat(csv).contains("LITRO;950,00;1.350,00;12;sí;Sachet de 1 litro;24;L2409A;15/10/2026;01/09/2026");
        assertThat(csv).contains("PAQUETE;620,00;980,00;10;no;;30;;;;Sucursal Norte");
    }

    private Map<String, String> data(ParsedSheet sheet, int index) {
        List<String> cells = sheet.rows().get(index);
        Map<String, String> data = new HashMap<>();
        for (ImportField field : ImportField.values()) {
            data.put(field.key(), cells.get(sheet.headers().indexOf(field.label())));
        }
        return data;
    }

    private void assertNumeric(Row row, ImportField field, double expected, String format) {
        Cell cell = row.getCell(field.ordinal());
        assertThat(cell.getCellType()).as(field.label()).isEqualTo(CellType.NUMERIC);
        assertThat(cell.getNumericCellValue()).as(field.label()).isEqualTo(expected);
        assertThat(cell.getCellStyle().getDataFormatString()).as(field.label()).isEqualTo(format);
    }

    private void assertDate(Row row, ImportField field, LocalDate expected) {
        Cell cell = row.getCell(field.ordinal());
        assertThat(cell.getCellType()).as(field.label()).isEqualTo(CellType.NUMERIC);
        assertThat(DateUtil.isCellDateFormatted(cell)).as(field.label()).isTrue();
        assertThat(cell.getLocalDateTimeCellValue().toLocalDate()).as(field.label()).isEqualTo(expected);
    }
}
