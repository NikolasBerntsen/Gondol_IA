import { Construction } from 'lucide-react';
import { EmptyState, PageHeader } from '@/components/ui';

export default function PosRegistersPage() {
  return (
    <>
      <PageHeader title="Cajas" description="Cajas por sucursal: alta, edición y baja." />
      <EmptyState
        icon={Construction}
        title="Sección en construcción"
        description="Estamos terminando esta pantalla. Muy pronto vas a poder usarla."
        bordered
      />
    </>
  );
}
