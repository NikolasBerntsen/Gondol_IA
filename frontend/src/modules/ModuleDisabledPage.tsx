import { Blocks, LifeBuoy } from 'lucide-react';
import { TENANT_MODULE_DESCRIPTIONS, TENANT_MODULE_LABELS, type TenantModule } from '@/api/types';
import { useShellLayout } from '@/components/layout/shellLayout';
import { ButtonLink } from '@/components/ui/Button';
import { Card } from '@/components/ui/Card';
import { EmptyState } from '@/components/ui/EmptyState';
import { PageHeader } from '@/components/ui/PageHeader';
import { cn } from '@/lib/cn';

/** Mensaje exacto de SPEC §14.1 (el backend devuelve el mismo en 403 `MODULE_DISABLED`). */
export const MODULE_DISABLED_MESSAGE =
  'Esta función no está habilitada para tu comercio. Contactá a GondolIA para activarla.';

export interface ModuleDisabledPageProps {
  module?: TenantModule;
}

/** Pantalla que ve un usuario que entra por URL a una función que su comercio no tiene contratada. */
export default function ModuleDisabledPage({ module }: ModuleDisabledPageProps) {
  // El shell normal ya pone el padding de página; en el compacto (`/app/pos`) y fuera del shell
  // (la ruta del ticket) lo pone esta pantalla.
  const { compact, inShell } = useShellLayout();
  const name = module ? TENANT_MODULE_LABELS[module] : 'Esta función';

  return (
    <div className={cn((compact || !inShell) && 'mx-auto w-full max-w-7xl px-4 pb-10 pt-5 sm:px-6 lg:px-8 lg:pt-7')}>
      <PageHeader eyebrow="Función no habilitada" title={name} documentTitle="Función no habilitada" />
      <Card padding="none">
        <EmptyState
          icon={Blocks}
          title="Esta función no está habilitada para tu comercio"
          description={
            <>
              Contactá a GondolIA para activarla.
              {module ? ` ${TENANT_MODULE_DESCRIPTIONS[module]}` : ''}
            </>
          }
          action={
            <ButtonLink to="/app/support" leftIcon={<LifeBuoy aria-hidden="true" />}>
              Escribir a Soporte
            </ButtonLink>
          }
        />
      </Card>
    </div>
  );
}
