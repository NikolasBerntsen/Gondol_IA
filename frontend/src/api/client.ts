import axios, { AxiosError, AxiosHeaders, type AxiosRequestConfig } from 'axios';
import { tokenStorage } from '@/auth/tokenStorage';
import { BRANCH_HEADER, branchScope } from '@/branches/branchScope';
import type { BranchScope, ErrorResponse, FieldErrorDto } from './types';

declare module 'axios' {
  interface AxiosRequestConfig {
    /**
     * Evita que un 401/403 de sesión de esta request cierre la sesión automáticamente.
     * Úsenlo solo en flujos que manejan el error por su cuenta.
     */
    skipAuthHandling?: boolean;
    /**
     * Sucursal a enviar en `X-Branch-Id` solo para esta request.
     * `undefined` (defecto) = la elegida en el selector; `null` = no enviar el header.
     */
    branch?: BranchScope | null;
  }
}

// ---------------------------------------------------------------------------
// Mensajes por defecto
// ---------------------------------------------------------------------------

export const NETWORK_ERROR_MESSAGE =
  'No pudimos conectarnos con el servidor. Revisá tu conexión e intentá de nuevo.';
export const TIMEOUT_ERROR_MESSAGE = 'El servidor tardó demasiado en responder. Intentá de nuevo.';
export const UNEXPECTED_ERROR_MESSAGE = 'Ocurrió un error inesperado. Intentá de nuevo.';
export const SERVER_UNREACHABLE_MESSAGE =
  'No pudimos comunicarnos con el servidor de GondolIA. Intentá de nuevo en unos minutos.';

/** Códigos que implican que el usuario ya no puede seguir usando la app (SPEC §3.2, §9.6). */
export const SESSION_BLOCKING_CODES: ReadonlySet<string> = new Set([
  'TENANT_DISABLED',
  'TENANT_CANCELLED',
  'USER_DISABLED',
]);

function defaultMessageForStatus(status: number): string {
  switch (status) {
    case 400:
    case 422:
      return 'Revisá los datos ingresados.';
    case 401:
      return 'Tu sesión expiró. Iniciá sesión nuevamente.';
    case 403:
      return 'No tenés permisos para realizar esta acción.';
    case 404:
      return 'No encontramos lo que buscabas.';
    case 409:
      return 'La operación entra en conflicto con datos existentes.';
    case 413:
      return 'El archivo es demasiado grande.';
    case 415:
      return 'El formato del archivo no es compatible.';
    case 429:
      return 'Hiciste demasiadas solicitudes. Esperá un momento e intentá de nuevo.';
    case 502:
    case 503:
    case 504:
      return 'El servicio no está disponible en este momento. Intentá de nuevo en unos minutos.';
    default:
      return status >= 500 ? 'Ocurrió un error en el servidor. Intentá de nuevo en unos minutos.' : UNEXPECTED_ERROR_MESSAGE;
  }
}

function defaultCodeForStatus(status: number): string {
  switch (status) {
    case 400:
      return 'BAD_REQUEST';
    case 401:
      return 'UNAUTHORIZED';
    case 403:
      return 'FORBIDDEN';
    case 404:
      return 'NOT_FOUND';
    case 409:
      return 'CONFLICT';
    case 413:
      return 'PAYLOAD_TOO_LARGE';
    case 503:
      return 'SERVICE_UNAVAILABLE';
    default:
      return status >= 500 ? 'INTERNAL_ERROR' : 'HTTP_ERROR';
  }
}

// ---------------------------------------------------------------------------
// ApiError
// ---------------------------------------------------------------------------

export interface ApiErrorInit {
  status: number;
  code: string;
  message: string;
  fieldErrors?: FieldErrorDto[];
  response?: ErrorResponse;
  cause?: unknown;
}

/**
 * Error normalizado que rechazan TODAS las requests hechas con `api`.
 * `status` es 0 cuando no hubo respuesta (red caída o timeout).
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly fieldErrors: FieldErrorDto[];
  readonly response?: ErrorResponse;
  readonly cause?: unknown;

  constructor(init: ApiErrorInit) {
    super(init.message);
    this.name = 'ApiError';
    this.status = init.status;
    this.code = init.code;
    this.fieldErrors = init.fieldErrors ?? [];
    this.response = init.response;
    this.cause = init.cause;
  }

  /** `true` si el código de error es alguno de los indicados. */
  is(...codes: string[]): boolean {
    return codes.includes(this.code);
  }

  /** Mensaje de validación de un campo (`fieldErrors`). */
  fieldError(field: string): string | undefined {
    return this.fieldErrors.find((f) => f.field === field)?.message;
  }

  /** `{ campo: mensaje }` con el primer error de cada campo. */
  get fieldErrorMap(): Record<string, string> {
    const map: Record<string, string> = {};
    for (const { field, message } of this.fieldErrors) {
      if (!(field in map)) map[field] = message;
    }
    return map;
  }

  get isNetworkError(): boolean {
    return this.status === 0;
  }
}

function isErrorResponse(data: unknown): data is ErrorResponse {
  return (
    typeof data === 'object' &&
    data !== null &&
    typeof (data as ErrorResponse).message === 'string' &&
    typeof (data as ErrorResponse).code === 'string'
  );
}

async function readErrorBody(data: unknown): Promise<unknown> {
  try {
    if (typeof Blob !== 'undefined' && data instanceof Blob) {
      return JSON.parse(await data.text());
    }
    if (typeof data === 'string' && data.trim().startsWith('{')) {
      return JSON.parse(data);
    }
  } catch {
    return undefined;
  }
  return data;
}

async function toApiError(error: AxiosError): Promise<ApiError> {
  if (!error.response) {
    const timedOut = error.code === AxiosError.ECONNABORTED || error.code === AxiosError.ETIMEDOUT;
    return new ApiError({
      status: 0,
      code: timedOut ? 'TIMEOUT' : 'NETWORK_ERROR',
      message: timedOut ? TIMEOUT_ERROR_MESSAGE : NETWORK_ERROR_MESSAGE,
      cause: error,
    });
  }
  const { status } = error.response;
  const body = await readErrorBody(error.response.data);
  const payload = isErrorResponse(body) ? body : undefined;
  // Un 5xx sin cuerpo de GondolIA viene del proxy (nginx/Vite): el backend no está respondiendo.
  const gatewayFailure = !payload && status >= 500;
  return new ApiError({
    status,
    code: payload?.code || (gatewayFailure ? 'SERVICE_UNAVAILABLE' : defaultCodeForStatus(status)),
    message: payload?.message?.trim() || (gatewayFailure ? SERVER_UNREACHABLE_MESSAGE : defaultMessageForStatus(status)),
    fieldErrors: payload?.fieldErrors ?? [],
    response: payload,
    cause: error,
  });
}

// ---------------------------------------------------------------------------
// Manejo global de sesión
// ---------------------------------------------------------------------------

export type AuthFailure =
  | { kind: 'expired'; token: string }
  | { kind: 'blocked'; token: string; code: string; message: string }
  /** 403 `BRANCH_FORBIDDEN`: la sucursal se desactivó o se la quitaron al usuario; hay que refrescar `me.branches`. */
  | { kind: 'branch-forbidden'; token: string };

type AuthFailureHandler = (failure: AuthFailure) => void;

let authFailureHandler: AuthFailureHandler | null = null;

/** Lo registra `AuthProvider`. Devuelve la función para desregistrarlo. */
export function setAuthFailureHandler(handler: AuthFailureHandler): () => void {
  authFailureHandler = handler;
  return () => {
    if (authFailureHandler === handler) authFailureHandler = null;
  };
}

// ---------------------------------------------------------------------------
// Instancia axios
// ---------------------------------------------------------------------------

type QueryValue = string | number | boolean | Date | null | undefined;

/** Serializa params al formato de Spring: arrays repetidos (`a=1&a=2`) y sin valores vacíos. */
function serializeParams(params: Record<string, QueryValue | QueryValue[]>): string {
  const search = new URLSearchParams();
  for (const [key, raw] of Object.entries(params ?? {})) {
    const values = Array.isArray(raw) ? raw : [raw];
    for (const value of values) {
      if (value === undefined || value === null || value === '') continue;
      search.append(key, value instanceof Date ? value.toISOString() : String(value));
    }
  }
  return search.toString();
}

export const api = axios.create({
  baseURL: '/api',
  headers: { Accept: 'application/json' },
  paramsSerializer: { serialize: serializeParams },
});

const BEARER_PREFIX = 'Bearer ';

api.interceptors.request.use((config) => {
  const token = tokenStorage.get();
  if (token && !config.headers.has('Authorization')) {
    config.headers.set('Authorization', `${BEARER_PREFIX}${token}`);
  }
  if (!config.headers.has(BRANCH_HEADER)) {
    const scope = config.branch === undefined ? branchScope.get() : config.branch;
    if (token && scope !== null) config.headers.set(BRANCH_HEADER, String(scope));
  }
  return config;
});

api.interceptors.response.use(undefined, async (error: unknown) => {
  if (axios.isCancel(error) || !axios.isAxiosError(error)) {
    return Promise.reject(error);
  }
  const apiError = await toApiError(error);
  const config = error.config;
  const authorization = config ? AxiosHeaders.from(config.headers).get('Authorization') : null;
  const sentToken =
    typeof authorization === 'string' && authorization.startsWith(BEARER_PREFIX)
      ? authorization.slice(BEARER_PREFIX.length)
      : null;

  if (sentToken && !config?.skipAuthHandling && authFailureHandler) {
    if (SESSION_BLOCKING_CODES.has(apiError.code)) {
      authFailureHandler({ kind: 'blocked', token: sentToken, code: apiError.code, message: apiError.message });
    } else if (apiError.status === 401 && apiError.code !== 'BAD_CREDENTIALS') {
      authFailureHandler({ kind: 'expired', token: sentToken });
    } else if (apiError.status === 403 && apiError.code === 'BRANCH_FORBIDDEN') {
      authFailureHandler({ kind: 'branch-forbidden', token: sentToken });
    }
  }
  return Promise.reject(apiError);
});

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Acepta rutas con o sin el prefijo `/api` (p. ej. `attachment.url = "/api/attachments/12"`). */
export function toApiPath(url: string): string {
  if (/^https?:\/\//i.test(url)) return url;
  return url.replace(/^\/api(?=\/|$)/, '') || '/';
}

export async function apiGet<T>(url: string, params?: object, config?: AxiosRequestConfig): Promise<T> {
  const response = await api.get<T>(toApiPath(url), { ...config, params });
  return response.data;
}

export async function apiPost<T = void>(url: string, body?: unknown, config?: AxiosRequestConfig): Promise<T> {
  const response = await api.post<T>(toApiPath(url), body, config);
  return response.data;
}

export async function apiPut<T = void>(url: string, body?: unknown, config?: AxiosRequestConfig): Promise<T> {
  const response = await api.put<T>(toApiPath(url), body, config);
  return response.data;
}

export async function apiPatch<T = void>(url: string, body?: unknown, config?: AxiosRequestConfig): Promise<T> {
  const response = await api.patch<T>(toApiPath(url), body, config);
  return response.data;
}

export async function apiDelete<T = void>(url: string, config?: AxiosRequestConfig): Promise<T> {
  const response = await api.delete<T>(toApiPath(url), config);
  return response.data;
}

export interface UploadOptions {
  /** Nombre del campo multipart del archivo (por defecto `file`). */
  fieldName?: string;
  /** Campos de texto adicionales del multipart (p. ej. `{ body: 'Hola' }`). Se omiten `null`/`undefined`. */
  fields?: Record<string, string | number | boolean | Blob | null | undefined>;
  /** Nombre del archivo cuando se sube un `Blob` (p. ej. una captura de cámara). */
  fileName?: string;
  method?: 'post' | 'put' | 'patch';
  /** Progreso de subida 0..100. */
  onProgress?: (percent: number) => void;
  signal?: AbortSignal;
  config?: AxiosRequestConfig;
}

const EXTENSION_BY_TYPE: Record<string, string> = {
  'image/jpeg': 'jpg',
  'image/png': 'png',
  'image/webp': 'webp',
  'image/gif': 'gif',
  'text/csv': 'csv',
};

/** Sube un archivo como `multipart/form-data` y devuelve el cuerpo de la respuesta. */
export async function uploadFile<T>(
  url: string,
  file: Blob | null | undefined,
  options: UploadOptions = {},
): Promise<T> {
  const { fieldName = 'file', fields, fileName, method = 'post', onProgress, signal, config } = options;
  const form = new FormData();
  if (file) {
    const name =
      fileName ?? (file instanceof File && file.name ? file.name : `archivo.${EXTENSION_BY_TYPE[file.type] ?? 'bin'}`);
    form.append(fieldName, file, name);
  }
  for (const [key, value] of Object.entries(fields ?? {})) {
    if (value === null || value === undefined) continue;
    form.append(key, value instanceof Blob ? value : String(value));
  }
  const response = await api.request<T>({
    ...config,
    url: toApiPath(url),
    method,
    data: form,
    signal,
    onUploadProgress: onProgress
      ? (event) => {
          if (event.total) onProgress(Math.min(100, Math.round((event.loaded * 100) / event.total)));
        }
      : undefined,
  });
  return response.data;
}

/** Descarga un recurso autenticado como `Blob` (imágenes, CSV). */
export async function fetchBlob(url: string, config?: AxiosRequestConfig): Promise<Blob> {
  const response = await api.get<Blob>(toApiPath(url), { ...config, responseType: 'blob' });
  return response.data;
}

function fileNameFromDisposition(header: unknown): string | null {
  if (typeof header !== 'string') return null;
  const utf8 = /filename\*=UTF-8''([^;]+)/i.exec(header);
  if (utf8) {
    try {
      return decodeURIComponent(utf8[1].trim());
    } catch {
      // Cae al filename simple.
    }
  }
  const simple = /filename="?([^";]+)"?/i.exec(header);
  return simple ? simple[1].trim() : null;
}

/** Descarga un archivo autenticado y lo guarda con el nombre del servidor (o `fallbackFileName`). */
export async function downloadFile(url: string, fallbackFileName: string, params?: object): Promise<void> {
  const response = await api.get<Blob>(toApiPath(url), { params, responseType: 'blob' });
  const fileName = fileNameFromDisposition(response.headers['content-disposition']) ?? fallbackFileName;
  const href = URL.createObjectURL(response.data);
  const link = document.createElement('a');
  link.href = href;
  link.download = fileName;
  link.rel = 'noopener';
  document.body.appendChild(link);
  link.click();
  link.remove();
  window.setTimeout(() => URL.revokeObjectURL(href), 1_000);
}

/** Mensaje en español para mostrar al usuario a partir de cualquier error. */
export function getErrorMessage(error: unknown, fallback: string = UNEXPECTED_ERROR_MESSAGE): string {
  if (error instanceof ApiError) return error.message;
  if (axios.isAxiosError(error)) {
    const data: unknown = error.response?.data;
    if (isErrorResponse(data)) return data.message;
    return error.response ? defaultMessageForStatus(error.response.status) : NETWORK_ERROR_MESSAGE;
  }
  return fallback;
}

/** `{ campo: mensaje }` de un error de validación (vacío si no aplica). */
export function getFieldErrors(error: unknown): Record<string, string> {
  return error instanceof ApiError ? error.fieldErrorMap : {};
}

/** `true` si `error` es un `ApiError` (y, si se indican, con alguno de esos códigos). */
export function isApiError(error: unknown, ...codes: string[]): error is ApiError {
  return error instanceof ApiError && (codes.length === 0 || codes.includes(error.code));
}
