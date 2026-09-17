import { Construction } from 'lucide-react';
import { EmptyState, PageHeader } from '@/components/ui';

export default function StatisticsPage() {
  return (
    <>
      <PageHeader title="Estadísticas" />
      <EmptyState
        icon={Construction}
        title="Sección en construcción"
        description="Estamos terminando esta pantalla. Muy pronto vas a poder usarla."
        bordered
      />
    </>
  );
}
