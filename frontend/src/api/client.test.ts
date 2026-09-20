import { AxiosError, AxiosHeaders, type AxiosAdapter, type AxiosRequestConfig, type AxiosResponse } from 'axios';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { tokenStorage } from '@/auth/tokenStorage';
import { BRANCH_HEADER, branchScope } from '@/branches/branchScope';
import {
  ApiError,
  NETWORK_ERROR_MESSAGE,
  SERVER_UNREACHABLE_MESSAGE,
  TIMEOUT_ERROR_MESSAGE,
  UNEXPECTED_ERROR_MESSAGE,
  api,
  apiDelete,
  apiGet,
  apiPatch,
  apiPost,
  apiPut,
  getErrorMessage,
  getFieldErrors,
  isApiError,
  downloadFile,
  fetchBlob,
  setAuthFailureHandler,
  toApiPath,
  uploadFile,
} from './client';

const originalAdapter = api.defaults.adapter;

/** Headers que finalmente salieron en la request. */
function headersOf(config: AxiosRequestConfig): AxiosHeaders {
  return AxiosHeaders.from(config.headers as AxiosHeaders);
}

/** Adaptador que no llama a la red: guarda la config y devuelve `data`. */
function okAdapter(data: unknown = {}, headers: Record<string, string> = {}) {
  const seen: AxiosRequestConfig[] = [];
  const adapter: AxiosAdapter = async (config) => {
    seen.push(config);
    return {
      data,
      status: 200,
      statusText: 'OK',
      headers,
      config,
    } as AxiosResponse;
  };
  api.defaults.adapter = adapter;
  return seen;
}

/** Adaptador que falla con una respuesta HTTP, como lo haría el backend. */
function failAdapter(status: number, data: unknown) {
  const adapter: AxiosAdapter = (config) =>
    Promise.reject(
      new AxiosError('Request failed', String(status), config, {}, {
        data,
        status,
        statusText: '',
        headers: new AxiosHeaders(),
        config,
      } as AxiosResponse),
    );
  api.defaults.adapter = adapter;
}

/** Adaptador que nunca llega al servidor (red caída o timeout). */
function offlineAdapter(code: string) {
  const adapter: AxiosAdapter = (config) => Promise.reject(new AxiosError('sin respuesta', code, config, {}));
  api.defaults.adapter = adapter;
}

afterEach(() => {
  api.defaults.adapter = originalAdapter;
  tokenStorage.clear();
  branchScope.set(null);
});

describe('toApiPath', () => {
  it('saca el prefijo /api porque ya está en baseURL', () => {
    expect(toApiPath('/api/attachments/12')).toBe('/attachments/12');
    expect(toApiPath('/attachments/12')).toBe('/attachments/12');
    expect(toApiPath('/api')).toBe('/');
  });

  it('no toca las URLs absolutas ni las rutas que apenas empiezan con "api"', () => {
    expect(toApiPath('https://cdn.ejemplo/imagen.png')).toBe('https://cdn.ejemplo/imagen.png');
    expect(toApiPath('/apifoo/1')).toBe('/apifoo/1');
  });
});

describe('ApiError', () => {
  const error = new ApiError({
    status: 400,
    code: 'VALIDATION_ERROR',
    message: 'Revisá los datos',
    fieldErrors: [
      { field: 'email', message: 'Formato inválido' },
      { field: 'email', message: 'Ya existe' },
      { field: 'name', message: 'Obligatorio' },
    ],
  });

  it('reconoce sus códigos', () => {
    expect(error.is('VALIDATION_ERROR')).toBe(true);
    expect(error.is('CONFLICT', 'VALIDATION_ERROR')).toBe(true);
    expect(error.is('CONFLICT')).toBe(false);
  });

  it('expone los errores por campo y se queda con el primero de cada uno', () => {
    expect(error.fieldError('email')).toBe('Formato inválido');
    expect(error.fieldError('otro')).toBeUndefined();
    expect(error.fieldErrorMap).toEqual({ email: 'Formato inválido', name: 'Obligatorio' });
  });

  it('isNetworkError solo con status 0', () => {
    expect(error.isNetworkError).toBe(false);
    expect(new ApiError({ status: 0, code: 'NETWORK_ERROR', message: '' }).isNetworkError).toBe(true);
  });
});

describe('interceptor de request', () => {
  it('agrega el token guardado', async () => {
    tokenStorage.set('jwt-123');
    const seen = okAdapter();
    await apiGet('/auth/me');
    expect(headersOf(seen[0]).get('Authorization')).toBe('Bearer jwt-123');
  });

  it('sin token no manda Authorization ni la sucursal', async () => {
    branchScope.set(7);
    const seen = okAdapter();
    await apiGet('/auth/me');
    const headers = headersOf(seen[0]);
    expect(headers.get('Authorization')).toBeFalsy();
    expect(headers.get(BRANCH_HEADER)).toBeFalsy();
  });

  it('manda la sucursal activa en X-Branch-Id', async () => {
    tokenStorage.set('jwt-123');
    branchScope.set(7);
    const seen = okAdapter();
    await apiGet('/products');
    expect(headersOf(seen[0]).get(BRANCH_HEADER)).toBe('7');
  });

  it('"all" también viaja como alcance', async () => {
    tokenStorage.set('jwt-123');
    branchScope.set('all');
    const seen = okAdapter();
    await apiGet('/products');
    expect(headersOf(seen[0]).get(BRANCH_HEADER)).toBe('all');
  });

  it('branch: null en la request no manda el header', async () => {
    tokenStorage.set('jwt-123');
    branchScope.set(7);
    const seen = okAdapter();
    await apiGet('/platform/tenants', undefined, { branch: null });
    expect(headersOf(seen[0]).get(BRANCH_HEADER)).toBeFalsy();
  });

  it('branch por request pisa la sucursal activa', async () => {
    tokenStorage.set('jwt-123');
    branchScope.set(7);
    const seen = okAdapter();
    await apiGet('/products', undefined, { branch: 9 });
    expect(headersOf(seen[0]).get(BRANCH_HEADER)).toBe('9');
  });
});

describe('serialización de params', () => {
  it('repite los arrays, omite vacíos y pasa las fechas a ISO (formato de Spring)', () => {
    const uri = api.getUri({
      url: '/products',
      params: {
        status: ['OK', 'LOW'],
        page: 0,
        query: '',
        branch: null,
        missing: undefined,
        from: new Date('2026-09-25T00:00:00Z'),
        onlyActive: true,
      },
    });
    const search = new URLSearchParams(uri.split('?')[1] ?? '');
    expect(search.getAll('status')).toEqual(['OK', 'LOW']);
    expect(search.get('page')).toBe('0');
    expect(search.has('query')).toBe(false);
    expect(search.has('branch')).toBe(false);
    expect(search.has('missing')).toBe(false);
    expect(search.get('from')).toBe('2026-09-25T00:00:00.000Z');
    expect(search.get('onlyActive')).toBe('true');
  });
});

describe('interceptor de response', () => {
  it('convierte el error del backend en ApiError', async () => {
    failAdapter(409, {
      timestamp: '2026-09-25T12:00:00Z',
      status: 409,
      error: 'Conflict',
      code: 'BARCODE_TAKEN',
      message: 'Ya existe un producto con ese código',
      path: '/api/products',
      fieldErrors: [{ field: 'barcode', message: 'Repetido' }],
    });
    const error = await apiPost('/products', {}).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    const apiError = error as ApiError;
    expect(apiError.status).toBe(409);
    expect(apiError.code).toBe('BARCODE_TAKEN');
    expect(apiError.message).toBe('Ya existe un producto con ese código');
    expect(apiError.fieldErrorMap).toEqual({ barcode: 'Repetido' });
  });

  it('un cuerpo en texto plano también se aprovecha si es JSON', async () => {
    failAdapter(400, JSON.stringify({ code: 'INVALID_FILE', message: 'Archivo inválido' }));
    const error = (await apiPost('/imports', {}).catch((e: unknown) => e)) as ApiError;
    expect(error.code).toBe('INVALID_FILE');
    expect(error.message).toBe('Archivo inválido');
  });

  it('un 403 sin cuerpo de GondolIA usa el mensaje por defecto', async () => {
    failAdapter(403, 'Invalid CORS request');
    const error = (await apiPost('/auth/login', {}).catch((e: unknown) => e)) as ApiError;
    expect(error.status).toBe(403);
    expect(error.code).toBe('FORBIDDEN');
    expect(error.message).toBe('No tenés permisos para realizar esta acción.');
  });

  it('un 5xx sin cuerpo se atribuye al proxy', async () => {
    failAdapter(502, '<html>502 Bad Gateway</html>');
    const error = (await apiGet('/products').catch((e: unknown) => e)) as ApiError;
    expect(error.code).toBe('SERVICE_UNAVAILABLE');
    expect(error.message).toBe(SERVER_UNREACHABLE_MESSAGE);
  });

  it('sin respuesta distingue red caída de timeout', async () => {
    offlineAdapter(AxiosError.ERR_NETWORK);
    const network = (await apiGet('/products').catch((e: unknown) => e)) as ApiError;
    expect(network.status).toBe(0);
    expect(network.code).toBe('NETWORK_ERROR');
    expect(network.message).toBe(NETWORK_ERROR_MESSAGE);

    offlineAdapter(AxiosError.ECONNABORTED);
    const timeout = (await apiGet('/products').catch((e: unknown) => e)) as ApiError;
    expect(timeout.code).toBe('TIMEOUT');
    expect(timeout.message).toBe(TIMEOUT_ERROR_MESSAGE);
  });
});

describe('manejo global de sesión', () => {
  it('un 401 con token cierra la sesión', async () => {
    tokenStorage.set('jwt-viejo');
    const handler = vi.fn();
    const unregister = setAuthFailureHandler(handler);
    failAdapter(401, { code: 'UNAUTHORIZED', message: 'Sesión vencida' });
    await apiGet('/auth/me').catch(() => undefined);
    expect(handler).toHaveBeenCalledWith({ kind: 'expired', token: 'jwt-viejo' });
    unregister();
  });

  it('BAD_CREDENTIALS del login no cierra nada', async () => {
    tokenStorage.set('jwt-viejo');
    const handler = vi.fn();
    const unregister = setAuthFailureHandler(handler);
    failAdapter(401, { code: 'BAD_CREDENTIALS', message: 'Email o contraseña incorrectos' });
    await apiPost('/auth/login', {}).catch(() => undefined);
    expect(handler).not.toHaveBeenCalled();
    unregister();
  });

  it('un comercio deshabilitado bloquea la sesión', async () => {
    tokenStorage.set('jwt');
    const handler = vi.fn();
    const unregister = setAuthFailureHandler(handler);
    failAdapter(403, { code: 'TENANT_DISABLED', message: 'Comercio deshabilitado' });
    await apiGet('/products').catch(() => undefined);
    expect(handler).toHaveBeenCalledWith({
      kind: 'blocked',
      token: 'jwt',
      code: 'TENANT_DISABLED',
      message: 'Comercio deshabilitado',
    });
    unregister();
  });

  it('BRANCH_FORBIDDEN pide refrescar las sucursales', async () => {
    tokenStorage.set('jwt');
    const handler = vi.fn();
    const unregister = setAuthFailureHandler(handler);
    failAdapter(403, { code: 'BRANCH_FORBIDDEN', message: 'Sin acceso a la sucursal' });
    await apiGet('/products').catch(() => undefined);
    expect(handler).toHaveBeenCalledWith({ kind: 'branch-forbidden', token: 'jwt' });
    unregister();
  });

  it('skipAuthHandling deja el error al llamador', async () => {
    tokenStorage.set('jwt');
    const handler = vi.fn();
    const unregister = setAuthFailureHandler(handler);
    failAdapter(401, { code: 'UNAUTHORIZED', message: 'Sesión vencida' });
    await apiGet('/auth/me', undefined, { skipAuthHandling: true }).catch(() => undefined);
    expect(handler).not.toHaveBeenCalled();
    unregister();
  });

  it('sin token no hay sesión que cerrar', async () => {
    const handler = vi.fn();
    const unregister = setAuthFailureHandler(handler);
    failAdapter(401, { code: 'UNAUTHORIZED', message: 'Sesión vencida' });
    await apiPost('/auth/login', {}).catch(() => undefined);
    expect(handler).not.toHaveBeenCalled();
    unregister();
  });

  it('desregistrar el handler lo deja de llamar', async () => {
    tokenStorage.set('jwt');
    const handler = vi.fn();
    setAuthFailureHandler(handler)();
    failAdapter(401, { code: 'UNAUTHORIZED', message: 'Sesión vencida' });
    await apiGet('/auth/me').catch(() => undefined);
    expect(handler).not.toHaveBeenCalled();
  });
});

describe('verbos', () => {
  it('cada helper usa su método y devuelve el cuerpo', async () => {
    const seen = okAdapter({ ok: true });
    await expect(apiGet('/a')).resolves.toEqual({ ok: true });
    await expect(apiPost('/a', { x: 1 })).resolves.toEqual({ ok: true });
    await expect(apiPut('/a', { x: 1 })).resolves.toEqual({ ok: true });
    await expect(apiPatch('/a', { x: 1 })).resolves.toEqual({ ok: true });
    await expect(apiDelete('/a')).resolves.toEqual({ ok: true });
    expect(seen.map((c) => c.method)).toEqual(['get', 'post', 'put', 'patch', 'delete']);
  });
});

describe('uploadFile', () => {
  it('arma el multipart con el archivo y los campos', async () => {
    const seen = okAdapter({ id: 1 });
    const file = new File(['hola'], 'ticket.png', { type: 'image/png' });
    await uploadFile('/api/support/tickets/1/messages', file, { fields: { body: 'Hola', vacio: null } });
    const form = seen[0].data as FormData;
    expect(form.get('body')).toBe('Hola');
    expect(form.has('vacio')).toBe(false);
    expect((form.get('file') as File).name).toBe('ticket.png');
    expect(seen[0].url).toBe('/support/tickets/1/messages');
  });

  it('a un Blob sin nombre le pone una extensión según el tipo', async () => {
    const seen = okAdapter({ id: 1 });
    await uploadFile('/attachments', new Blob(['x'], { type: 'image/jpeg' }));
    expect((seen[0].data as FormData).get('file')).toBeInstanceOf(File);
    expect(((seen[0].data as FormData).get('file') as File).name).toBe('archivo.jpg');
  });

  it('informa el progreso de la subida', async () => {
    const onProgress = vi.fn();
    const adapter = async (config: AxiosRequestConfig) => {
      config.onUploadProgress?.({ loaded: 50, total: 200 } as never);
      config.onUploadProgress?.({ loaded: 200, total: 200 } as never);
      // Sin total (el navegador no siempre lo sabe): no se informa nada.
      config.onUploadProgress?.({ loaded: 200 } as never);
      return { data: {}, status: 200, statusText: 'OK', headers: {}, config } as AxiosResponse;
    };
    api.defaults.adapter = adapter as AxiosAdapter;
    await uploadFile('/attachments', new Blob(['x'], { type: 'text/csv' }), { onProgress });
    expect(onProgress.mock.calls.map(([p]) => p)).toEqual([25, 100]);
  });

  it('sin archivo manda solo los campos', async () => {
    const seen = okAdapter({ id: 1 });
    await uploadFile('/attachments', null, { fields: { body: 'Solo texto' }, method: 'put' });
    expect((seen[0].data as FormData).has('file')).toBe(false);
    expect(seen[0].method).toBe('put');
  });
});

describe('descargas', () => {
  it('fetchBlob pide el recurso como Blob', async () => {
    const blob = new Blob(['datos']);
    const seen = okAdapter(blob);
    await expect(fetchBlob('/api/attachments/12')).resolves.toBe(blob);
    expect(seen[0].responseType).toBe('blob');
    expect(seen[0].url).toBe('/attachments/12');
  });

  it('un error con cuerpo Blob (responseType blob) igual se lee', async () => {
    const body = new Blob([JSON.stringify({ code: 'NOT_FOUND', message: 'No existe el adjunto' })]);
    failAdapter(404, body);
    const error = (await fetchBlob('/attachments/12').catch((e: unknown) => e)) as ApiError;
    expect(error.code).toBe('NOT_FOUND');
    expect(error.message).toBe('No existe el adjunto');
  });

  it('un Blob que no es JSON cae al mensaje por defecto', async () => {
    failAdapter(500, new Blob(['<html>error</html>']));
    const error = (await fetchBlob('/attachments/12').catch((e: unknown) => e)) as ApiError;
    expect(error.code).toBe('SERVICE_UNAVAILABLE');
  });

  it('downloadFile usa el nombre que manda el servidor', async () => {
    const clicked: HTMLAnchorElement[] = [];
    const click = vi
      .spyOn(HTMLAnchorElement.prototype, 'click')
      .mockImplementation(function (this: HTMLAnchorElement) {
        clicked.push(this);
      });
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: () => 'blob:x', revokeObjectURL: vi.fn() }));

    okAdapter(new Blob(['a,b']), { 'content-disposition': 'attachment; filename="ventas-2026.csv"' });
    await downloadFile('/api/sales/export', 'ventas.csv');
    expect(clicked[0].download).toBe('ventas-2026.csv');

    okAdapter(new Blob(['a,b']), {
      'content-disposition': "attachment; filename*=UTF-8''informe%20de%20ventas.csv",
    });
    await downloadFile('/api/sales/export', 'ventas.csv');
    expect(clicked[1].download).toBe('informe de ventas.csv');

    okAdapter(new Blob(['a,b']));
    await downloadFile('/api/sales/export', 'ventas.csv');
    expect(clicked[2].download).toBe('ventas.csv');

    expect(document.querySelectorAll('a[download]')).toHaveLength(0);
    click.mockRestore();
  });
});

describe('helpers de error', () => {
  it('getErrorMessage prioriza el ApiError', () => {
    expect(getErrorMessage(new ApiError({ status: 409, code: 'X', message: 'Conflicto' }))).toBe('Conflicto');
  });

  it('getErrorMessage entiende un AxiosError crudo', () => {
    const withBody = new AxiosError('x', '500', undefined, {}, {
      data: { code: 'INTERNAL_ERROR', message: 'Explotó' },
      status: 500,
      statusText: '',
      headers: new AxiosHeaders(),
      config: { headers: new AxiosHeaders() },
    } as AxiosResponse);
    expect(getErrorMessage(withBody)).toBe('Explotó');

    const withoutBody = new AxiosError('x', '404', undefined, {}, {
      data: '',
      status: 404,
      statusText: '',
      headers: new AxiosHeaders(),
      config: { headers: new AxiosHeaders() },
    } as AxiosResponse);
    expect(getErrorMessage(withoutBody)).toBe('No encontramos lo que buscabas.');

    expect(getErrorMessage(new AxiosError('sin red', AxiosError.ERR_NETWORK))).toBe(NETWORK_ERROR_MESSAGE);
  });

  it('getErrorMessage cae al mensaje genérico', () => {
    expect(getErrorMessage(new Error('boom'))).toBe(UNEXPECTED_ERROR_MESSAGE);
    expect(getErrorMessage(null, 'No se pudo guardar')).toBe('No se pudo guardar');
  });

  it('getFieldErrors e isApiError', () => {
    const error = new ApiError({
      status: 400,
      code: 'VALIDATION_ERROR',
      message: '',
      fieldErrors: [{ field: 'name', message: 'Obligatorio' }],
    });
    expect(getFieldErrors(error)).toEqual({ name: 'Obligatorio' });
    expect(getFieldErrors(new Error('x'))).toEqual({});
    expect(isApiError(error)).toBe(true);
    expect(isApiError(error, 'VALIDATION_ERROR')).toBe(true);
    expect(isApiError(error, 'CONFLICT')).toBe(false);
    expect(isApiError(new Error('x'))).toBe(false);
  });
});
