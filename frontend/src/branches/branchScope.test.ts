import { afterEach, describe, expect, it, vi } from 'vitest';
import { BRANCH_HEADER, branchScope, branchStorageKey, readStoredBranch, writeStoredBranch } from './branchScope';

afterEach(() => {
  branchScope.set(null);
  vi.restoreAllMocks();
});

describe('branchScope', () => {
  it('arranca sin alcance y guarda el que se le pone', () => {
    expect(branchScope.get()).toBeNull();
    branchScope.set(3);
    expect(branchScope.get()).toBe(3);
    branchScope.set('all');
    expect(branchScope.get()).toBe('all');
    branchScope.set(null);
    expect(branchScope.get()).toBeNull();
  });

  it('el header es el que espera el backend', () => {
    expect(BRANCH_HEADER).toBe('X-Branch-Id');
  });
});

describe('sucursal recordada por usuario', () => {
  it('cada usuario tiene su clave', () => {
    expect(branchStorageKey(7)).toBe('gondolia.branch.7');
    expect(branchStorageKey(8)).not.toBe(branchStorageKey(7));
  });

  it('guarda y recupera la elección', () => {
    writeStoredBranch(7, 3);
    expect(readStoredBranch(7)).toBe(3);
    writeStoredBranch(7, 'all');
    expect(readStoredBranch(7)).toBe('all');
  });

  it('no mezcla la elección de dos usuarios', () => {
    writeStoredBranch(7, 3);
    expect(readStoredBranch(8)).toBeNull();
  });

  it('descarta valores guardados que ya no sirven', () => {
    window.localStorage.setItem(branchStorageKey(7), 'cualquier cosa');
    expect(readStoredBranch(7)).toBeNull();
    window.localStorage.setItem(branchStorageKey(7), '0');
    expect(readStoredBranch(7)).toBeNull();
    window.localStorage.setItem(branchStorageKey(7), '-2');
    expect(readStoredBranch(7)).toBeNull();
    window.localStorage.setItem(branchStorageKey(7), '1.5');
    expect(readStoredBranch(7)).toBeNull();
  });

  it('sin storage disponible no rompe', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('SecurityError');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('SecurityError');
    });
    expect(readStoredBranch(7)).toBeNull();
    expect(() => writeStoredBranch(7, 2)).not.toThrow();
  });
});
