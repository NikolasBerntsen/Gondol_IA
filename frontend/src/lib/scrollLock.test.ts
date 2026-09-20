import { afterEach, describe, expect, it } from 'vitest';
import { lockBodyScroll } from './scrollLock';

afterEach(() => {
  document.body.style.overflow = '';
});

describe('lockBodyScroll', () => {
  it('bloquea y libera el scroll del body', () => {
    const release = lockBodyScroll();
    expect(document.body.style.overflow).toBe('hidden');
    release();
    expect(document.body.style.overflow).toBe('');
  });

  it('con bloqueos anidados solo libera el último (modal sobre modal)', () => {
    const first = lockBodyScroll();
    const second = lockBodyScroll();
    expect(document.body.style.overflow).toBe('hidden');
    first();
    expect(document.body.style.overflow).toBe('hidden');
    second();
    expect(document.body.style.overflow).toBe('');
  });

  it('liberar dos veces no descuenta de más', () => {
    const first = lockBodyScroll();
    const second = lockBodyScroll();
    first();
    first();
    expect(document.body.style.overflow).toBe('hidden');
    second();
    expect(document.body.style.overflow).toBe('');
  });

  it('devuelve el overflow que había antes', () => {
    document.body.style.overflow = 'auto';
    const release = lockBodyScroll();
    expect(document.body.style.overflow).toBe('hidden');
    release();
    expect(document.body.style.overflow).toBe('auto');
  });
});
