import { ArrowLeft, Construction, Printer } from 'lucide-react';
import { useNavigate, useParams } from 'react-router-dom';
import { Button, EmptyState } from '@/components/ui';

/**
 * Página de impresión del ticket (SPEC §9.3, §15.3). Se renderiza **fuera del AppShell**:
 * en pantalla es una hoja de 80 mm centrada y al imprimir queda solo el ticket
 * (los controles llevan `gd-no-print`).
 *
 * El módulo H reemplaza el contenido de la hoja por `<Ticket80mm data={...} />` con los datos de
 * `GET /api/tenant/pos/sales/{id}/ticket`.
 */
export default function PosTicketPage() {
  const { id } = useParams();
  const navigate = useNavigate();

  return (
    <div className="min-h-dvh bg-background px-4 py-6">
      <div className="mx-auto flex w-full max-w-[420px] flex-col items-center gap-4">
        <div className="gd-no-print flex w-full items-center justify-between gap-2">
          <Button variant="ghost" size="sm" onClick={() => navigate(-1)} leftIcon={<ArrowLeft aria-hidden="true" />}>
            Volver
          </Button>
          <Button size="sm" onClick={() => window.print()} leftIcon={<Printer aria-hidden="true" />}>
            Imprimir
          </Button>
        </div>

        <div className="w-full rounded-panel border border-border bg-card p-4 print:border-0 print:bg-transparent print:p-0">
          <EmptyState
            icon={Construction}
            title="Ticket en construcción"
            description={`Acá va el comprobante de la venta ${id ?? ''} en 80 mm, listo para imprimir.`}
          />
        </div>
      </div>
    </div>
  );
}
