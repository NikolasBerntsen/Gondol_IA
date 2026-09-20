import { describe, expect, it } from 'vitest';
import { cn } from './cn';

describe('cn', () => {
  it('junta clases y descarta las condiciones falsas', () => {
    expect(cn('a', false && 'b', undefined, null, 'c')).toBe('a c');
    expect(cn(['a', 'b'], { c: true, d: false })).toBe('a b c');
  });

  it('resuelve conflictos de Tailwind quedándose con la última', () => {
    expect(cn('p-2', 'p-4')).toBe('p-4');
    expect(cn('text-left', 'text-right')).toBe('text-right');
  });

  it('entiende los radios propios de Góndola UI', () => {
    expect(cn('rounded-panel', 'rounded-tag')).toBe('rounded-tag');
    expect(cn('rounded-t-dialog', 'rounded-t-control')).toBe('rounded-t-control');
    // Distinto lado: conviven.
    expect(cn('rounded-t-dialog', 'rounded-b-tag')).toBe('rounded-t-dialog rounded-b-tag');
  });

  it('entiende los tamaños de texto y las sombras propias', () => {
    expect(cn('text-read', 'text-md')).toBe('text-md');
    expect(cn('shadow-pop', 'shadow-sheet')).toBe('shadow-sheet');
  });

  it('sin clases devuelve una cadena vacía', () => {
    expect(cn()).toBe('');
  });
});
