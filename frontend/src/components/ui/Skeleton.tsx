import type { HTMLAttributes } from 'react';
import { cn } from '@/lib/cn';

/**
 * Bloque neutro con brillo suave para estados de carga (`<Skeleton className="h-4 w-32" />`).
 * El brillo se apaga con `prefers-reduced-motion`.
 */
export function Skeleton({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div aria-hidden="true" className={cn('gd-skeleton rounded-[6px] bg-muted', className)} {...props} />;
}
