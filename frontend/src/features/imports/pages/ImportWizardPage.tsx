import { Construction } from 'lucide-react';
import { EmptyState, PageHeader } from '@/components/ui';

export default function ImportWizardPage() {
  return (
    <>
      <PageHeader title="Importación" description="Archivo, columnas, revisión, confirmación y resultado." />
      <EmptyState
        icon={Construction}
        title="Sección en construcción"
        description="Estamos terminando esta pantalla. Muy pronto vas a poder usarla."
        bordered
      />
    </>
  );
}
