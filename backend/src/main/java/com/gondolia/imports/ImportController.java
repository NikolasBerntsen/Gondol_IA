package com.gondolia.imports;

import com.gondolia.common.PageResponse;
import com.gondolia.imports.dto.ImportDtos;
import com.gondolia.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Importación masiva de productos y stock desde Excel o CSV (SPEC §16.3). Todo el módulo es de
 * {@code TENANT_ADMIN} y siempre está incluido: no depende de ningún módulo por comercio (SPEC §14.1).
 */
@RestController
@RequestMapping("/api/tenant/imports")
@PreAuthorize(Roles.TENANT_ADMIN)
@RequiredArgsConstructor
@Tag(name = "Importación masiva", description = "Carga de productos y stock desde una planilla Excel o CSV")
public class ImportController {

    private final ImportService importService;
    private final ImportFileService fileService;

    // -----------------------------------------------------------------------
    // Campos y archivos
    // -----------------------------------------------------------------------

    @GetMapping("/fields")
    @Operation(summary = "Campos importables con sus sinónimos de encabezado")
    public List<ImportDtos.FieldDto> fields() {
        return importService.fields();
    }

    @GetMapping("/template")
    @Operation(summary = "Plantilla en blanco con instrucciones y filas de ejemplo")
    public ResponseEntity<byte[]> template(@RequestParam(defaultValue = "xlsx") String format) {
        return download(fileService.template(format));
    }

    @GetMapping("/export")
    @Operation(summary = "Catálogo actual con las mismas columnas de la plantilla")
    public ResponseEntity<byte[]> export(@RequestParam(defaultValue = "xlsx") String format,
                                         @RequestParam(defaultValue = "true") boolean includeStock) {
        return download(fileService.export(format, includeStock));
    }

    @GetMapping(value = "/{id}/errors.csv", produces = "text/csv")
    @Operation(summary = "CSV con las filas con error o advertencia y su motivo")
    public ResponseEntity<byte[]> errorsCsv(@PathVariable Long id) {
        return download(fileService.errorsCsv(id));
    }

    // -----------------------------------------------------------------------
    // Importaciones
    // -----------------------------------------------------------------------

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Subir la planilla y detectar encabezados")
    public ImportDtos.JobDto upload(@RequestPart("file") MultipartFile file,
                                    @RequestPart(value = "sheetName", required = false) String sheetName) {
        return importService.upload(file, sheetName);
    }

    @GetMapping
    @Operation(summary = "Historial de importaciones del comercio")
    public PageResponse<ImportDtos.JobSummaryDto> list(@RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        return importService.list(page, size);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Detalle de una importación")
    public ImportDtos.JobDto get(@PathVariable Long id) {
        return importService.get(id);
    }

    @PutMapping("/{id}/mapping")
    @Operation(summary = "Guardar el mapeo de columnas y validar todas las filas")
    public ImportDtos.JobDto mapping(@PathVariable Long id, @Valid @RequestBody ImportDtos.MappingRequest request) {
        return importService.saveMapping(id, request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Cancelar una importación que todavía no se aplicó")
    public ImportDtos.JobDto cancel(@PathVariable Long id) {
        return importService.cancel(id);
    }

    // -----------------------------------------------------------------------
    // Filas
    // -----------------------------------------------------------------------

    @GetMapping("/{id}/rows")
    @Operation(summary = "Filas de la importación, filtrables por estado y texto")
    public PageResponse<ImportDtos.RowDto> rows(@PathVariable Long id,
                                                @RequestParam(defaultValue = "ALL") String status,
                                                @RequestParam(required = false) String q,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "50") int size) {
        return importService.rows(id, status, q, page, size);
    }

    @PatchMapping("/{id}/rows/{rowId}")
    @Operation(summary = "Corregir una celda y revalidar la fila")
    public Map<String, Object> patchRow(@PathVariable Long id, @PathVariable Long rowId,
                                        @Valid @RequestBody ImportDtos.RowPatchRequest request) {
        return importService.patchRow(id, rowId, request);
    }

    @PostMapping("/{id}/rows/bulk")
    @Operation(summary = "Acciones masivas sobre las filas seleccionadas o filtradas")
    public ImportDtos.BulkRowsResponse bulk(@PathVariable Long id,
                                            @Valid @RequestBody ImportDtos.BulkRowsRequest request) {
        return importService.bulk(id, request);
    }

    @PostMapping("/{id}/apply")
    @Operation(summary = "Aplicar la importación (asincrónica, en bloques de 200 filas)")
    public ImportDtos.JobDto apply(@PathVariable Long id,
                                   @RequestBody(required = false) ImportDtos.ApplyRequest request) {
        return importService.apply(id, request != null && request.ignoreErrorsOrFalse());
    }

    // -----------------------------------------------------------------------

    private ResponseEntity<byte[]> download(ImportFileService.GeneratedFile file) {
        String encoded = URLEncoder.encode(file.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, file.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + file.fileName() + "\"; filename*=UTF-8''" + encoded)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(file.content());
    }
}
