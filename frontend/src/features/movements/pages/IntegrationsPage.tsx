import { Construction } from 'lucide-react';
import { EmptyState, PageHeader } from '@/components/ui';

export default function IntegrationsPage() {
  return (
    <>
      <PageHeader title="Integración POS" description="API key por sucursal, importación de ventas y simulador." />
      <EmptyState
        icon={Construction}
        title="Sección en construcción"
        description="Estamos terminando esta pantalla. Muy pronto vas a poder usarla."
        bordered
      />
    </>
  );
}
