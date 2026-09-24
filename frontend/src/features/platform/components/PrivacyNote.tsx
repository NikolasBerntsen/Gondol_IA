import { Lock } from 'lucide-react';
import { cn } from '@/lib/cn';
import { useConsoleRole } from '../useConsoleRole';

export interface PrivacyNoteProps {
  className?: string;
  /** Texto propio de la pantalla; por defecto el de la consola (SPEC §3.4.3). */
  children?: React.ReactNode;
}

/**
 * Recordatorio fijo de la consola de dueños: lo que se ve acá es administrativo y agregado.
 * Nunca hay productos, stock, ventas, alertas ni chats de un cliente. Soporte, que también entra a Clientes y Módulos,
 * ve su propia versión: los chats sí los atiende, desde su bandeja.
 */
export function PrivacyNote({ className, children }: PrivacyNoteProps) {
  const { isSupport } = useConsoleRole();
  return (
    <div
      className={cn(
        'flex items-start gap-3 rounded-control border border-info/30 bg-info-soft px-3.5 py-2.5 text-info-ink',
        className,
      )}
    >
      <Lock className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
      <p className="text-base">
        {children ??
          (isSupport ? (
            <>
              <strong className="font-semibold">Solo datos administrativos:</strong> acá nunca ves el stock, las ventas
              ni las alertas de los clientes. Lo que te cuentan en un ticket sigue en la bandeja de soporte.
            </>
          ) : (
            <>
              <strong className="font-semibold">No tenés acceso a los datos de tus clientes:</strong> nunca ves su
              stock, sus ventas, sus alertas ni sus chats. Solo datos administrativos y totales de la plataforma.
            </>
          ))}
      </p>
    </div>
  );
}
