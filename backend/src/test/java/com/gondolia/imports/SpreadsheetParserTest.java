package com.gondolia.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gondolia.common.error.ApiException;
import com.gondolia.domain.imports.ImportFileFormat;
import com.gondolia.imports.parse.ParsedSheet;
import com.gondolia.imports.parse.SpreadsheetParser;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/** Lectura de planillas XLSX y CSV con detección de separador y codificación (SPEC §16.2). */
class SpreadsheetParserTest {

    private final SpreadsheetParser parser = new SpreadsheetParser();

    // -----------------------------------------------------------------------
    // CSV
    // -----------------------------------------------------------------------

    @Test
    void readsCsvWithSemicolonsAndWindows1252() {
        String csv = """
                Código;Descripción;Precio
                7791234000012;Leche entera La Pradera 1 L;$ 1.350,00
                7791234000029;Yogur bebible Vaquita 1 L;$ 2.100,00
                """;
        ParsedSheet sheet = parser.parse(csv.getBytes(Charset.forName("windows-1252")), "listado.csv", null);

        assertThat(sheet.format()).isEqualTo(ImportFileFormat.CSV);
        assertThat(sheet.delimiter()).isEqualTo(";");
        assertThat(sheet.charset()).isEqualToIgnoringCase("windows-1252");
        assertThat(sheet.headers()).containsExactly("Código", "Descripción", "Precio");
        assertThat(sheet.rows()).hasSize(2);
        assertThat(sheet.rows().getFirst()).containsExactly("7791234000012", "Leche entera La Pradera 1 L",
                "$ 1.350,00");
    }

    @Test
    void readsCsvWithCommasBomAndQuotes() {
        String csv = "﻿Codigo,Nombre,Precio\n7791234000012,\"Leche entera, sachet\",1350\n";
        ParsedSheet sheet = parser.parse(csv.getBytes(StandardCharsets.UTF_8), "listado.csv", null);

        assertThat(sheet.delimiter()).isEqualTo(",");
        assertThat(sheet.charset()).isEqualToIgnoringCase("UTF-8");
        assertThat(sheet.headers()).containsExactly("Codigo", "Nombre", "Precio");
        assertThat(sheet.rows().getFirst()).containsExactly("7791234000012", "Leche entera, sachet", "1350");
    }

    @Test
    void readsTabSeparatedFiles() {
        String csv = "Codigo\tNombre\tPrecio\n779\tLeche\t1350\n";
        ParsedSheet sheet = parser.parse(csv.getBytes(StandardCharsets.UTF_8), "listado.csv", null);

        assertThat(sheet.delimiter()).isEqualTo("\t");
        assertThat(sheet.headers()).containsExactly("Codigo", "Nombre", "Precio");
    }

    @Test
    void skipsEmptyRowsAndTakesTheFirstNonEmptyRowAsHeader() {
        String csv = "\n\nCodigo;Nombre\n\n779;Leche\n\n779;Yogur\n";
        ParsedSheet sheet = parser.parse(csv.getBytes(StandardCharsets.UTF_8), "listado.csv", null);

        assertThat(sheet.headers()).containsExactly("Codigo", "Nombre");
        assertThat(sheet.rows()).hasSize(2);
    }

    @Test
    void makesRepeatedHeadersUnique() {
        String csv = "Precio;Precio;Nombre\n1;2;Leche\n";
        ParsedSheet sheet = parser.parse(csv.getBytes(StandardCharsets.UTF_8), "listado.csv", null);

        assertThat(sheet.headers()).containsExactly("Precio", "Precio (2)", "Nombre");
    }

    @Test
    void rejectsFilesWithoutDataRows() {
        assertThatThrownBy(() -> parser.parse("Codigo;Nombre\n".getBytes(StandardCharsets.UTF_8), "x.csv", null))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("INVALID_FILE"));
    }

    @Test
    void rejectsUnknownFormats() {
        assertThatThrownBy(() -> parser.parse("hola".getBytes(StandardCharsets.UTF_8), "notas.docx", null))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessage()).contains("Excel"));
    }

    // -----------------------------------------------------------------------
    // Excel
    // -----------------------------------------------------------------------

    @Test
    void readsXlsxWithNativeDatesNumbersAndFormulas() throws Exception {
        byte[] content = workbook();
        ParsedSheet sheet = parser.parse(content, "planilla.xlsx", null);

        assertThat(sheet.format()).isEqualTo(ImportFileFormat.XLSX);
        assertThat(sheet.sheetNames()).containsExactly("Instrucciones", "Listado");
        // La primera hoja con datos que no sean instrucciones.
        assertThat(sheet.sheetName()).isEqualTo("Listado");
        assertThat(sheet.headers()).containsExactly("Codigo", "Nombre", "Cantidad", "Vto", "Total");
        assertThat(sheet.rows()).hasSize(1);
        assertThat(sheet.rows().getFirst()).containsExactly(
                "7791234000012",            // el código largo no vuelve en notación científica
                "Leche entera La Pradera 1 L",
                "24",                        // el número entero no trae ".0"
                "15/10/2026",                // fecha nativa formateada dd/MM/yyyy
                "2400");                     // fórmula evaluada
    }

    @Test
    void readsTheRequestedSheet() throws Exception {
        ParsedSheet sheet = parser.parse(workbook(), "planilla.xlsx", "Instrucciones");

        assertThat(sheet.sheetName()).isEqualTo("Instrucciones");
        assertThat(sheet.headers()).containsExactly("Cómo completar la planilla");
    }

    @Test
    void rejectsAnUnknownSheet() {
        assertThatThrownBy(() -> parser.parse(workbook(), "planilla.xlsx", "Hoja 9"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getMessage()).contains("Hoja 9"));
    }

    private byte[] workbook() {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet instructions = workbook.createSheet("Instrucciones");
            instructions.createRow(0).createCell(0).setCellValue("Cómo completar la planilla");
            instructions.createRow(1).createCell(0).setCellValue("Una fila por producto");

            Sheet data = workbook.createSheet("Listado");
            Row header = data.createRow(0);
            String[] headers = {"Codigo", "Nombre", "Cantidad", "Vto", "Total"};
            for (int c = 0; c < headers.length; c++) {
                header.createCell(c).setCellValue(headers[c]);
            }
            Row row = data.createRow(1);
            row.createCell(0).setCellValue("7791234000012");
            row.createCell(1).setCellValue("Leche entera La Pradera 1 L");
            row.createCell(2).setCellValue(24d);
            CellStyle dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.createDataFormat().getFormat("dd/mm/yyyy"));
            Cell expiry = row.createCell(3);
            expiry.setCellValue(LocalDate.of(2026, 10, 15));
            expiry.setCellStyle(dateStyle);
            row.createCell(4).setCellFormula("C2*100");
            workbook.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
