import { ChevronLeft, ChevronRight } from 'lucide-react';
import type { PageResponse } from '@/api/types';
import { cn } from '@/lib/cn';
import { formatNumber } from '@/lib/format';

export interface PaginationProps {
  /** Página actual, 0-based (como `PageResponse.page`). */
  page: number;
  totalPages: number;
  totalElements?: number;
  size?: number;
  onPageChange: (page: number) => void;
  disabled?: boolean;
  className?: string;
}

/** Props de `Pagination` a partir de un `PageResponse` (sin `onPageChange`). */
export function pageInfo(data: PageResponse<unknown> | undefined): Pick<PaginationProps, 'page' | 'totalPages' | 'totalElements' | 'size'> {
  return {
    page: data?.page ?? 0,
    totalPages: data?.totalPages ?? 0,
    totalElements: data?.totalElements,
    size: data?.size,
  };
}

type PageSlot = number | 'gap-start' | 'gap-end';

function pageSlots(current: number, total: number): PageSlot[] {
  if (total <= 7) return Array.from({ length: total }, (_, i) => i);
  const slots: PageSlot[] = [0];
  const start = Math.max(1, current - 1);
  const end = Math.min(total - 2, current + 1);
  if (start > 1) slots.push('gap-start');
  for (let i = start; i <= end; i += 1) slots.push(i);
  if (end < total - 2) slots.push('gap-end');
  slots.push(total - 1);
  return slots;
}

const navButton =
  'inline-flex h-8 min-w-8 items-center justify-center gap-1 rounded-control px-2 text-base font-semibold tabular-nums transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:pointer-events-none disabled:opacity-40';

/** Paginación "Mostrando 1–20 de 123" + anterior/siguiente (números de página desde `sm`). */
export function Pagination({ page, totalPages, totalElements, size, onPageChange, disabled, className }: PaginationProps) {
  if (totalPages <= 1 && !totalElements) return null;
  const hasPrev = page > 0;
  const hasNext = page < totalPages - 1;
  const from = size && totalElements ? page * size + 1 : null;
  const to = size && totalElements ? Math.min(totalElements, (page + 1) * size) : null;

  return (
    <nav
      aria-label="Paginación"
      className={cn('flex flex-col items-center justify-between gap-3 border-t border-border px-4 py-2.5 sm:flex-row', className)}
    >
      <p className="text-base text-muted-foreground">
        {from !== null && to !== null && totalElements !== undefined ? (
          <>
            Mostrando <span className="font-semibold text-foreground">{formatNumber(from)}</span>–
            <span className="font-semibold text-foreground">{formatNumber(to)}</span> de{' '}
            <span className="font-semibold text-foreground">{formatNumber(totalElements)}</span>
          </>
        ) : (
          <>
            Página <span className="font-semibold text-foreground">{page + 1}</span> de {Math.max(totalPages, 1)}
          </>
        )}
      </p>
      {totalPages > 1 && (
        <div className="flex items-center gap-1">
          <button
            type="button"
            className={cn(navButton, 'text-muted-foreground hover:bg-muted hover:text-foreground')}
            onClick={() => onPageChange(page - 1)}
            disabled={disabled || !hasPrev}
            aria-label="Página anterior"
          >
            <ChevronLeft className="h-4 w-4" aria-hidden="true" />
            <span className="sm:hidden">Anterior</span>
          </button>
          <div className="hidden items-center gap-1 sm:flex">
            {pageSlots(page, totalPages).map((slot) =>
              typeof slot === 'number' ? (
                <button
                  key={slot}
                  type="button"
                  onClick={() => onPageChange(slot)}
                  disabled={disabled}
                  aria-current={slot === page ? 'page' : undefined}
                  aria-label={`Página ${slot + 1}`}
                  className={cn(
                    navButton,
                    slot === page ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:bg-muted hover:text-foreground',
                  )}
                >
                  {slot + 1}
                </button>
              ) : (
                <span key={slot} className="px-1 text-muted-foreground" aria-hidden="true">
                  …
                </span>
              ),
            )}
          </div>
          <span className="px-2 text-base tabular-nums text-muted-foreground sm:hidden">
            {page + 1} / {totalPages}
          </span>
          <button
            type="button"
            className={cn(navButton, 'text-muted-foreground hover:bg-muted hover:text-foreground')}
            onClick={() => onPageChange(page + 1)}
            disabled={disabled || !hasNext}
            aria-label="Página siguiente"
          >
            <span className="sm:hidden">Siguiente</span>
            <ChevronRight className="h-4 w-4" aria-hidden="true" />
          </button>
        </div>
      )}
    </nav>
  );
}
