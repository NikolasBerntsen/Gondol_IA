import { Construction } from 'lucide-react';
import { EmptyState, PageHeader } from '@/components/ui';

export default function ImportsPage() {
  return (
    <>
      <PageHeader title="Importar Excel/CSV" description="Traé tu planilla de productos y stock, revisala y confirmá." />
      <EmptyState
        icon={Construction}
        title="Sección en construcción"
        description="Estamos terminando esta pantalla. Muy pronto vas a poder usarla."
        bordered
      />
    </>
  );
}
