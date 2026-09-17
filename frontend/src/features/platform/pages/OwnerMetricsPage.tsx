import { Construction } from 'lucide-react';
import { EmptyState, PageHeader } from '@/components/ui';

export default function OwnerMetricsPage() {
  return (
    <>
      <PageHeader title="Métricas de GondolIA" />
      <EmptyState
        icon={Construction}
        title="Sección en construcción"
        description="Estamos terminando esta pantalla. Muy pronto vas a poder usarla."
        bordered
      />
    </>
  );
}
