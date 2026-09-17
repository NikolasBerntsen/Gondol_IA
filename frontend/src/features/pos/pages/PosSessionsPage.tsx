import { Construction } from 'lucide-react';
import { EmptyState, PageHeader } from '@/components/ui';

export default function PosSessionsPage() {
  return (
    <>
      <PageHeader title="Cajas y turnos" description="Turnos de caja, ventas del turno y reporte de cierre." />
      <EmptyState
        icon={Construction}
        title="Sección en construcción"
        description="Estamos terminando esta pantalla. Muy pronto vas a poder usarla."
        bordered
      />
    </>
  );
}
