import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useDebounce } from './useDebounce';

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

describe('useDebounce', () => {
  it('devuelve el valor inicial enseguida', () => {
    const { result } = renderHook(() => useDebounce('leche'));
    expect(result.current).toBe('leche');
  });

  it('espera a que el valor deje de cambiar', () => {
    const { result, rerender } = renderHook(({ value }) => useDebounce(value, 300), {
      initialProps: { value: 'le' },
    });
    rerender({ value: 'lec' });
    rerender({ value: 'leche' });
    expect(result.current).toBe('le');
    act(() => vi.advanceTimersByTime(299));
    expect(result.current).toBe('le');
    act(() => vi.advanceTimersByTime(1));
    expect(result.current).toBe('leche');
  });

  it('respeta el retardo que se le pase', () => {
    const { result, rerender } = renderHook(({ value }) => useDebounce(value, 50), {
      initialProps: { value: 'a' },
    });
    rerender({ value: 'b' });
    act(() => vi.advanceTimersByTime(50));
    expect(result.current).toBe('b');
  });
});
