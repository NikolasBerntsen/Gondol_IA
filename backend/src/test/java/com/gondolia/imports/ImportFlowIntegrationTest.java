package com.gondolia.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gondolia.common.error.ApiException;
import com.gondolia.domain.imports.ImportRowStatus;
import com.gondolia.domain.imports.ImportStatus;
import com.gondolia.domain.user.Role;
import com.gondolia.imports.dto.ImportDtos;
import com.gondolia.imports.parse.ImportField;
import com.gondolia.it.PostgresIntegrationTest;
import com.gondolia.it.TestData;
import com.gondolia.security.AuthUser;
import com.gondolia.security.BranchAccessService;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Flujo completo de la importación masiva contra PostgreSQL real (SPEC §16): subida, mapeo, validación con las
 * reglas de §16.2, corrección de una celda, acciones masivas, aplicación en bloques y aislamiento entre comercios.
 */
class ImportFlowIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private ImportService importService;
    @Autowired
    private ImportApplyService applyService;
    @Autowired
    private ImportFileService fileService;
    @Autowired
    private ImportRowStore rowStore;
    @Autowired
    private BranchAccessService branchAccess;
    @Autowired
    private JdbcTemplate jdbc;

    private TestData data;
    private long tenantA;
    private long tenantB;
    private long centro;
    private long norte;
    private long branchB;
    private AuthUser adminA;
    private AuthUser adminB;

    /** Encabezados "de la vida real": prueban el auto-mapeo por sinónimos. */
    private static final String HEADERS =
            "Cod. Barra;Descripcion;Rubro;Proveedor;Precio Costo;Precio Venta;Cantidad;Nro Lote;Vto;Fecha Ingreso;Sucursal";

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc);
        tenantA = data.tenant("Comercio Importaciones");
        tenantB = data.tenant("Otro Comercio Importaciones");
        centro = data.branch(tenantA, "Sucursal Centro", true);
        norte = data.branch(tenantA, "Sucursal Norte", true);
        branchB = data.branch(tenantB, "Sucursal Ajena", true);
        adminA = data.user(tenantA, Role.TENANT_ADMIN, true);
        adminB = data.user(tenantB, Role.TENANT_ADMIN, true);
        as(adminA);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        jdbc.update("delete from tenants where id in (?, ?)", tenantA, tenantB);
    }

    // -----------------------------------------------------------------------

    @Test
    void appliesTheWholeFlowFromASpreadsheetToStock() {
        ImportDtos.JobDto uploaded = upload(csv());

        assertThat(uploaded.status()).isEqualTo(ImportStatus.UPLOADED);
        assertThat(uploaded.fileFormat()).isEqualTo(com.gondolia.domain.imports.ImportFileFormat.CSV);
        assertThat(uploaded.totalRows()).isEqualTo(8);       // la fila vacía del medio no cuenta
        assertThat(uploaded.suggestedMapping())
                .containsEntry("barcode", "Cod. Barra")
                .containsEntry("name", "Descripcion")
                .containsEntry("category", "Rubro")
                .containsEntry("quantity", "Cantidad")
                .containsEntry("expiryDate", "Vto")
                .containsEntry("branch", "Sucursal");
        assertThat(uploaded.sampleRows()).isNotEmpty();

        ImportDtos.JobDto validated = validate(uploaded);

        assertThat(validated.status()).isEqualTo(ImportStatus.VALIDATED);
        assertThat(validated.errorRows()).isEqualTo(4);      // filas 4, 5, 6 y 8
        // El resto queda con advertencia: categoría y proveedor nuevos, fila repetida y vencimiento pasado.
        assertThat(validated.warningRows()).isEqualTo(4);
        assertThat(validated.validRows()).isZero();
        assertThat(validated.preview()).isNotNull();
        assertThat(validated.preview().categoriesToCreate()).contains("Lacteos", "Almacen");
        assertThat(validated.preview().suppliersToCreate()).containsExactly("Prov Test");

        assertMessages(validated.id(), 4, ImportRowStatus.ERROR, "name", "Falta el nombre del producto.");
        assertMessages(validated.id(), 5, ImportRowStatus.ERROR, "salePrice", "no es un número");
        assertMessages(validated.id(), 6, ImportRowStatus.ERROR, "quantity", "número entero");
        assertMessages(validated.id(), 8, ImportRowStatus.ERROR, "branch", "no existe o no tenés acceso");
        assertMessages(validated.id(), 7, ImportRowStatus.WARNING, "expiryDate", "vencimiento ya pasó");
        assertMessages(validated.id(), 2, ImportRowStatus.WARNING, null, "ya viene en una fila anterior");

        // Corrección inline de la fila 6: la cantidad pasa a ser entera y la fila queda válida.
        Long rowId = rowId(validated.id(), 6);
        @SuppressWarnings("unchecked")
        Map<String, Object> patched = importService.patchRow(validated.id(), rowId,
                new ImportDtos.RowPatchRequest(Map.of("quantity", "4")));
        ImportDtos.RowDto row = (ImportDtos.RowDto) patched.get("row");
        // Deja de ser un error; le quedan las advertencias del EAN y de la categoría nueva.
        assertThat(row.status()).isEqualTo(ImportRowStatus.WARNING);
        assertThat(row.messages()).noneMatch(m -> "ERROR".equals(m.level()));
        assertThat(((ImportDtos.JobDto) patched.get("job")).errorRows()).isEqualTo(3);

        // Acción masiva: omitir la fila 5 en vez de corregirla.
        ImportDtos.BulkRowsResponse bulk = importService.bulk(validated.id(), new ImportDtos.BulkRowsRequest(
                List.of(rowId(validated.id(), 5)), null, "SKIP", null, null));
        assertThat(bulk.affectedRows()).isEqualTo(1);
        assertThat(bulk.job().skippedRows()).isEqualTo(1);
        assertThat(bulk.job().errorRows()).isEqualTo(2);

        // Con errores sin omitir no deja aplicar.
        assertApiError(() -> importService.apply(validated.id(), false), 409, "IMPORT_HAS_ERRORS");

        // Con ignoreErrors las filas con error se omiten y se aplica el resto.
        ImportDtos.JobDto applying = importService.apply(validated.id(), true);
        assertThat(applying.status()).isEqualTo(ImportStatus.APPLYING);
        assertThat(applying.errorRows()).isZero();

        applyService.run(applying.id(), tenantA, adminA.id(), branchAccess.accessibleBranches());

        ImportDtos.JobDto applied = importService.get(applying.id());
        assertThat(applied.status()).isEqualTo(ImportStatus.APPLIED);
        assertThat(applied.appliedAt()).isNotNull();
        assertThat(applied.progressPct()).isEqualTo(100);
        ImportDtos.ResultDto result = applied.result();
        assertThat(result.productsCreated()).isEqualTo(4);   // leche, arroz, queso y el del EAN inválido
        assertThat(result.lotsCreated()).isEqualTo(5);       // la leche entra con dos lotes
        assertThat(result.unitsLoaded()).isEqualTo(10 + 5 + 20 + 2 + 4);
        assertThat(result.categoriesCreated()).isEqualTo(2); // Lacteos y Almacen (Bebidas venía en una fila omitida)
        assertThat(result.suppliersCreated()).isEqualTo(1);
        assertThat(result.rowsFailed()).isZero();

        // El catálogo quedó cargado y los dos lotes de la leche respetan el orden del archivo (FIFO).
        assertThat(jdbc.queryForObject("select count(*) from products where tenant_id = ?", Long.class, tenantA))
                .isEqualTo(4);
        List<String> lots = jdbc.queryForList("""
                select l.lot_number from lots l join products p on p.id = l.product_id
                 where l.tenant_id = ? and p.barcode = 'TESTLECHE' order by l.received_at, l.id
                """, String.class, tenantA);
        assertThat(lots).containsExactly("L1", "L2");
        assertThat(jdbc.queryForObject("""
                select count(*) from stock_movements where tenant_id = ? and source = 'IMPORT' and type = 'ENTRY'
                """, Long.class, tenantA)).isEqualTo(5);

        // Las filas quedan marcadas con el producto y el lote que crearon.
        List<ImportRowStore.StoredRow> rows = rowStore.allRows(applied.id());
        assertThat(rows).filteredOn(r -> r.status() == ImportRowStatus.IMPORTED).hasSize(5);
        assertThat(rows).filteredOn(r -> r.status() == ImportRowStatus.SKIPPED).hasSize(3);
        assertThat(rows.getFirst().productId()).isNotNull();
        assertThat(rows.getFirst().lotId()).isNotNull();

        // Reimportar el mismo archivo actualiza en vez de duplicar.
        ImportDtos.JobDto second = validate(upload(csv()));
        assertThat(second.preview().productsToUpdate()).isPositive();
        assertThat(second.preview().productsToCreate()).isZero();
    }

    @Test
    void marksTheRowAsFailedInsteadOfBreakingTheWholeBlock() {
        // Un producto dado de baja hace que StockService rechace el ingreso: la fila falla, el resto sigue.
        ImportDtos.JobDto job = validate(upload(csv()));
        importService.bulk(job.id(), new ImportDtos.BulkRowsRequest(null, ImportRowStatus.ERROR, "SKIP", null, null));
        ImportDtos.JobDto applying = importService.apply(job.id(), false);
        jdbc.update("update import_jobs set options = jsonb_set(options, '{defaultBranchId}', ?::jsonb) where id = ?",
                String.valueOf(branchB), applying.id());

        applyService.run(applying.id(), tenantA, adminA.id(), List.of());

        ImportDtos.JobDto applied = importService.get(applying.id());
        assertThat(applied.status()).isEqualTo(ImportStatus.APPLIED);
        assertThat(applied.result().rowsFailed()).isPositive();
        assertThat(rowStore.allRows(applied.id()))
                .filteredOn(r -> r.status() == ImportRowStatus.FAILED).isNotEmpty();
    }

    @Test
    void reusesCatalogNamesWithoutAccentsAndReactivatesDeletedProducts() {
        jdbc.update("insert into categories (tenant_id, name) values (?, 'Almacén')", tenantA);
        data.supplier(tenantA, "Distribuidora Pérez");
        long deleted = data.product(tenantA, "7790000000010", "Yerba de baja", "900", "1500");
        jdbc.update("update products set active = false where id = ?", deleted);
        String csv = "Código;Producto;Rubro;Proveedor;Cantidad\r\n"
                + "7790000000010;Yerba reactivada;ALMACEN;distribuidora perez;6\r\n"
                + ";Galletitas sin código;almacen;Distribuidora Perez;3\r\n";

        ImportDtos.JobDto job = validate(upload(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertThat(job.errorRows()).isZero();
        assertThat(job.preview().categoriesToCreate()).isEmpty();
        assertThat(job.preview().suppliersToCreate()).isEmpty();
        assertMessages(job.id(), 1, ImportRowStatus.WARNING, null, "estaba dado de baja");

        applyService.run(importService.apply(job.id(), false).id(), tenantA, adminA.id(),
                branchAccess.accessibleBranches());

        ImportDtos.ResultDto result = importService.get(job.id()).result();
        assertThat(result.categoriesCreated()).isZero();
        assertThat(result.suppliersCreated()).isZero();
        assertThat(result.productsUpdated()).isEqualTo(1);
        assertThat(result.productsCreated()).isEqualTo(1);
        assertThat(result.unitsLoaded()).isEqualTo(9);
        assertThat(jdbc.queryForObject("select count(*) from categories where tenant_id = ?", Long.class, tenantA))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("select active from products where id = ?", Boolean.class, deleted)).isTrue();

        // Sin «Actualizar los existentes», cargar stock a un producto dado de baja es un error de la fila.
        jdbc.update("update products set active = false where id = ?", deleted);
        ImportDtos.JobDto second = importService.saveMapping(upload(csv.getBytes(
                java.nio.charset.StandardCharsets.UTF_8)).id(), new ImportDtos.MappingRequest(
                job.columnMapping(), new ImportDtos.OptionsRequest(false, true, true, true, centro, "DMY")));
        assertMessages(second.id(), 1, ImportRowStatus.ERROR, "quantity", "está dado de baja");
    }

    @Test
    void warnsAboutRecallsAndLeavesTheLotInQuarantine() {
        String barcode = data.barcode();
        long recall = data.recall(barcode, false, null, null, "PUBLISHED", "R-77/a");
        try {
            String csv = "Código;Producto;Cantidad;Lote;Vencimiento\r\n"
                    + barcode + ";Sopa de tomate test;5;r77a;10/12/2026\r\n"
                    + barcode + ";Sopa de tomate test;3;OTRO;10/12/2026\r\n";

            ImportDtos.JobDto job = validate(upload(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

            assertMessages(job.id(), 1, ImportRowStatus.WARNING, "lotNumber", "alerta de recall");
            assertThat(job.preview().recallWarnings()).hasSize(1).first().asString().startsWith("Fila 1");

            applyService.run(importService.apply(job.id(), false).id(), tenantA, adminA.id(),
                    branchAccess.accessibleBranches());

            assertThat(importService.get(job.id()).result().recallMatches()).isEqualTo(1);
            Long lotId = rowStore.allRows(job.id()).getFirst().lotId();
            assertThat(jdbc.queryForObject("select status from lots where id = ?", String.class, lotId))
                    .isEqualTo("RECALLED");
        } finally {
            jdbc.update("delete from announcements where id = ?", recall);
        }
    }

    @Test
    void keepsEachTenantInsideItsOwnImports() {
        ImportDtos.JobDto job = validate(upload(csv()));

        as(adminB);
        assertApiError(() -> importService.get(job.id()), 404, "NOT_FOUND");
        assertApiError(() -> importService.rows(job.id(), "ALL", null, 0, 20), 404, "NOT_FOUND");
        assertApiError(() -> importService.patchRow(job.id(), 1L,
                new ImportDtos.RowPatchRequest(Map.of("name", "Hackeado"))), 404, "NOT_FOUND");
        assertApiError(() -> importService.bulk(job.id(),
                new ImportDtos.BulkRowsRequest(null, null, "SKIP", null, null)), 404, "NOT_FOUND");
        assertApiError(() -> importService.apply(job.id(), true), 404, "NOT_FOUND");
        assertApiError(() -> importService.cancel(job.id()), 404, "NOT_FOUND");
        assertApiError(() -> fileService.errorsCsv(job.id()), 404, "NOT_FOUND");
        assertThat(importService.list(0, 20).content()).extracting(ImportDtos.JobSummaryDto::id)
                .doesNotContain(job.id());
    }

    @Test
    void rejectsADefaultBranchOfAnotherTenant() {
        ImportDtos.JobDto uploaded = upload(csv());

        assertApiError(() -> importService.saveMapping(uploaded.id(), new ImportDtos.MappingRequest(
                        uploaded.suggestedMapping(),
                        new ImportDtos.OptionsRequest(true, true, true, true, branchB, "DMY"))),
                404, "NOT_FOUND");
    }

    @Test
    void exportsOnlyTheBranchesInScope() {
        ImportDtos.JobDto job = validate(upload(csv()));
        importService.bulk(job.id(), new ImportDtos.BulkRowsRequest(null, ImportRowStatus.ERROR, "SKIP", null, null));
        applyService.run(importService.apply(job.id(), false).id(), tenantA, adminA.id(),
                branchAccess.accessibleBranches());

        String csv = new String(fileService.export("csv", true).content(), java.nio.charset.StandardCharsets.UTF_8);

        assertThat(csv).contains("Código de barras;Nombre;Marca;Categoría");
        assertThat(csv).contains("Sucursal Centro").contains("Sucursal Norte");
        assertThat(csv).doesNotContain("Sucursal Ajena");
        // El catálogo exportado se puede volver a importar: mismas columnas que la plantilla.
        assertThat(csv.lines().findFirst().orElseThrow().replace("﻿", ""))
                .isEqualTo(new String(fileService.template("csv").content(),
                        java.nio.charset.StandardCharsets.UTF_8).lines().findFirst().orElseThrow()
                        .replace("﻿", ""));
    }

    @Test
    void exportsTheCatalogToExcelWithNumbersAndDatesThatReimportCleanly() throws Exception {
        ImportDtos.JobDto job = validate(upload(csv()));
        importService.bulk(job.id(), new ImportDtos.BulkRowsRequest(null, ImportRowStatus.ERROR, "SKIP", null, null));
        applyService.run(importService.apply(job.id(), false).id(), tenantA, adminA.id(),
                branchAccess.accessibleBranches());

        byte[] xlsx = fileService.export("xlsx", true).content();

        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(xlsx))) {
            var sheet = workbook.getSheet("Productos");
            org.apache.poi.ss.usermodel.Row milk = null;
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                var row = sheet.getRow(i);
                var lot = row.getCell(ImportField.LOT_NUMBER.ordinal());
                if (lot != null && "L1".equals(lot.getStringCellValue())) {
                    milk = row;
                }
            }
            assertThat(milk).as("fila del lote L1").isNotNull();
            assertThat(milk.getCell(ImportField.BARCODE.ordinal()).getStringCellValue()).isEqualTo("TESTLECHE");
            var cost = milk.getCell(ImportField.COST_PRICE.ordinal());
            assertThat(cost.getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.NUMERIC);
            assertThat(cost.getNumericCellValue()).isEqualTo(950d);
            assertThat(milk.getCell(ImportField.MIN_STOCK.ordinal()).getCellType())
                    .isEqualTo(org.apache.poi.ss.usermodel.CellType.NUMERIC);
            assertThat(milk.getCell(ImportField.QUANTITY.ordinal()).getNumericCellValue()).isEqualTo(10d);
            var expiry = milk.getCell(ImportField.EXPIRY_DATE.ordinal());
            assertThat(org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(expiry)).isTrue();
            assertThat(expiry.getLocalDateTimeCellValue().toLocalDate()).isEqualTo(java.time.LocalDate.of(2026, 10, 15));
        }

        // Reimportar la planilla exportada (sin cargar stock) actualiza el mismo catálogo sin errores.
        ImportDtos.JobDto uploaded = importService.upload(
                new MockMultipartFile("file", "catalogo.xlsx", ImportFileService.XLSX_TYPE, xlsx), null);
        ImportDtos.JobDto reimport = importService.saveMapping(uploaded.id(), new ImportDtos.MappingRequest(
                uploaded.suggestedMapping(), new ImportDtos.OptionsRequest(true, true, true, false, centro, "DMY")));
        assertThat(reimport.errorRows()).isZero();
        assertThat(reimport.preview().productsToCreate()).isZero();
        ImportRowStore.StoredRow first = rowStore.allRows(reimport.id()).stream()
                .filter(r -> "L1".equals(r.data().get("lotNumber"))).findFirst().orElseThrow();
        assertThat(com.gondolia.imports.parse.ImportValues.number(first.data().get("costPrice")))
                .hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("950"));
        assertThat(first.data().get("expiryDate")).isEqualTo("15/10/2026");
    }

    @Test
    void writesAnErrorsCsvWithTheReasonOfEachCell() {
        ImportDtos.JobDto job = validate(upload(csv()));

        String csv = new String(fileService.errorsCsv(job.id()).content(), java.nio.charset.StandardCharsets.UTF_8);

        assertThat(csv).contains("Fila;Estado;Problemas");
        assertThat(csv).contains("Falta el nombre del producto.");
        assertThat(csv).contains("Error en Precio de venta");
        assertThat(csv).contains("Advertencia en Fecha de vencimiento");
    }

    @Test
    void cancelsAnImportThatWasNotApplied() {
        ImportDtos.JobDto job = validate(upload(csv()));

        assertThat(importService.cancel(job.id()).status()).isEqualTo(ImportStatus.CANCELLED);
        assertApiError(() -> importService.apply(job.id(), true), 409, "CONFLICT");
    }

    @Test
    void requiresTheNameColumn() {
        ImportDtos.JobDto uploaded = upload(csv());
        Map<String, String> mapping = new LinkedHashMap<>(uploaded.suggestedMapping());
        mapping.remove(ImportField.NAME.key());

        assertApiError(() -> importService.saveMapping(uploaded.id(),
                new ImportDtos.MappingRequest(mapping, null)), 400, "VALIDATION_ERROR");
    }

    @Test
    void rejectsAColumnThatIsNotInTheFile() {
        ImportDtos.JobDto uploaded = upload(csv());
        Map<String, String> mapping = new LinkedHashMap<>(uploaded.suggestedMapping());
        mapping.put(ImportField.BRAND.key(), "Columna inventada");

        assertApiError(() -> importService.saveMapping(uploaded.id(),
                new ImportDtos.MappingRequest(mapping, null)), 400, "VALIDATION_ERROR");
    }

    // -----------------------------------------------------------------------
    // Utilidades
    // -----------------------------------------------------------------------

    /**
     * Planilla de prueba con problemas a propósito: fila 2 repite el producto de la fila 1 con otro lote, la 4 no
     * tiene nombre, la 5 tiene un precio que no es número, la 6 una cantidad decimal, la 7 un vencimiento pasado y
     * la 8 una sucursal inexistente. El bloque vacío del medio se ignora.
     */
    private byte[] csv() {
        String csv = HEADERS + "\r\n"
                + "TESTLECHE;Leche test;Lacteos;Prov Test;950,00;1.350,00;10;L1;15/10/2026;01/09/2026;Sucursal Centro\r\n"
                + "TESTLECHE;Leche test;Lacteos;Prov Test;950,00;1.390,00;5;L2;20/10/2026;01/09/2026;Sucursal Centro\r\n"
                + "TESTARROZ;Arroz test;Almacen;Prov Test;800;1.200;20;;;;Sucursal Norte\r\n"
                + "\r\n"
                + "TESTSINNOMBRE;;Almacen;;100;200;5;;;;Sucursal Centro\r\n"
                + "TESTFIDEOS;Fideos test;Almacen;;500;mil doscientos;8;;;;Sucursal Centro\r\n"
                + "7791111000000;Aceite test;Almacen;;1.000;1.500;3,5;;;;Sucursal Centro\r\n"
                + "TESTQUESO;Queso test;Lacteos;;1.000;1.500;2;Q1;10/01/2020;;Sucursal Centro\r\n"
                + "TESTAGUA;Agua test;Bebidas;;100;80;4;;;;Sucursal Oeste\r\n";
        return csv.getBytes(Charset.forName("windows-1252"));
    }

    private ImportDtos.JobDto upload(byte[] content) {
        return importService.upload(
                new MockMultipartFile("file", "listado.csv", "text/csv", content), null);
    }

    private ImportDtos.JobDto validate(ImportDtos.JobDto uploaded) {
        return importService.saveMapping(uploaded.id(), new ImportDtos.MappingRequest(uploaded.suggestedMapping(),
                new ImportDtos.OptionsRequest(true, true, true, true, centro, "DMY")));
    }

    private Long rowId(Long jobId, int rowNumber) {
        return rowStore.allRows(jobId).stream().filter(r -> r.rowNumber() == rowNumber).findFirst().orElseThrow().id();
    }

    private void assertMessages(Long jobId, int rowNumber, ImportRowStatus status, String field, String text) {
        ImportRowStore.StoredRow row = rowStore.allRows(jobId).stream()
                .filter(r -> r.rowNumber() == rowNumber).findFirst().orElseThrow();
        assertThat(row.status()).as("estado de la fila %s", rowNumber).isEqualTo(status);
        assertThat(row.messages()).as("mensajes de la fila %s: %s", rowNumber, row.messages())
                .anySatisfy(message -> {
                    assertThat(message.field()).isEqualTo(field);
                    assertThat(message.message()).contains(text);
                });
    }

    private static void as(AuthUser user) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(user, null, user.authorities()));
    }

    private static void assertApiError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, int status,
                                       String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.getStatus().value()).isEqualTo(status);
            assertThat(ex.getCode()).isEqualTo(code);
        });
    }
}
