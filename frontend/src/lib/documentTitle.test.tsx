import { renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { APP_NAME, formatDocumentTitle, useDocumentTitle } from './documentTitle';

const wrapper = ({ children }: { children: ReactNode }) => (
  <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>{children}</MemoryRouter>
);

describe('formatDocumentTitle', () => {
  it('agrega el nombre de la app como sufijo', () => {
    expect(formatDocumentTitle('Alertas')).toBe(`Alertas · ${APP_NAME}`);
  });

  it('normaliza los espacios', () => {
    expect(formatDocumentTitle('  Punto   de venta ')).toBe(`Punto de venta · ${APP_NAME}`);
  });

  it('sin título (o con el nombre de la app) deja solo el nombre', () => {
    expect(formatDocumentTitle()).toBe(APP_NAME);
    expect(formatDocumentTitle(null)).toBe(APP_NAME);
    expect(formatDocumentTitle('   ')).toBe(APP_NAME);
    expect(formatDocumentTitle(APP_NAME)).toBe(APP_NAME);
  });
});

describe('useDocumentTitle', () => {
  it('pone el título de la pestaña', () => {
    renderHook(() => useDocumentTitle('Yerba Serrana'), { wrapper });
    expect(document.title).toBe(`Yerba Serrana · ${APP_NAME}`);
  });

  it('un título vacío no pisa el que ya había', () => {
    document.title = 'Anterior';
    renderHook(() => useDocumentTitle('   '), { wrapper });
    expect(document.title).toBe('Anterior');
  });
});
