package com.gondolia.imports.dto;

import com.gondolia.domain.imports.ImportFileFormat;
import com.gondolia.domain.imports.ImportRowAction;
import com.gondolia.domain.imports.ImportRowStatus;
import com.gondolia.domain.imports.ImportStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * DTOs de la importación masiva (SPEC §16.3). Todos los textos que ve el usuario están en español.
 */
public final class ImportDtos {

    private ImportDtos() {
    }

    /** Campo importable del catálogo ({@code GET /api/tenant/imports/fields}). */
    public record FieldDto(String key, String label, boolean required, String type, String description,
                           List<String> synonyms, String group) {
    }

    /** Opciones de la importación (SPEC §16.2). */
    public record OptionsDto(boolean updateExisting, boolean createCategories, boolean createSuppliers,
                             boolean importStock, Long defaultBranchId, String defaultBranchName, String dateFormat) {
    }

    /** Mensaje de validación de una celda. */
    public record RowMessageDto(String field, String level, String message) {
    }

    /** Fila de la importación. */
    public record RowDto(Long id, int rowNumber, Map<String, String> raw, Map<String, String> data,
                         ImportRowStatus status, ImportRowAction action, List<RowMessageDto> messages,
                         Long productId, Long lotId) {
    }

    /** Impacto estimado de aplicar la importación (paso «Confirmar»). */
    public record PreviewDto(int productsToCreate, int productsToUpdate, int lotsToCreate, long unitsToLoad,
                             List<String> categoriesToCreate, List<String> suppliersToCreate, int rowsToSkip,
                             int rowsWithErrors, List<String> recallWarnings) {
    }

    /** Resultado de la aplicación (SPEC §16.3). */
    public record ResultDto(int productsCreated, int productsUpdated, int lotsCreated, long unitsLoaded,
                            int categoriesCreated, int suppliersCreated, int rowsSkipped, int rowsFailed,
                            int recallMatches) {

        public static ResultDto empty() {
            return new ResultDto(0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    /** Importación completa. */
    public record JobDto(Long id, ImportStatus status, String fileName, ImportFileFormat fileFormat, String sheetName,
                         List<String> sheetNames, List<String> headers, Map<String, String> suggestedMapping,
                         Map<String, String> columnMapping, OptionsDto options, int totalRows, int validRows,
                         int warningRows, int errorRows, int skippedRows, int processedRows, int progressPct,
                         ResultDto result, String errorMessage, Instant createdAt, String createdByName,
                         Instant appliedAt, List<Map<String, String>> sampleRows, PreviewDto preview) {
    }

    /** Fila del historial (`GET /api/tenant/imports`). */
    public record JobSummaryDto(Long id, ImportStatus status, String fileName, ImportFileFormat fileFormat,
                                int totalRows, int validRows, int warningRows, int errorRows, int skippedRows,
                                int progressPct, ResultDto result, Instant createdAt, String createdByName,
                                Instant appliedAt) {
    }

    // -----------------------------------------------------------------------
    // Requests
    // -----------------------------------------------------------------------

    /** {@code PUT /api/tenant/imports/{id}/mapping}. */
    public record MappingRequest(Map<String, String> columnMapping, OptionsRequest options) {
    }

    /** Opciones enviadas por el frontend (los booleanos nulos toman el valor por defecto). */
    public record OptionsRequest(Boolean updateExisting, Boolean createCategories, Boolean createSuppliers,
                                 Boolean importStock, Long defaultBranchId,
                                 @Size(max = 3, message = "tiene que ser DMY, MDY o YMD") String dateFormat) {
    }

    /** {@code PATCH /api/tenant/imports/{id}/rows/{rowId}}. */
    public record RowPatchRequest(Map<String, String> data) {
    }

    /** {@code POST /api/tenant/imports/{id}/rows/bulk}. */
    public record BulkRowsRequest(List<Long> rowIds, ImportRowStatus filterStatus,
                                  @NotBlank(message = "es obligatoria") String action, String field, String value) {
    }

    /** Resultado de una acción masiva. */
    public record BulkRowsResponse(int affectedRows, JobDto job) {
    }

    /** {@code POST /api/tenant/imports/{id}/apply}. */
    public record ApplyRequest(Boolean ignoreErrors) {

        public boolean ignoreErrorsOrFalse() {
            return Boolean.TRUE.equals(ignoreErrors);
        }
    }
}
