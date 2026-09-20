import { QueryClient } from '@tanstack/react-query';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { toast } from 'sonner';
import { ApiError } from '@/api/client';
import { createQueryClient } from './queryClient';

vi.mock('sonner', () => ({ toast: { error: vi.fn() } }));

const toastError = vi.mocked(toast.error);

function retryOf(client: QueryClient) {
  const retry = client.getDefaultOptions().queries?.retry;
  if (typeof retry !== 'function') throw new Error('se esperaba una función de reintento');
  return retry as (failureCount: number, error: Error) => boolean;
}

/** Corre una mutación que falla, como lo haría un componente. */
async function runFailingMutation(
  client: QueryClient,
  error: unknown,
  options: Record<string, unknown> = {},
): Promise<void> {
  await client
    .getMutationCache()
    .build(client, { mutationFn: () => Promise.reject(error), ...options })
    .execute(undefined)
    .catch(() => undefined);
}

afterEach(() => {
  toastError.mockClear();
});

describe('reintentos de las queries', () => {
  const client = createQueryClient();

  it('no reintenta los errores del cliente (4xx)', () => {
    const retry = retryOf(client);
    expect(retry(0, new ApiError({ status: 404, code: 'NOT_FOUND', message: '' }))).toBe(false);
    expect(retry(0, new ApiError({ status: 403, code: 'FORBIDDEN', message: '' }))).toBe(false);
  });

  it('reintenta dos veces los errores de servidor y de red', () => {
    const retry = retryOf(client);
    const serverError = new ApiError({ status: 503, code: 'SERVICE_UNAVAILABLE', message: '' });
    expect(retry(0, serverError)).toBe(true);
    expect(retry(1, serverError)).toBe(true);
    expect(retry(2, serverError)).toBe(false);
    expect(retry(0, new ApiError({ status: 0, code: 'NETWORK_ERROR', message: '' }))).toBe(true);
  });

  it('las mutaciones no se reintentan solas', () => {
    expect(client.getDefaultOptions().mutations?.retry).toBe(false);
  });
});

describe('toast global de las mutaciones', () => {
  it('avisa cuando una mutación falla', async () => {
    const client = createQueryClient();
    await runFailingMutation(client, new ApiError({ status: 409, code: 'CONFLICT', message: 'Ya existe' }));
    expect(toastError).toHaveBeenCalledWith('Ya existe');
  });

  it('no duplica el aviso si la mutación tiene su propio onError', async () => {
    const client = createQueryClient();
    await runFailingMutation(client, new ApiError({ status: 409, code: 'CONFLICT', message: 'Ya existe' }), {
      onError: () => undefined,
    });
    expect(toastError).not.toHaveBeenCalled();
  });

  it('meta.errorToast=false lo silencia', async () => {
    const client = createQueryClient();
    await runFailingMutation(client, new ApiError({ status: 409, code: 'CONFLICT', message: 'Ya existe' }), {
      meta: { errorToast: false },
    });
    expect(toastError).not.toHaveBeenCalled();
  });

  it('los errores de sesión los maneja el cierre global, no el toast', async () => {
    const client = createQueryClient();
    await runFailingMutation(client, new ApiError({ status: 401, code: 'UNAUTHORIZED', message: 'Sesión vencida' }));
    await runFailingMutation(client, new ApiError({ status: 403, code: 'TENANT_DISABLED', message: 'Deshabilitado' }));
    expect(toastError).not.toHaveBeenCalled();
  });

  it('el 401 del login sí se muestra (no es una sesión que venció)', async () => {
    const client = createQueryClient();
    await runFailingMutation(
      client,
      new ApiError({ status: 401, code: 'BAD_CREDENTIALS', message: 'El email o la contraseña son incorrectos' }),
    );
    expect(toastError).toHaveBeenCalledWith('El email o la contraseña son incorrectos');
  });
});
