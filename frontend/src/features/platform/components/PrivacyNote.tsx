import { Lock } from 'lucide-react';
import { cn } from '@/lib/cn';

export interface PrivacyNoteProps {
  className?: string;
  /** Texto propio de la pantalla; por defecto el de la consola (SPEC §3.4.3). */
  children?: React.ReactNode;
}

/**
 * Recordatorio fijo de la consola de dueños: lo que se ve acá es administrativo y agregado.
 * Nunca hay productos, stock, ventas, alertas ni chats de un cliente.
 */
export function PrivacyNote({ className, children }: PrivacyNoteProps) {
  return (
    <div
      className={cn(
        'flex items-start gap-3 rounded-control border border-info/30 bg-info-soft px-3.5 py-2.5 text-info-ink',
        className,
      )}
    >
      <Lock className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
      <p className="text-base">
        {children ?? (
          <>
            <strong className="font-semibold">No tenés acceso a los datos de tus clientes:</strong> nunca ves su stock,
            sus ventas, sus alertas ni sus chats. Solo datos administrativos y totales de la plataforma.
          </>
        )}
      </p>
    </div>
  );
}
