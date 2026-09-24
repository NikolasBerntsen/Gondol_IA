import { act, fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { anchorHidden, DropdownItem, DropdownPanel, floatingPosition, useDropdown } from './Dropdown';

describe('floatingPosition', () => {
  const viewport = { width: 1280, height: 800 };
  const size = { width: 240, height: 200 };

  it('abre hacia abajo si entra, alineado al borde derecho del disparador', () => {
    const anchor = { top: 100, bottom: 132, left: 900, right: 932 };
    expect(floatingPosition(anchor, size, viewport, 'end')).toEqual({ top: 140, left: 692, side: 'bottom' });
    expect(floatingPosition(anchor, size, viewport, 'start')).toEqual({ top: 140, left: 900, side: 'bottom' });
  });

  it('en la última fila abre hacia arriba en vez de salirse de la pantalla', () => {
    const anchor = { top: 700, bottom: 732, left: 900, right: 932 };
    expect(floatingPosition(anchor, size, viewport)).toEqual({ top: 492, left: 692, side: 'top' });
  });

  it('si no entra de ningún lado va donde hay más lugar, sin salirse de la pantalla', () => {
    const anchor = { top: 150, bottom: 182, left: 900, right: 932 };
    const tall = { width: 240, height: 400 };
    const small = { width: 1280, height: 500 };
    expect(floatingPosition(anchor, tall, small)).toEqual({ top: 92, left: 692, side: 'bottom' });
  });

  it('se corre para quedar dentro del gutter horizontal', () => {
    const nearLeft = { top: 100, bottom: 132, left: 10, right: 42 };
    expect(floatingPosition(nearLeft, size, viewport, 'end').left).toBe(16);
    const nearRight = { top: 100, bottom: 132, left: 1250, right: 1270 };
    expect(floatingPosition(nearRight, size, viewport, 'start').left).toBe(1280 - 16 - 240);
  });

  it('la posición sigue al disparador aunque salga de la pantalla (el menú lo cierra useDropdown)', () => {
    const above = { top: -80, bottom: -48, left: 900, right: 932 };
    expect(floatingPosition(above, size, viewport)).toMatchObject({ top: -40, side: 'bottom' });
  });
});

/** Menú de acciones de una fila, dentro de un contenedor con scroll como el de `Table`. */
function RowMenu({ floating }: { floating: boolean }) {
  const dropdown = useDropdown({ floating });
  return (
    <div data-testid="table" className="relative overflow-x-auto">
      <div className="relative inline-block">
        <button {...dropdown.triggerProps}>Acciones</button>
        {dropdown.open && (
          <DropdownPanel {...dropdown.panelProps} aria-label="Acciones de la fila">
            <DropdownItem onClick={() => dropdown.close()}>Ver el detalle</DropdownItem>
            <DropdownItem onClick={() => dropdown.close()}>Editar los datos</DropdownItem>
          </DropdownPanel>
        )}
      </div>
      <button type="button">Siguiente fila</button>
    </div>
  );
}

function renderMenu(floating: boolean) {
  return render(
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <RowMenu floating={floating} />
    </MemoryRouter>,
  );
}

describe('DropdownPanel flotante', () => {
  let anchorTop = 700;

  beforeEach(() => {
    anchorTop = 700;
    // jsdom no calcula layout: se fijan la pantalla, el tamaño del panel y la posición del disparador.
    vi.spyOn(document.documentElement, 'clientWidth', 'get').mockReturnValue(1280);
    vi.spyOn(document.documentElement, 'clientHeight', 'get').mockReturnValue(800);
    vi.spyOn(HTMLElement.prototype, 'offsetWidth', 'get').mockReturnValue(240);
    vi.spyOn(HTMLElement.prototype, 'offsetHeight', 'get').mockReturnValue(120);
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function rect(this: HTMLElement) {
      const top = this.textContent === 'Acciones' ? anchorTop : 0;
      return { top, bottom: top + 32, left: 1000, right: 1032, width: 32, height: 32, x: 1000, y: top } as DOMRect;
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('va en un portal fuera del contenedor de la tabla y abre hacia arriba en la última fila', async () => {
    const user = userEvent.setup();
    renderMenu(true);

    await user.click(screen.getByRole('button', { name: 'Acciones' }));

    const menu = screen.getByRole('menu', { name: 'Acciones de la fila' });
    expect(screen.getByTestId('table')).not.toContainElement(menu);
    expect(menu.parentElement).toBe(document.body);
    expect(menu).toHaveClass('fixed');
    expect(menu).not.toHaveClass('absolute');
    expect(menu).toHaveAttribute('data-side', 'top');
    expect(menu.style.top).toBe(`${700 - 8 - 120}px`);
    expect(menu.style.left).toBe(`${1032 - 240}px`);
  });

  it('se reubica con el scroll de cualquier contenedor', async () => {
    const user = userEvent.setup();
    renderMenu(true);
    await user.click(screen.getByRole('button', { name: 'Acciones' }));
    const menu = screen.getByRole('menu');

    anchorTop = 200;
    act(() => {
      fireEvent.scroll(screen.getByTestId('table'));
    });
    expect(menu).toHaveAttribute('data-side', 'bottom');
    expect(menu.style.top).toBe(`${200 + 32 + 8}px`);

    anchorTop = 650;
    act(() => {
      window.dispatchEvent(new Event('resize'));
    });
    expect(menu).toHaveAttribute('data-side', 'top');
  });

  it('se cierra si el scroll saca el botón de la pantalla, sin perder el foco', async () => {
    const user = userEvent.setup();
    renderMenu(true);
    const trigger = screen.getByRole('button', { name: 'Acciones' });
    await user.click(trigger);
    const focus = vi.spyOn(trigger, 'focus');

    // Arriba del todo, fuera de la pantalla: antes el panel seguía ahí, suelto sobre el encabezado.
    anchorTop = -80;
    act(() => {
      fireEvent.scroll(window);
    });

    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
    // El foco vuelve al botón (Tab sigue desde ahí) sin scrollear la página de vuelta hasta él.
    expect(focus).toHaveBeenCalledWith({ preventScroll: true });
    expect(trigger).toHaveFocus();
  });

  it('se cierra si el scroll deja el botón debajo de la barra superior', async () => {
    const user = userEvent.setup();
    renderMenu(true);
    const trigger = screen.getByRole('button', { name: 'Acciones' });
    await user.click(trigger);
    const topbar = document.createElement('header');
    document.body.append(topbar);
    const elementFromPoint = vi.fn<(x: number, y: number) => Element | null>(() => trigger);
    Object.defineProperty(document, 'elementFromPoint', { value: elementFromPoint, configurable: true });

    try {
      // Todavía se ve: el menú sigue abierto y se reubica.
      anchorTop = 300;
      act(() => {
        fireEvent.scroll(screen.getByTestId('table'));
      });
      expect(screen.getByRole('menu')).toBeInTheDocument();
      expect(elementFromPoint).toHaveBeenLastCalledWith(1016, 316);

      // La barra fija lo tapa (sigue dentro de la pantalla): se cierra.
      anchorTop = 20;
      elementFromPoint.mockReturnValue(topbar);
      act(() => {
        fireEvent.scroll(window);
      });
      expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    } finally {
      Reflect.deleteProperty(document, 'elementFromPoint');
      topbar.remove();
    }
  });

  it('mantiene el teclado: foco en el primer ítem, flechas, Esc devuelve el foco al botón', async () => {
    const user = userEvent.setup();
    renderMenu(true);
    const trigger = screen.getByRole('button', { name: 'Acciones' });

    await user.click(trigger);
    expect(trigger).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByRole('menuitem', { name: 'Ver el detalle' })).toHaveFocus();

    await user.keyboard('{ArrowDown}');
    expect(screen.getByRole('menuitem', { name: 'Editar los datos' })).toHaveFocus();

    await user.keyboard('{Escape}');
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });

  it('Tab cierra el menú y sigue desde el botón, no desde el final de la página', async () => {
    const user = userEvent.setup();
    renderMenu(true);
    const trigger = screen.getByRole('button', { name: 'Acciones' });
    await user.click(trigger);

    fireEvent.keyDown(screen.getByRole('menuitem', { name: 'Ver el detalle' }), { key: 'Tab' });

    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });

  it('se cierra con un clic afuera, pero no con un clic adentro', async () => {
    const user = userEvent.setup();
    renderMenu(true);
    await user.click(screen.getByRole('button', { name: 'Acciones' }));

    fireEvent.pointerDown(screen.getByRole('menu'));
    expect(screen.getByRole('menu')).toBeInTheDocument();

    await user.click(document.body);
    expect(screen.queryByRole('menu')).not.toBeInTheDocument();
  });

  it('sin `floating` el panel sigue anclado dentro de su contenedor (menús de la barra superior)', async () => {
    const user = userEvent.setup();
    renderMenu(false);

    await user.click(screen.getByRole('button', { name: 'Acciones' }));

    const menu = screen.getByRole('menu');
    expect(screen.getByTestId('table')).toContainElement(menu);
    expect(menu).toHaveClass('absolute');
    expect(menu).not.toHaveClass('fixed');
    expect(menu).not.toHaveAttribute('data-side');
  });
});

describe('anchorHidden', () => {
  afterEach(() => {
    Reflect.deleteProperty(document, 'elementFromPoint');
    vi.restoreAllMocks();
  });

  function anchorAt(top: number) {
    const anchor = document.createElement('button');
    anchor.append(document.createElement('svg'));
    vi.spyOn(anchor, 'getBoundingClientRect').mockReturnValue({
      top,
      bottom: top + 32,
      left: 1000,
      right: 1032,
    } as DOMRect);
    return anchor;
  }

  beforeEach(() => {
    vi.spyOn(document.documentElement, 'clientWidth', 'get').mockReturnValue(1280);
    vi.spyOn(document.documentElement, 'clientHeight', 'get').mockReturnValue(800);
  });

  it('fuera de la pantalla, arriba o abajo, está oculto', () => {
    expect(anchorHidden(anchorAt(-40), null)).toBe(true);
    expect(anchorHidden(anchorAt(790), null)).toBe(true);
    expect(anchorHidden(anchorAt(400), null)).toBe(false);
  });

  it('se ve si lo que está encima es el botón, algo de adentro o el propio panel', () => {
    const anchor = anchorAt(400);
    const panel = document.createElement('div');
    const item = document.createElement('button');
    panel.append(item);
    const elementFromPoint = vi.fn<(x: number, y: number) => Element | null>();
    Object.defineProperty(document, 'elementFromPoint', { value: elementFromPoint, configurable: true });

    elementFromPoint.mockReturnValue(anchor.firstElementChild);
    expect(anchorHidden(anchor, panel)).toBe(false);
    elementFromPoint.mockReturnValue(item);
    expect(anchorHidden(anchor, panel)).toBe(false);
    elementFromPoint.mockReturnValue(document.createElement('header'));
    expect(anchorHidden(anchor, panel)).toBe(true);
  });
});
