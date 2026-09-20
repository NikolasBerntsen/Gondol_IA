import { afterEach, describe, expect, it, vi } from 'vitest';
import { TOKEN_STORAGE_KEY, tokenStorage } from './tokenStorage';

afterEach(() => {
  vi.restoreAllMocks();
  tokenStorage.clear();
});

describe('tokenStorage', () => {
  it('guarda, lee y borra el JWT en localStorage', () => {
    expect(tokenStorage.get()).toBeNull();
    tokenStorage.set('jwt-123');
    expect(window.localStorage.getItem(TOKEN_STORAGE_KEY)).toBe('jwt-123');
    expect(tokenStorage.get()).toBe('jwt-123');
    tokenStorage.clear();
    expect(tokenStorage.get()).toBeNull();
    expect(window.localStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull();
  });

  it('sin localStorage (navegación privada) la sesión vive en memoria', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('QuotaExceededError');
    });
    tokenStorage.set('jwt-privado');
    expect(tokenStorage.get()).toBe('jwt-privado');
    vi.restoreAllMocks();
    // Nunca llegó al storage del navegador.
    expect(window.localStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull();
    tokenStorage.clear();
    expect(tokenStorage.get()).toBeNull();
  });

  it('si leer el storage falla, cae al respaldo en memoria', () => {
    tokenStorage.set('jwt-123');
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('SecurityError');
    });
    expect(tokenStorage.get()).toBe('jwt-123');
  });

  it('borrar tolera que el storage falle', () => {
    tokenStorage.set('jwt-123');
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw new DOMException('SecurityError');
    });
    expect(() => tokenStorage.clear()).not.toThrow();
    vi.restoreAllMocks();
  });
});
