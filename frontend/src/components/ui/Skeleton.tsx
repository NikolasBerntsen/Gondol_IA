import type { HTMLAttributes } from 'react';
import { cn } from '@/lib/cn';

/** Bloque gris animado para estados de carga (`<Skeleton className="h-4 w-32" />`). */
export function Skeleton({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div aria-hidden="true" className={cn('animate-pulse rounded-lg bg-slate-100', className)} {...props} />;
}
