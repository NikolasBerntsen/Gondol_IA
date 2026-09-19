import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { ArrowLeft, Printer } from 'lucide-react';
import { Ticket80mm } from '@/components/gondola';
import { Alert, Button, ErrorState, Skeleton } from '@/components/ui';
import { formatDateTime } from '@/lib/format';
import { posApi, posKeys } from '../api';
import { ticketToTicketData } from '../ticket';

/** Ancho del rollo térmico (SPEC §15.3: "CSS de impresión 80 mm"). */
const PAPER_WIDTH_MM = 80;
/** Alto de hoja mientras el ticket todavía no se midió (un ticket de almacén típico entra holgado). */
const FALLBACK_HEIGHT_MM = 200;
const MM_PER_PX = 25.4 / 96;

/**
 * Impresión del comprobante (SPEC §9.3, §15.3). Va **fuera del AppShell**: en pantalla es una hoja de
 * 80 mm centrada y al imprimir queda solo el ticket (los controles llevan `gd-no-print`).
 * Con `?print=1` dispara `window.print()` apenas carga (reimpresión desde el historial).
 * <p>
 * La hoja de impresión mide 80 mm de ancho y el alto del ticket (`@page`, sin márgenes: el ticket trae su propio
 * margen interno de 4 mm), así la impresora térmica corta donde termina el comprobante y el PDF no sale en A4/Carta.
 * Al imprimir todo el fondo es blanco, aunque la pantalla esté en oscuro.
 */
export default function PosTicketPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const saleId = Number(id);
  const autoPrinted = useRef(false);

  const ticketQuery = useQuery({
    queryKey: posKeys.ticket(saleId),
    queryFn: () => posApi.ticket(saleId),
    enabled: Number.isFinite(saleId) && saleId > 0,
  });

  const ticket = ticketQuery.data;

  // Alto real del ticket en mm para el `@page`: se mide en pantalla (mismo ancho de 80 mm que en el papel).
  const ticketRef = useRef<HTMLDivElement>(null);
  const [heightMm, setHeightMm] = useState<number | null>(null);
  useLayoutEffect(() => {
    const element = ticketRef.current;
    if (!element) return undefined;
    const measure = () => setHeightMm(Math.ceil(element.getBoundingClientRect().height * MM_PER_PX) + 2);
    measure();
    if (typeof ResizeObserver === 'undefined') return undefined;
    const observer = new ResizeObserver(measure);
    observer.observe(element);
    return () => observer.disconnect();
  }, [ticket]);

  useEffect(() => {
    if (!ticket || autoPrinted.current) return;
    if (searchParams.get('print') !== '1') return;
    autoPrinted.current = true;
    // Esperamos al layout para que la hoja ya esté dibujada cuando abre el diálogo de impresión.
    const timer = window.setTimeout(() => window.print(), 300);
    return () => window.clearTimeout(timer);
  }, [ticket, searchParams]);

  return (
    <div className="min-h-dvh bg-background px-4 py-6 print:min-h-0 print:bg-white print:p-0">
      <style>{`@page { size: ${PAPER_WIDTH_MM}mm ${heightMm ?? FALLBACK_HEIGHT_MM}mm; margin: 0; }`}</style>
      <div className="mx-auto flex w-full max-w-[420px] flex-col items-center gap-4 print:mx-0 print:block print:w-auto print:max-w-none">
        <div className="gd-no-print flex w-full items-center justify-between gap-2">
          <Button
            variant="ghost"
            size="sm"
            onClick={() => navigate(-1)}
            leftIcon={<ArrowLeft aria-hidden="true" />}
          >
            Volver
          </Button>
          <Button
            size="sm"
            onClick={() => window.print()}
            disabled={!ticket}
            leftIcon={<Printer aria-hidden="true" />}
          >
            Imprimir
          </Button>
        </div>

        {ticketQuery.isPending ? (
          <Skeleton className="h-[520px] w-[302px] rounded-panel" />
        ) : ticketQuery.isError ? (
          <div className="gd-no-print w-full rounded-panel border border-border bg-card p-4">
            <ErrorState
              error={ticketQuery.error}
              onRetry={() => void ticketQuery.refetch()}
              title="No pudimos traer el comprobante"
            />
          </div>
        ) : ticket ? (
          <>
            {ticket.status === 'VOIDED' ? (
              <Alert tone="crit" title="Venta anulada" className="gd-no-print w-full">
                Se anuló el {ticket.voidedAt ? formatDateTime(ticket.voidedAt) : 'sin fecha'}
                {ticket.voidReason ? `: ${ticket.voidReason}` : '.'} El stock volvió a sus lotes.
              </Alert>
            ) : null}
            <div ref={ticketRef} className="w-[80mm] max-w-full print:max-w-none">
              <Ticket80mm
                data={ticketToTicketData(ticket)}
                className="w-full print:bg-white print:[-webkit-mask:none] print:[mask:none]"
              />
            </div>
          </>
        ) : null}
      </div>
    </div>
  );
}
