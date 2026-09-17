import { Construction } from 'lucide-react';
import { EmptyState, PageHeader } from '@/components/ui';

/**
 * Terminal del POS (módulo H). Se renderiza dentro del **AppShell compacto**: el shell no pone
 * padding ni ancho máximo, así que el layout de dos paneles y su scroll los maneja esta página.
 */
export default function PosTerminalPage() {
  return (
    <div className="flex min-h-full flex-col px-4 pb-10 pt-5 sm:px-6 lg:px-8 lg:pt-7">
      <PageHeader title="Punto de venta" description="Abrí tu caja, escaneá los productos y cobrá." />
      <EmptyState
        icon={Construction}
        title="Sección en construcción"
        description="Estamos terminando esta pantalla. Muy pronto vas a poder usarla."
        bordered
      />
    </div>
  );
}
