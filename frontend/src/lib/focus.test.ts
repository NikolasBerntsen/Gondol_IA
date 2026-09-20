import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { getFocusableElements, trapTabKey } from './focus';

/**
 * jsdom no hace layout: `getClientRects()` siempre devuelve una lista vacía y el filtro de
 * `getFocusableElements` descartaría todo. Se simula que los elementos ocupan lugar.
 */
function withLayout() {
  vi.spyOn(Element.prototype, 'getClientRects').mockImplementation(
    () => [{ width: 10, height: 10 }] as unknown as DOMRectList,
  );
}

let container: HTMLElement;

beforeEach(() => {
  withLayout();
  container = document.createElement('div');
  container.tabIndex = -1;
  document.body.appendChild(container);
});

afterEach(() => {
  container.remove();
  vi.restoreAllMocks();
});

function tab(shift = false): KeyboardEvent {
  const event = new KeyboardEvent('keydown', { key: 'Tab', shiftKey: shift, cancelable: true });
  trapTabKey(event, container);
  return event;
}

describe('getFocusableElements', () => {
  it('encuentra enlaces, botones y campos habilitados', () => {
    container.innerHTML = `
      <a href="/x">enlace</a>
      <button>ok</button>
      <input>
      <select></select>
      <textarea></textarea>
      <div tabindex="0">foco</div>
      <div contenteditable="true">texto</div>`;
    expect(getFocusableElements(container)).toHaveLength(7);
  });

  it('descarta lo deshabilitado, lo oculto y lo que no entra en el tabulado', () => {
    container.innerHTML = `
      <button disabled>no</button>
      <input type="hidden">
      <a>sin href</a>
      <div tabindex="-1">no</div>
      <button inert>no</button>
      <button aria-hidden="true">no</button>`;
    expect(getFocusableElements(container)).toHaveLength(0);
  });

  it('sin contenedor devuelve una lista vacía', () => {
    expect(getFocusableElements(null)).toEqual([]);
  });
});

describe('trapTabKey', () => {
  it('desde el último vuelve al primero', () => {
    container.innerHTML = '<button id="a">a</button><button id="b">b</button>';
    const [first, last] = getFocusableElements(container);
    last.focus();
    const event = tab();
    expect(event.defaultPrevented).toBe(true);
    expect(document.activeElement).toBe(first);
  });

  it('con Shift desde el primero va al último', () => {
    container.innerHTML = '<button id="a">a</button><button id="b">b</button>';
    const [first, last] = getFocusableElements(container);
    first.focus();
    const event = tab(true);
    expect(event.defaultPrevented).toBe(true);
    expect(document.activeElement).toBe(last);
  });

  it('en el medio deja que el navegador siga tabulando', () => {
    container.innerHTML = '<button>a</button><button id="medio">b</button><button>c</button>';
    getFocusableElements(container)[1].focus();
    expect(tab().defaultPrevented).toBe(false);
  });

  it('si el foco está afuera lo trae adentro', () => {
    container.innerHTML = '<button id="a">a</button><button id="b">b</button>';
    const outside = document.createElement('button');
    document.body.appendChild(outside);
    outside.focus();
    tab();
    expect(document.activeElement).toBe(getFocusableElements(container)[0]);
    outside.remove();
  });

  it('sin nada enfocable el foco va al contenedor', () => {
    container.innerHTML = '<p>solo texto</p>';
    const event = tab();
    expect(event.defaultPrevented).toBe(true);
    expect(document.activeElement).toBe(container);
  });

  it('ignora las demás teclas y los contenedores nulos', () => {
    container.innerHTML = '<button>a</button>';
    const escape = new KeyboardEvent('keydown', { key: 'Escape', cancelable: true });
    trapTabKey(escape, container);
    expect(escape.defaultPrevented).toBe(false);
    const event = new KeyboardEvent('keydown', { key: 'Tab', cancelable: true });
    trapTabKey(event, null);
    expect(event.defaultPrevented).toBe(false);
  });
});
