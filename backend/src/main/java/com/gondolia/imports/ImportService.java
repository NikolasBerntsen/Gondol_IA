package com.gondolia.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.gondolia.common.PageResponse;
import com.gondolia.common.error.BadRequestException;
import com.gondolia.common.error.ConflictException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.common.error.NotFoundException;
import com.gondolia.domain.imports.ImportJob;
import com.gondolia.domain.imports.ImportJobRepository;
import com.gondolia.domain.imports.ImportRowAction;
import com.gondolia.domain.imports.ImportRowStatus;
import com.gondolia.domain.imports.ImportStatus;
import com.gondolia.domain.imports.ImportType;
import com.gondolia.domain.inventory.CategoryRepository;
import com.gondolia.domain.inventory.SupplierRepository;
import com.gondolia.domain.user.User;
import com.gondolia.domain.user.UserRepository;
import com.gondolia.imports.dto.ImportDtos;
import com.gondolia.imports.parse.ImportField;
import com.gondolia.imports.parse.ImportValues;
import com.gondolia.imports.parse.ParsedSheet;
import com.gondolia.imports.parse.SpreadsheetParser;
import com.gondolia.imports.validate.ImportOptions;
import com.gondolia.imports.validate.ImportValidator;
import com.gondolia.imports.validate.RowValidation;
import com.gondolia.realtime.RealtimePublisher;
import com.gondolia.security.BranchAccessService;
import com.gondolia.security.BranchAccessService.BranchRef;
import com.gondolia.security.CurrentUser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * Importación masiva de productos y stock (SPEC §16): subida y parseo del archivo, mapeo de columnas, validación
 * fila por fila, correcciones y acciones masivas, y disparo de la aplicación asincrónica.
 */
@Service
@RequiredArgsConstructor
public class ImportService {

    /** Filas que se aplican por transacción (SPEC §16.3). */
    public static final int APPLY_CHUNK_SIZE = 200;

    private final ImportJobRepository jobRepository;
    private final ImportRowStore rowStore;
    private final ImportValidator validator;
    private final SpreadsheetParser parser;
    private final BranchAccessService branchAccess;
    private final UserRepository userRepository;
    private final CategoryRepository categoryRepository;
    private final SupplierRepository supplierRepository;
    private final ImportApplyService applyService;
    private final RealtimePublisher realtimePublisher;

    // -----------------------------------------------------------------------
    // Catálogo de campos
    // -----------------------------------------------------------------------

    /** Campos importables con sus sinónimos (SPEC §16.1). */
    public List<ImportDtos.FieldDto> fields() {
        List<ImportDtos.FieldDto> fields = new ArrayList<>();
        for (ImportField field : ImportField.values()) {
            fields.add(new ImportDtos.FieldDto(field.key(), field.label(), field.required(),
                    field.type().jsonName(), field.description(), field.synonyms(),
                    field.isProductField() ? "producto" : "stock"));
        }
        return fields;
    }

    // -----------------------------------------------------------------------
    // Subida
    // -----------------------------------------------------------------------

    @Transactional
    public ImportDtos.JobDto upload(MultipartFile file, String sheetName) {
        Long tenantId = CurrentUser.tenantId();
        if (file == null || file.isEmpty()) {
            throw new BadRequestException(ErrorCodes.INVALID_FILE, "Elegí un archivo Excel o CSV para importar.");
        }
        if (file.getSize() > SpreadsheetParser.MAX_FILE_SIZE) {
            throw new BadRequestException(ErrorCodes.INVALID_FILE, "El archivo supera el máximo de 10 MB.");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (java.io.IOException e) {
            throw new BadRequestException(ErrorCodes.INVALID_FILE, "No pudimos leer el archivo. Volvé a subirlo.");
        }
        String fileName = cleanFileName(file.getOriginalFilename());
        ParsedSheet sheet = parser.parse(content, fileName, sheetName);

        ImportJob job = new ImportJob();
        job.setTenantId(tenantId);
        job.setType(ImportType.PRODUCTS);
        job.setStatus(ImportStatus.UPLOADED);
        job.setFileName(fileName);
        job.setFileFormat(sheet.format());
        job.setSheetName(sheet.sheetName());
        job.setSheetNames(rowStore.toNode(sheet.sheetNames()));
        job.setHeaders(rowStore.toNode(sheet.headers()));
        job.setColumnMapping(rowStore.toNode(suggestedMapping(sheet.headers())));
        job.setOptions(rowStore.toNode(defaultOptions().toDto(null)));
        job.setTotalRows(sheet.rows().size());
        job.setCreatedBy(CurrentUser.id());
        jobRepository.save(job);
        rowStore.insertRaw(job.getId(), sheet.headers(), sheet.rows());
        return toDto(job);
    }

    /** Opciones iniciales: si el usuario ya eligió una sucursal, va como sucursal por defecto. */
    private ImportOptions defaultOptions() {
        ImportOptions defaults = ImportOptions.defaults();
        List<BranchRef> branches = branchAccess.accessibleBranches();
        Long branchId = branchAccess.requestedBranchId()
                .filter(id -> branches.stream().anyMatch(b -> Objects.equals(b.id(), id)))
                .orElse(branches.size() == 1 ? branches.getFirst().id() : null);
        return new ImportOptions(defaults.updateExisting(), defaults.createCategories(), defaults.createSuppliers(),
                defaults.importStock(), branchId, defaults.dateFormat());
    }

    private Map<String, String> suggestedMapping(List<String> headers) {
        Map<ImportField, String> suggestion =
                com.gondolia.imports.parse.HeaderMatching.suggest(headers);
        Map<String, String> mapping = new LinkedHashMap<>();
        for (ImportField field : ImportField.values()) {
            String header = suggestion.get(field);
            if (header != null) {
                mapping.put(field.key(), header);
            }
        }
        return mapping;
    }

    // -----------------------------------------------------------------------
    // Mapeo y validación
    // -----------------------------------------------------------------------

    @Transactional
    public ImportDtos.JobDto saveMapping(Long id, ImportDtos.MappingRequest request) {
        ImportJob job = editableJob(id);
        List<String> headers = headers(job);
        Map<String, String> mapping = normalizeMapping(request == null ? null : request.columnMapping(), headers);
        if (!mapping.containsKey(ImportField.NAME.key())) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR,
                    "Elegí qué columna del archivo tiene el nombre del producto: es el único campo obligatorio.");
        }
        ImportOptions options = currentOptions(job).merge(request == null ? null : request.options());
        List<BranchRef> branches = branchAccess.accessibleBranches();
        if (options.defaultBranchId() != null) {
            branchAccess.assertAccess(options.defaultBranchId());
        }
        job.setColumnMapping(rowStore.toNode(mapping));
        job.setOptions(rowStore.toNode(options.toDto(branchName(branches, options.defaultBranchId()))));

        ImportValidator.Run run = validator.start(job.getTenantId(), options, branches, false);
        List<ImportRowStore.StoredRow> rows = rowStore.allRows(job.getId());
        List<ImportRowStore.RowUpdate> updates = new ArrayList<>(rows.size());
        for (ImportRowStore.StoredRow row : rows) {
            Map<String, String> data = mapRow(row.raw(), mapping);
            RowValidation validation = run.validate(row.rowNumber(), data, false);
            updates.add(new ImportRowStore.RowUpdate(row.id(), data, validation.status(), validation.action(),
                    validation.messageDtos()));
        }
        rowStore.applyValidation(updates);
        job.setStatus(ImportStatus.VALIDATED);
        job.setProcessedRows(0);
        job.setResult(null);
        job.setErrorMessage(null);
        refreshCounters(job);
        jobRepository.save(job);
        return toDto(job);
    }

    /** Aplica el mapeo a la fila cruda: {@code {fieldKey: texto de la celda}}. */
    private Map<String, String> mapRow(Map<String, String> raw, Map<String, String> mapping) {
        Map<String, String> data = new LinkedHashMap<>();
        for (ImportField field : ImportField.values()) {
            String header = mapping.get(field.key());
            if (header == null) {
                continue;
            }
            String value = ImportValues.text(raw.get(header));
            data.put(field.key(), value == null ? "" : value);
        }
        return data;
    }

    /** Mapeo {@code {fieldKey: header}} validado contra los campos y encabezados reales. */
    private Map<String, String> normalizeMapping(Map<String, String> requested, List<String> headers) {
        Map<String, String> mapping = new LinkedHashMap<>();
        if (requested == null) {
            return mapping;
        }
        Set<String> used = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : requested.entrySet()) {
            Optional<ImportField> field = ImportField.byKey(entry.getKey());
            String header = ImportValues.text(entry.getValue());
            if (field.isEmpty() || header == null) {
                continue;
            }
            if (!headers.contains(header)) {
                throw new BadRequestException(ErrorCodes.VALIDATION_ERROR,
                        "La columna «" + header + "» no está en el archivo.");
            }
            if (!used.add(header)) {
                throw new BadRequestException(ErrorCodes.VALIDATION_ERROR,
                        "La columna «" + header + "» está asignada a más de un campo.");
            }
            mapping.put(field.get().key(), header);
        }
        return mapping;
    }

    // -----------------------------------------------------------------------
    // Filas
    // -----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<ImportDtos.RowDto> rows(Long id, String status, String query, int page, int size) {
        ImportJob job = job(id);
        ImportRowStatus filter = rowStatus(status);
        int pageSize = Math.clamp(size, 1, 200);
        long total = rowStore.count(job.getId(), filter, query);
        List<ImportDtos.RowDto> content = rowStore.page(job.getId(), filter, query, Math.max(page, 0), pageSize)
                .stream().map(this::toRowDto).toList();
        return PageResponse.of(content, Math.max(page, 0), pageSize, total);
    }

    /** Edición inline de una celda: revalida la fila y actualiza los contadores del job. */
    @Transactional
    public Map<String, Object> patchRow(Long id, Long rowId, ImportDtos.RowPatchRequest request) {
        ImportJob job = editableJob(id);
        ImportRowStore.StoredRow row = rowStore.findRow(job.getId(), rowId)
                .orElseThrow(() -> new NotFoundException("La fila no existe en esta importación."));
        if (job.getStatus() != ImportStatus.VALIDATED) {
            throw new ConflictException(ErrorCodes.CONFLICT,
                    "Primero elegí las columnas: todavía no validamos las filas.");
        }
        Map<String, String> data = new LinkedHashMap<>(row.data());
        if (request != null && request.data() != null) {
            for (Map.Entry<String, String> entry : request.data().entrySet()) {
                Optional<ImportField> field = ImportField.byKey(entry.getKey());
                if (field.isEmpty()) {
                    throw new BadRequestException(ErrorCodes.VALIDATION_ERROR,
                            "El campo «" + entry.getKey() + "» no se puede importar.");
                }
                String value = ImportValues.text(entry.getValue());
                data.put(field.get().key(), value == null ? "" : value);
            }
        }
        ImportOptions options = currentOptions(job);
        ImportValidator.Run run = validator.start(job.getTenantId(), options, branchAccess.accessibleBranches(), true);
        RowValidation validation = run.validate(row.rowNumber(), data, false);
        rowStore.applyValidation(List.of(new ImportRowStore.RowUpdate(row.id(), data, validation.status(),
                validation.action(), validation.messageDtos())));
        refreshCounters(job);
        jobRepository.save(job);
        ImportRowStore.StoredRow updated = rowStore.findRow(job.getId(), rowId).orElseThrow();
        return Map.of("row", toRowDto(updated), "job", toDto(job));
    }

    /** Acciones masivas: omitir, restaurar o asignar el mismo valor a un campo en varias filas. */
    @Transactional
    public ImportDtos.BulkRowsResponse bulk(Long id, ImportDtos.BulkRowsRequest request) {
        ImportJob job = editableJob(id);
        if (job.getStatus() != ImportStatus.VALIDATED) {
            throw new ConflictException(ErrorCodes.CONFLICT,
                    "Primero elegí las columnas: todavía no validamos las filas.");
        }
        String action = request.action().trim().toUpperCase(Locale.ROOT);
        List<Long> ids = rowStore.resolveIds(job.getId(), request.rowIds(), request.filterStatus());
        if (ids.isEmpty()) {
            return new ImportDtos.BulkRowsResponse(0, toDto(job));
        }
        int affected;
        if ("SKIP".equals(action)) {
            affected = rowStore.skip(ids);
        } else if ("UNSKIP".equals(action) || "SET_FIELD".equals(action)) {
            ImportField field = null;
            String value = null;
            if ("SET_FIELD".equals(action)) {
                field = ImportField.byKey(request.field()).orElseThrow(() -> new BadRequestException(
                        ErrorCodes.VALIDATION_ERROR, "Elegí un campo válido para asignar."));
                value = ImportValues.text(request.value());
            }
            affected = revalidate(job, ids, field, value);
        } else {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR,
                    "La acción tiene que ser «SKIP», «UNSKIP» o «SET_FIELD».");
        }
        refreshCounters(job);
        jobRepository.save(job);
        return new ImportDtos.BulkRowsResponse(affected, toDto(job));
    }

    private int revalidate(ImportJob job, List<Long> ids, ImportField field, String value) {
        ImportOptions options = currentOptions(job);
        ImportValidator.Run run = validator.start(job.getTenantId(), options, branchAccess.accessibleBranches(), true);
        Set<Long> wanted = new LinkedHashSet<>(ids);
        List<ImportRowStore.RowUpdate> updates = new ArrayList<>();
        for (ImportRowStore.StoredRow row : rowStore.allRows(job.getId())) {
            if (!wanted.contains(row.id())) {
                continue;
            }
            Map<String, String> data = new LinkedHashMap<>(row.data());
            if (field != null) {
                data.put(field.key(), value == null ? "" : value);
            }
            RowValidation validation = run.validate(row.rowNumber(), data, false);
            updates.add(new ImportRowStore.RowUpdate(row.id(), data, validation.status(), validation.action(),
                    validation.messageDtos()));
        }
        rowStore.applyValidation(updates);
        return updates.size();
    }

    // -----------------------------------------------------------------------
    // Historial, aplicación y cancelación
    // -----------------------------------------------------------------------

    @Transactional(readOnly = true)
    public ImportDtos.JobDto get(Long id) {
        return toDto(job(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<ImportDtos.JobSummaryDto> list(int page, int size) {
        int pageSize = Math.clamp(size, 1, 100);
        return PageResponse.of(jobRepository.findByTenantIdOrderByCreatedAtDescIdDesc(CurrentUser.tenantId(),
                PageRequest.of(Math.max(page, 0), pageSize)), this::toSummary);
    }

    @Transactional
    public ImportDtos.JobDto apply(Long id, boolean ignoreErrors) {
        ImportJob job = editableJob(id);
        if (job.getStatus() != ImportStatus.VALIDATED) {
            throw new ConflictException(ErrorCodes.CONFLICT,
                    "Revisá las filas antes de importar: todavía falta elegir las columnas.");
        }
        refreshCounters(job);
        if (job.getErrorRows() > 0) {
            if (!ignoreErrors) {
                throw new ConflictException(ErrorCodes.IMPORT_HAS_ERRORS, "Hay " + job.getErrorRows()
                        + (job.getErrorRows() == 1 ? " fila con error" : " filas con error")
                        + ". Corregilas o marcá «Omitir las filas con error».");
            }
            rowStore.skip(rowStore.resolveIds(job.getId(), null, ImportRowStatus.ERROR));
            refreshCounters(job);
        }
        if (job.getValidRows() + job.getWarningRows() == 0) {
            throw new ConflictException(ErrorCodes.CONFLICT, "No queda ninguna fila para importar.");
        }
        job.setStatus(ImportStatus.APPLYING);
        job.setProcessedRows(0);
        job.setErrorMessage(null);
        jobRepository.save(job);

        Long jobId = job.getId();
        Long tenantId = job.getTenantId();
        Long userId = CurrentUser.id();
        List<BranchRef> branches = branchAccess.accessibleBranches();
        realtimePublisher.afterCommit(() -> applyService.runAsync(jobId, tenantId, userId, branches));
        return toDto(job);
    }

    /** Cancela una importación que todavía no se aplicó (o pide el corte de una en curso). */
    @Transactional
    public ImportDtos.JobDto cancel(Long id) {
        ImportJob job = job(id);
        if (job.getStatus() == ImportStatus.APPLIED) {
            throw new ConflictException(ErrorCodes.CONFLICT,
                    "Esta importación ya se aplicó: no se puede cancelar.");
        }
        if (job.getStatus() == ImportStatus.APPLYING) {
            applyService.requestCancel(job.getId());
            return toDto(job);
        }
        job.setStatus(ImportStatus.CANCELLED);
        jobRepository.save(job);
        return toDto(job);
    }

    // -----------------------------------------------------------------------
    // Acceso y contadores
    // -----------------------------------------------------------------------

    /** Importación del comercio del usuario actual (404 si es de otro comercio). */
    public ImportJob job(Long id) {
        return jobRepository.findByIdAndTenantId(id, CurrentUser.tenantId())
                .orElseThrow(() -> new NotFoundException("La importación no existe."));
    }

    private ImportJob editableJob(Long id) {
        ImportJob job = job(id);
        if (job.getStatus().isFinal() || job.getStatus() == ImportStatus.APPLYING) {
            throw new ConflictException(ErrorCodes.CONFLICT,
                    "Esta importación ya no se puede modificar (" + statusLabel(job.getStatus()) + ").");
        }
        return job;
    }

    private void refreshCounters(ImportJob job) {
        Map<ImportRowStatus, Integer> counts = rowStore.countsByStatus(job.getId());
        job.setValidRows(counts.getOrDefault(ImportRowStatus.VALID, 0));
        job.setWarningRows(counts.getOrDefault(ImportRowStatus.WARNING, 0));
        job.setErrorRows(counts.getOrDefault(ImportRowStatus.ERROR, 0));
        job.setSkippedRows(counts.getOrDefault(ImportRowStatus.SKIPPED, 0));
    }

    ImportOptions currentOptions(ImportJob job) {
        return ImportOptions.fromJson(job.getOptions());
    }

    // -----------------------------------------------------------------------
    // Mapeo a DTOs
    // -----------------------------------------------------------------------

    public ImportDtos.JobDto toDto(ImportJob job) {
        List<String> headers = headers(job);
        ImportOptions options = currentOptions(job);
        List<BranchRef> branches = branchAccess.accessibleBranches();
        List<Map<String, String>> sampleRows = rowStore.page(job.getId(), null, null, 0, 5).stream()
                .map(ImportRowStore.StoredRow::raw).toList();
        return new ImportDtos.JobDto(job.getId(), job.getStatus(), job.getFileName(), job.getFileFormat(),
                job.getSheetName(), stringList(job.getSheetNames()), headers, suggestedMapping(headers),
                stringMap(job.getColumnMapping()), options.toDto(branchName(branches, options.defaultBranchId())),
                job.getTotalRows(), job.getValidRows(), job.getWarningRows(), job.getErrorRows(), job.getSkippedRows(),
                job.getProcessedRows(), progressPct(job), result(job), job.getErrorMessage(), job.getCreatedAt(),
                userName(job.getCreatedBy()), job.getAppliedAt(), sampleRows,
                job.getStatus() == ImportStatus.VALIDATED ? preview(job, options) : null);
    }

    public ImportDtos.JobSummaryDto toSummary(ImportJob job) {
        return new ImportDtos.JobSummaryDto(job.getId(), job.getStatus(), job.getFileName(), job.getFileFormat(),
                job.getTotalRows(), job.getValidRows(), job.getWarningRows(), job.getErrorRows(), job.getSkippedRows(),
                progressPct(job), result(job), job.getCreatedAt(), userName(job.getCreatedBy()), job.getAppliedAt());
    }

    private ImportDtos.RowDto toRowDto(ImportRowStore.StoredRow row) {
        return new ImportDtos.RowDto(row.id(), row.rowNumber(), row.raw(), row.data(), row.status(), row.action(),
                row.messages(), row.productId(), row.lotId());
    }

    private int progressPct(ImportJob job) {
        int target = job.getValidRows() + job.getWarningRows() + job.getProcessedRows();
        if (job.getStatus() == ImportStatus.APPLIED) {
            return 100;
        }
        if (job.getStatus() != ImportStatus.APPLYING || target == 0) {
            return 0;
        }
        return Math.clamp(Math.round(job.getProcessedRows() * 100f / target), 0, 99);
    }

    /** Impacto estimado del paso «Confirmar» (SPEC §16.3). */
    private ImportDtos.PreviewDto preview(ImportJob job, ImportOptions options) {
        Set<String> existingCategories = new LinkedHashSet<>();
        categoryRepository.findByTenantIdOrderByNameAsc(job.getTenantId())
                .forEach(c -> existingCategories.add(ImportValues.slug(c.getName())));
        Set<String> existingSuppliers = new LinkedHashSet<>();
        supplierRepository.findByTenantIdOrderByNameAsc(job.getTenantId())
                .forEach(s -> existingSuppliers.add(ImportValues.slug(s.getName())));
        Map<String, String> newCategories = new LinkedHashMap<>();
        Map<String, String> newSuppliers = new LinkedHashMap<>();
        Set<String> recallWarnings = new LinkedHashSet<>();
        int toCreate = 0;
        int toUpdate = 0;
        int lots = 0;
        long units = 0;
        int skipped = 0;
        int errors = 0;
        for (ImportRowStore.StoredRow row : rowStore.allRows(job.getId())) {
            if (row.status() == ImportRowStatus.SKIPPED) {
                skipped++;
                continue;
            }
            if (row.status() == ImportRowStatus.ERROR) {
                errors++;
                continue;
            }
            if (!row.status().isApplicable()) {
                continue;
            }
            if (row.action() == ImportRowAction.CREATE) {
                toCreate++;
            } else if (row.action() == ImportRowAction.UPDATE) {
                toUpdate++;
            }
            var parsed = com.gondolia.imports.parse.ImportRowParser.parse(row.data(), options.dateFormat());
            if (options.importStock() && parsed.quantityOrZero() > 0) {
                lots++;
                units += parsed.quantityOrZero();
            }
            if (parsed.category() != null && !existingCategories.contains(ImportValues.slug(parsed.category()))) {
                newCategories.putIfAbsent(ImportValues.slug(parsed.category()), parsed.category());
            }
            if (parsed.supplier() != null && !existingSuppliers.contains(ImportValues.slug(parsed.supplier()))) {
                newSuppliers.putIfAbsent(ImportValues.slug(parsed.supplier()), parsed.supplier());
            }
            for (ImportDtos.RowMessageDto message : row.messages()) {
                if (message.message() != null && message.message().startsWith(ImportValidator.RECALL_MESSAGE_PREFIX)) {
                    recallWarnings.add("Fila " + row.rowNumber() + ": " + message.message());
                }
            }
        }
        return new ImportDtos.PreviewDto(toCreate, toUpdate, lots, units, List.copyOf(newCategories.values()),
                List.copyOf(newSuppliers.values()), skipped, errors, List.copyOf(recallWarnings));
    }

    private ImportDtos.ResultDto result(ImportJob job) {
        JsonNode node = job.getResult();
        if (node == null || node.isNull()) {
            return null;
        }
        return rowStore.fromNode(node, ImportDtos.ResultDto.class);
    }

    List<String> headers(ImportJob job) {
        return stringList(job.getHeaders());
    }

    private List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> values.add(item.asText()));
        }
        return values;
    }

    private Map<String, String> stringMap(JsonNode node) {
        Map<String, String> values = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.properties().forEach(entry -> {
                if (!entry.getValue().isNull()) {
                    values.put(entry.getKey(), entry.getValue().asText());
                }
            });
        }
        return values;
    }

    private String branchName(List<BranchRef> branches, Long branchId) {
        if (branchId == null) {
            return null;
        }
        return branches.stream().filter(b -> Objects.equals(b.id(), branchId)).map(BranchRef::name).findFirst()
                .orElse(null);
    }

    private String userName(Long userId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId).map(User::getFullName).orElse(null);
    }

    private ImportRowStatus rowStatus(String status) {
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status.trim())) {
            return null;
        }
        try {
            return ImportRowStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR,
                    "El estado tiene que ser ALL, VALID, WARNING, ERROR, SKIPPED, IMPORTED o FAILED.");
        }
    }

    static String statusLabel(ImportStatus status) {
        return switch (status) {
            case UPLOADED -> "archivo subido";
            case VALIDATED -> "filas validadas";
            case APPLYING -> "aplicándose";
            case APPLIED -> "aplicada";
            case FAILED -> "fallida";
            case CANCELLED -> "cancelada";
        };
    }

    /** Nombre de archivo seguro para guardar y mostrar. */
    private String cleanFileName(String original) {
        String name = ImportValues.text(original);
        if (name == null) {
            return "planilla";
        }
        name = name.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("\\p{Cntrl}", "").trim();
        if (name.isEmpty()) {
            name = "planilla";
        }
        return name.length() > 200 ? name.substring(0, 200) : name;
    }
}
