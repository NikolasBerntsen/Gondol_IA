import { Construction } from 'lucide-react';
import { EmptyState, PageHeader } from '@/components/ui';

export default function ModulesMatrixPage() {
  return (
    <>
      <PageHeader title="Módulos por cliente" description="Qué módulos tiene habilitado cada comercio y cuánto suma al abono." />
      <EmptyState
        icon={Construction}
        title="Sección en construcción"
        description="Estamos terminando esta pantalla. Muy pronto vas a poder usarla."
        bordered
      />
    </>
  );
}
