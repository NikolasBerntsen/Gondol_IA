package com.gondolia.imports.validate;

import com.fasterxml.jackson.databind.JsonNode;
import com.gondolia.imports.dto.ImportDtos;
import com.gondolia.imports.parse.ImportValues;

/**
 * Opciones efectivas de una importación (SPEC §16.2). Los valores por defecto son los que espera el asistente:
 * actualizar existentes, crear categorías y proveedores, cargar stock y fechas en formato día/mes/año.
 */
public record ImportOptions(boolean updateExisting, boolean createCategories, boolean createSuppliers,
                            boolean importStock, Long defaultBranchId, ImportValues.DateOrder dateFormat) {

    public static ImportOptions defaults() {
        return new ImportOptions(true, true, true, true, null, ImportValues.DateOrder.DMY);
    }

    /** Opciones guardadas en el JSONB del job. */
    public static ImportOptions fromJson(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return defaults();
        }
        ImportOptions defaults = defaults();
        return new ImportOptions(
                bool(node, "updateExisting", defaults.updateExisting()),
                bool(node, "createCategories", defaults.createCategories()),
                bool(node, "createSuppliers", defaults.createSuppliers()),
                bool(node, "importStock", defaults.importStock()),
                node.hasNonNull("defaultBranchId") ? node.get("defaultBranchId").asLong() : null,
                ImportValues.DateOrder.fromJson(node.hasNonNull("dateFormat")
                        ? node.get("dateFormat").asText() : null));
    }

    /** Opciones enviadas por el frontend; lo que no viene conserva el valor actual. */
    public ImportOptions merge(ImportDtos.OptionsRequest request) {
        if (request == null) {
            return this;
        }
        return new ImportOptions(
                request.updateExisting() == null ? updateExisting : request.updateExisting(),
                request.createCategories() == null ? createCategories : request.createCategories(),
                request.createSuppliers() == null ? createSuppliers : request.createSuppliers(),
                request.importStock() == null ? importStock : request.importStock(),
                request.defaultBranchId(),
                request.dateFormat() == null ? dateFormat : ImportValues.DateOrder.fromJson(request.dateFormat()));
    }

    public ImportDtos.OptionsDto toDto(String defaultBranchName) {
        return new ImportDtos.OptionsDto(updateExisting, createCategories, createSuppliers, importStock,
                defaultBranchId, defaultBranchName, dateFormat.name());
    }

    private static boolean bool(JsonNode node, String field, boolean fallback) {
        return node.hasNonNull(field) ? node.get(field).asBoolean(fallback) : fallback;
    }
}
