/** Tipos de la importación masiva de productos y stock (SPEC §16). Espejo de `com.gondolia.imports.dto`. */

export type ImportStatus = 'UPLOADED' | 'VALIDATED' | 'APPLYING' | 'APPLIED' | 'FAILED' | 'CANCELLED';
export type ImportFileFormat = 'XLSX' | 'XLS' | 'CSV';
export type ImportRowStatus = 'PENDING' | 'VALID' | 'WARNING' | 'ERROR' | 'SKIPPED' | 'IMPORTED' | 'FAILED';
export type ImportRowAction = 'CREATE' | 'UPDATE' | 'SKIP';
export type ImportFieldType = 'text' | 'number' | 'integer' | 'boolean' | 'date' | 'unit' | 'branch';
export type ImportDateFormat = 'DMY' | 'MDY' | 'YMD';

/** Campo importable del catálogo (`GET /api/tenant/imports/fields`). */
export interface ImportFieldDto {
  key: string;
  label: string;
  required: boolean;
  type: ImportFieldType;
  description: string;
  synonyms: string[];
  /** `producto` = datos del catálogo · `stock` = lote inicial. */
  group: 'producto' | 'stock';
}

export interface ImportOptions {
  updateExisting: boolean;
  createCategories: boolean;
  createSuppliers: boolean;
  importStock: boolean;
  defaultBranchId: number | null;
  defaultBranchName: string | null;
  dateFormat: ImportDateFormat;
}

export interface ImportRowMessage {
  /** Clave del campo (`name`, `salePrice`…) o `null` si el mensaje es de toda la fila. */
  field: string | null;
  level: 'ERROR' | 'WARNING';
  message: string;
}

export interface ImportRow {
  id: number;
  rowNumber: number;
  /** Valores originales por encabezado del archivo. */
  raw: Record<string, string>;
  /** Valores mapeados y editables, por clave de campo. */
  data: Record<string, string>;
  status: ImportRowStatus;
  action: ImportRowAction | null;
  messages: ImportRowMessage[];
  productId: number | null;
  lotId: number | null;
}

export interface ImportPreview {
  productsToCreate: number;
  productsToUpdate: number;
  lotsToCreate: number;
  unitsToLoad: number;
  categoriesToCreate: string[];
  suppliersToCreate: string[];
  rowsToSkip: number;
  rowsWithErrors: number;
  recallWarnings: string[];
}

export interface ImportResult {
  productsCreated: number;
  productsUpdated: number;
  lotsCreated: number;
  unitsLoaded: number;
  categoriesCreated: number;
  suppliersCreated: number;
  rowsSkipped: number;
  rowsFailed: number;
  recallMatches: number;
}

export interface ImportJob {
  id: number;
  status: ImportStatus;
  fileName: string;
  fileFormat: ImportFileFormat;
  sheetName: string | null;
  sheetNames: string[];
  headers: string[];
  /** Mapeo sugerido por sinónimos de encabezado. */
  suggestedMapping: Record<string, string>;
  /** Mapeo guardado ({@code fieldKey → encabezado}). */
  columnMapping: Record<string, string>;
  options: ImportOptions;
  totalRows: number;
  validRows: number;
  warningRows: number;
  errorRows: number;
  skippedRows: number;
  processedRows: number;
  progressPct: number;
  result: ImportResult | null;
  errorMessage: string | null;
  createdAt: string;
  createdByName: string | null;
  appliedAt: string | null;
  /** Primeras filas crudas, para mostrar valores de muestra al mapear columnas. */
  sampleRows: Array<Record<string, string>>;
  preview: ImportPreview | null;
}

export interface ImportJobSummary {
  id: number;
  status: ImportStatus;
  fileName: string;
  fileFormat: ImportFileFormat;
  totalRows: number;
  validRows: number;
  warningRows: number;
  errorRows: number;
  skippedRows: number;
  progressPct: number;
  result: ImportResult | null;
  createdAt: string;
  createdByName: string | null;
  appliedAt: string | null;
}

export interface ImportMappingRequest {
  columnMapping: Record<string, string>;
  options: Partial<Omit<ImportOptions, 'defaultBranchName'>>;
}

export interface ImportRowsParams {
  status?: ImportRowStatus | 'ALL';
  q?: string;
  page?: number;
  size?: number;
}

export interface ImportBulkRequest {
  /** `null` = todas las filas del filtro. */
  rowIds: number[] | null;
  filterStatus?: ImportRowStatus | null;
  action: 'SKIP' | 'UNSKIP' | 'SET_FIELD';
  field?: string;
  value?: string;
}

export interface ImportBulkResponse {
  affectedRows: number;
  job: ImportJob;
}

export interface ImportRowPatchResponse {
  row: ImportRow;
  job: ImportJob;
}
