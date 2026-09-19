import { apiDelete, apiGet, apiPatch, apiPost, apiPut, downloadFile, uploadFile } from '@/api/client';
import type { PageResponse } from '@/api/types';
import type {
  ImportBulkRequest,
  ImportBulkResponse,
  ImportFieldDto,
  ImportJob,
  ImportJobSummary,
  ImportMappingRequest,
  ImportRow,
  ImportRowPatchResponse,
  ImportRowsParams,
} from './types';

const BASE = '/tenant/imports';

export const importsApi = {
  fields: () => apiGet<ImportFieldDto[]>(`${BASE}/fields`),

  list: (params: { page?: number; size?: number }) =>
    apiGet<PageResponse<ImportJobSummary>>(BASE, params),

  get: (id: number) => apiGet<ImportJob>(`${BASE}/${id}`),

  upload: (file: File, sheetName?: string, onProgress?: (percent: number) => void) =>
    uploadFile<ImportJob>(BASE, file, { fields: sheetName ? { sheetName } : undefined, onProgress }),

  saveMapping: (id: number, body: ImportMappingRequest) =>
    apiPut<ImportJob>(`${BASE}/${id}/mapping`, body),

  rows: (id: number, params: ImportRowsParams) =>
    apiGet<PageResponse<ImportRow>>(`${BASE}/${id}/rows`, params),

  patchRow: (id: number, rowId: number, data: Record<string, string>) =>
    apiPatch<ImportRowPatchResponse>(`${BASE}/${id}/rows/${rowId}`, { data }),

  bulk: (id: number, body: ImportBulkRequest) =>
    apiPost<ImportBulkResponse>(`${BASE}/${id}/rows/bulk`, body),

  apply: (id: number, ignoreErrors: boolean) =>
    apiPost<ImportJob>(`${BASE}/${id}/apply`, { ignoreErrors }),

  cancel: (id: number) => apiDelete<ImportJob>(`${BASE}/${id}`),

  downloadTemplate: (format: 'xlsx' | 'csv') =>
    downloadFile(`${BASE}/template`, `gondolia-plantilla-productos.${format}`, { format }),

  downloadCatalog: (format: 'xlsx' | 'csv', includeStock: boolean) =>
    downloadFile(`${BASE}/export`, `gondolia-catalogo.${format}`, { format, includeStock }),

  downloadErrors: (id: number) =>
    downloadFile(`${BASE}/${id}/errors.csv`, `importacion-${id}-errores.csv`),
};

/**
 * Query keys. Las importaciones son por comercio (no por sucursal), así que no llevan el segmento de sucursal;
 * la exportación del catálogo sí depende del alcance elegido, pero es una descarga, no una query.
 */
export const importKeys = {
  all: ['imports'] as const,
  list: (params: { page?: number; size?: number }) => ['imports', 'list', params] as const,
  detail: (id: number) => ['imports', 'detail', id] as const,
  rows: (id: number, params: ImportRowsParams) => ['imports', 'rows', id, params] as const,
  fields: () => ['imports', 'fields'] as const,
};
