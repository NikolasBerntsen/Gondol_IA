import type { LucideIcon } from 'lucide-react';
import type { Key, KeyboardEvent, ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { EmptyState } from './EmptyState';
import { ErrorState } from './ErrorState';

export type TableAlign = 'left' | 'center' | 'right';

/**
 * Cómo se muestra la columna en la vista de tarjetas (pantallas < `md`):
 * - `title`: título de la tarjeta · `subtitle`: línea debajo del título · `aside`: arriba a la derecha (badges, montos)
 * - `field` (defecto): fila "etiqueta: valor" · `actions`: pie de la tarjeta · `hidden`: no se muestra
 */
export type TableMobileSlot = 'title' | 'subtitle' | 'aside' | 'field' | 'actions' | 'hidden';

export interface TableColumn<T> {
  id: string;
  header: ReactNode;
  cell: (row: T, index: number) => ReactNode;
  align?: TableAlign;
  /** Clases de la celda `<td>`. */
  className?: string;
  /** Clases del encabezado `<th>` (p. ej. un ancho `w-32`). */
  headerClassName?: string;
  /** Oculta la columna de la tabla por debajo de ese breakpoint (sigue visible en tarjetas). */
  hideBelow?: 'lg' | 'xl';
  mobile?: TableMobileSlot;
  /** Etiqueta en la vista tarjetas (por defecto `header`). */
  mobileLabel?: ReactNode;
}

/** Severidad de una fila: pinta la franja de 4 px a la izquierda (docs/design-system.md §5.5). */
export type RowSeverity = 'crit' | 'warn' | 'info' | 'ok' | 'none';

const ROW_SEVERITY_CLASSES: Record<RowSeverity, string> = {
  crit: '[&>td:first-child]:shadow-[inset_4px_0_0_hsl(var(--crit))]',
  warn: '[&>td:first-child]:shadow-[inset_4px_0_0_hsl(var(--warn))]',
  info: '[&>td:first-child]:shadow-[inset_4px_0_0_hsl(var(--info))]',
  ok: '[&>td:first-child]:shadow-[inset_4px_0_0_hsl(var(--ok))]',
  none: '',
};

const CARD_SEVERITY_CLASSES: Record<RowSeverity, string> = {
  crit: 'gd-stripe-crit',
  warn: 'gd-stripe-warn',
  info: 'gd-stripe-info',
  ok: 'gd-stripe-ok',
  none: '',
};

export interface TableEmptyProps {
  icon?: LucideIcon;
  title: ReactNode;
  description?: ReactNode;
  action?: ReactNode;
}

export interface TableProps<T> {
  columns: ReadonlyArray<TableColumn<T> | null | false | undefined>;
  data: readonly T[] | undefined;
  rowKey: (row: T, index: number) => Key;
  /** Primera carga (sin datos todavía): muestra filas esqueleto. */
  loading?: boolean;
  /** Error de la query: muestra `ErrorState` con reintento. */
  error?: unknown;
  onRetry?: () => void;
  empty?: TableEmptyProps;
  onRowClick?: (row: T) => void;
  rowClassName?: (row: T) => string | undefined;
  /** Franja de severidad por fila (urgencia). Devolvé `undefined` o `'none'` para no pintarla. */
  rowSeverity?: (row: T) => RowSeverity | undefined;
  /** `cards` (defecto): lista de tarjetas en mobile. `scroll`: la tabla scrollea horizontalmente. */
  mobileLayout?: 'cards' | 'scroll';
  /** Tarjeta personalizada para mobile (reemplaza la generada con `mobile` de cada columna). */
  renderMobileCard?: (row: T, index: number) => ReactNode;
  /** Descripción para lectores de pantalla. */
  caption?: string;
  skeletonRows?: number;
  dense?: boolean;
  /** Contenido al pie (p. ej. `<Pagination>`). */
  footer?: ReactNode;
  className?: string;
}

const ALIGN_CLASSES: Record<TableAlign, string> = {
  left: 'text-left',
  center: 'text-center',
  right: 'text-right',
};

const HIDE_BELOW_CLASSES = {
  lg: 'hidden lg:table-cell',
  xl: 'hidden xl:table-cell',
} as const;

function onActivate(event: KeyboardEvent, action: () => void) {
  if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault();
    action();
  }
}

/**
 * Tabla de datos responsive. En pantallas chicas se convierte en tarjetas (SPEC §9.5).
 * Incluye estados de carga (esqueleto), error y vacío.
 */
export function Table<T>({
  columns: rawColumns,
  data,
  rowKey,
  loading = false,
  error,
  onRetry,
  empty = { title: 'No hay datos para mostrar' },
  onRowClick,
  rowClassName,
  rowSeverity,
  mobileLayout = 'cards',
  renderMobileCard,
  caption,
  skeletonRows = 5,
  dense = false,
  footer,
  className,
}: TableProps<T>) {
  const columns = rawColumns.filter(Boolean) as TableColumn<T>[];
  const rows = data ?? [];
  const showSkeleton = loading && rows.length === 0;
  const cards = mobileLayout === 'cards';

  if (error && rows.length === 0 && !loading) {
    return (
      <div className={className}>
        <ErrorState error={error} onRetry={onRetry} size="sm" />
      </div>
    );
  }

  if (!showSkeleton && rows.length === 0) {
    return (
      <div className={className}>
        <EmptyState icon={empty.icon} title={empty.title} description={empty.description} action={empty.action} size="sm" />
        {footer}
      </div>
    );
  }

  const cellPadding = dense ? 'px-3 py-1.5' : 'px-3 py-2';

  return (
    <div className={className}>
      <div className={cn('overflow-x-auto', cards && 'hidden md:block')}>
        <table className="min-w-full border-collapse text-base">
          {caption && <caption className="sr-only">{caption}</caption>}
          <thead>
            <tr className="border-b border-border [&>th:first-child]:pl-4">
              {columns.map((column) => (
                <th
                  key={column.id}
                  scope="col"
                  className={cn(
                    cellPadding,
                    'h-9 whitespace-nowrap text-xs font-semibold uppercase tracking-[0.06em] text-muted-foreground',
                    ALIGN_CLASSES[column.align ?? 'left'],
                    column.hideBelow && HIDE_BELOW_CLASSES[column.hideBelow],
                    column.headerClassName,
                  )}
                >
                  {column.header}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-border">
            {showSkeleton
              ? Array.from({ length: skeletonRows }, (_, index) => (
                  <tr key={`skeleton-${index}`} aria-hidden="true">
                    {columns.map((column) => (
                      <td
                        key={column.id}
                        className={cn(cellPadding, column.hideBelow && HIDE_BELOW_CLASSES[column.hideBelow])}
                      >
                        <div className="gd-skeleton h-4 w-full max-w-[10rem] rounded-[4px] bg-muted" />
                      </td>
                    ))}
                  </tr>
                ))
              : rows.map((row, index) => (
                  <tr
                    key={rowKey(row, index)}
                    onClick={onRowClick ? () => onRowClick(row) : undefined}
                    onKeyDown={onRowClick ? (event) => onActivate(event, () => onRowClick(row)) : undefined}
                    tabIndex={onRowClick ? 0 : undefined}
                    className={cn(
                      'transition-colors [&>td:first-child]:pl-4',
                      onRowClick && 'cursor-pointer hover:bg-muted/45 focus-visible:bg-muted/60 focus-visible:outline-none',
                      ROW_SEVERITY_CLASSES[rowSeverity?.(row) ?? 'none'],
                      rowClassName?.(row),
                    )}
                  >
                    {columns.map((column) => (
                      <td
                        key={column.id}
                        className={cn(
                          cellPadding,
                          'align-middle text-foreground',
                          ALIGN_CLASSES[column.align ?? 'left'],
                          column.hideBelow && HIDE_BELOW_CLASSES[column.hideBelow],
                          column.className,
                        )}
                      >
                        {column.cell(row, index)}
                      </td>
                    ))}
                  </tr>
                ))}
          </tbody>
        </table>
      </div>

      {cards && (
        <ul className="divide-y divide-border md:hidden" aria-label={caption}>
          {showSkeleton
            ? Array.from({ length: Math.min(skeletonRows, 4) }, (_, index) => (
                <li key={`skeleton-${index}`} className="space-y-2 p-4" aria-hidden="true">
                  <div className="gd-skeleton h-4 w-2/3 rounded-[4px] bg-muted" />
                  <div className="gd-skeleton h-3 w-1/2 rounded-[4px] bg-muted" />
                  <div className="gd-skeleton h-3 w-3/4 rounded-[4px] bg-muted" />
                </li>
              ))
            : rows.map((row, index) => (
                <li
                  key={rowKey(row, index)}
                  onClick={onRowClick ? () => onRowClick(row) : undefined}
                  onKeyDown={onRowClick ? (event) => onActivate(event, () => onRowClick(row)) : undefined}
                  tabIndex={onRowClick ? 0 : undefined}
                  className={cn(
                    'p-4',
                    onRowClick && 'cursor-pointer active:bg-muted/60 focus-visible:bg-muted/60 focus-visible:outline-none',
                    CARD_SEVERITY_CLASSES[rowSeverity?.(row) ?? 'none'],
                    rowClassName?.(row),
                  )}
                >
                  {renderMobileCard ? renderMobileCard(row, index) : <MobileCard columns={columns} row={row} index={index} />}
                </li>
              ))}
        </ul>
      )}

      {footer}
    </div>
  );
}

function MobileCard<T>({ columns, row, index }: { columns: TableColumn<T>[]; row: T; index: number }) {
  const bySlot = (slot: TableMobileSlot) => columns.filter((column) => (column.mobile ?? 'field') === slot);
  const titles = bySlot('title');
  const subtitles = bySlot('subtitle');
  const asides = bySlot('aside');
  const fields = bySlot('field');
  const actions = bySlot('actions');
  const hasHeader = titles.length > 0 || subtitles.length > 0 || asides.length > 0;

  return (
    <div className="space-y-3">
      {hasHeader && (
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0 space-y-0.5">
            {titles.map((column) => (
              <div key={column.id} className="font-semibold text-foreground">
                {column.cell(row, index)}
              </div>
            ))}
            {subtitles.map((column) => (
              <div key={column.id} className="text-sm text-muted-foreground">
                {column.cell(row, index)}
              </div>
            ))}
          </div>
          {asides.length > 0 && (
            <div className="flex shrink-0 flex-col items-end gap-1 text-right text-base">
              {asides.map((column) => (
                <div key={column.id}>{column.cell(row, index)}</div>
              ))}
            </div>
          )}
        </div>
      )}
      {fields.length > 0 && (
        // `overflow-hidden`: un chip largo (no corta línea) recorta contra la tarjeta en vez de
        // estirar la página a 390 px. Para chips anchos usá `mobile: 'aside'` en la columna.
        <dl className="grid grid-cols-2 gap-x-4 gap-y-2 overflow-hidden text-base">
          {fields.map((column) => (
            <div key={column.id} className="min-w-0">
              <dt className="text-xs text-muted-foreground">{column.mobileLabel ?? column.header}</dt>
              <dd className="mt-0.5 break-words text-foreground">{column.cell(row, index)}</dd>
            </div>
          ))}
        </dl>
      )}
      {actions.length > 0 && (
        <div className="flex flex-wrap justify-end gap-2" onClick={(event) => event.stopPropagation()}>
          {actions.map((column) => (
            <div key={column.id}>{column.cell(row, index)}</div>
          ))}
        </div>
      )}
    </div>
  );
}
