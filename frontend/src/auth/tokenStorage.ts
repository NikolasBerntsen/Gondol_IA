/** Clave de `localStorage` donde se guarda el JWT (SPEC §9.6). */
export const TOKEN_STORAGE_KEY = 'gondolia.token';

/** Respaldo en memoria cuando `localStorage` no está disponible (navegación privada, bloqueos). */
let memoryToken: string | null = null;
let memoryOnly = false;

export const tokenStorage = {
  get(): string | null {
    if (memoryOnly) return memoryToken;
    try {
      return window.localStorage.getItem(TOKEN_STORAGE_KEY);
    } catch {
      return memoryToken;
    }
  },

  set(token: string): void {
    memoryToken = token;
    try {
      window.localStorage.setItem(TOKEN_STORAGE_KEY, token);
      memoryOnly = false;
    } catch {
      memoryOnly = true;
    }
  },

  clear(): void {
    memoryToken = null;
    memoryOnly = false;
    try {
      window.localStorage.removeItem(TOKEN_STORAGE_KEY);
    } catch {
      // Sin almacenamiento persistente: no hay nada más que limpiar.
    }
  },
};
