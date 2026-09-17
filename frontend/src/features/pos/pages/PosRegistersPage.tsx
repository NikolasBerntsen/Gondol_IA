import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { MonitorSmartphone, Pencil, Plus } from 'lucide-react';
import { getErrorMessage, getFieldErrors } from '@/api/client';
import { useBranch, useBranchQueryKey, useWriteBranch } from '@/branches/BranchContext';
import { BranchPicker } from '@/branches/BranchPicker';
import { useBranchColumn } from '@/branches/branchColumn';
import { StatusPill } from '@/components/gondola';
import {
  Button,
  Card,
  Field,
  Input,
  Modal,
  PageHeader,
  Table,
  Toggle,
  type TableColumn,
} from '@/components/ui';
import { formatDate, formatTime } from '@/lib/format';
import { posApi, posKeys } from '../api';
import type { PosRegister } from '../types';

interface FormState {
  open: boolean;
  register: PosRegister | null;
}

/** Cajas del comercio (SPEC §15.2). Solo TENANT_ADMIN: crear, renombrar, activar o desactivar. */
export default function PosRegistersPage() {
  const queryClient = useQueryClient();
  const { isAll } = useBranch();
  const branchColumn = useBranchColumn<PosRegister>();
  const [includeInactive, setIncludeInactive] = useState(true);
  const [form, setForm] = useState<FormState>({ open: false, register: null });

  const registersQuery = useQuery({
    queryKey: useBranchQueryKey(...posKeys.registers(includeInactive)),
    queryFn: () => posApi.registers(includeInactive),
  });

  const columns: Array<TableColumn<PosRegister> | null> = [
    {
      id: 'name',
      header: 'Caja',
      mobile: 'title',
      cell: (row) => <span className="font-semibold text-foreground">{row.name}</span>,
    },
    branchColumn,
    {
      id: 'state',
      header: 'Estado',
      mobile: 'aside',
      cell: (row) =>
        row.active ? (
          <StatusPill tone="ok">Activa</StatusPill>
        ) : (
          <StatusPill tone="neutral" solid>
            Desactivada
          </StatusPill>
        ),
    },
    {
      id: 'session',
      header: 'Turno abierto',
      mobile: 'field',
      cell: (row) =>
        row.openSession ? (
          <span className="text-base">
            {row.openSession.openedByName ?? 'Cajero'}
            <span className="block text-xs text-muted-foreground">
              desde {formatTime(row.openSession.openedAt)}
            </span>
          </span>
        ) : (
          <span className="text-muted-foreground">Libre</span>
        ),
    },
    {
      id: 'createdAt',
      header: 'Alta',
      hideBelow: 'lg',
      mobile: 'hidden',
      cell: (row) => <span className="tabular-nums text-muted-foreground">{formatDate(row.createdAt)}</span>,
    },
    {
      id: 'actions',
      header: '',
      align: 'right',
      mobile: 'actions',
      cell: (row) => (
        <Button
          variant="ghost"
          size="sm"
          onClick={() => setForm({ open: true, register: row })}
          leftIcon={<Pencil aria-hidden="true" />}
        >
          Editar
        </Button>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        title="Cajas del punto de venta"
        description="Cada caja admite un turno abierto por vez. Desactivá las que ya no se usan."
        icon={MonitorSmartphone}
        actions={
          <Button onClick={() => setForm({ open: true, register: null })} leftIcon={<Plus aria-hidden="true" />}>
            Nueva caja
          </Button>
        }
      >
        <Toggle
          checked={includeInactive}
          onChange={setIncludeInactive}
          label="Mostrar las cajas desactivadas"
        />
      </PageHeader>

      <Card padding="none" className="mt-4 overflow-hidden">
        <Table
          columns={columns}
          data={registersQuery.data}
          rowKey={(row) => row.id}
          loading={registersQuery.isPending}
          error={registersQuery.isError ? registersQuery.error : undefined}
          onRetry={() => void registersQuery.refetch()}
          caption="Cajas del punto de venta"
          rowSeverity={(row) => (row.active ? 'none' : 'info')}
          empty={{
            icon: MonitorSmartphone,
            title: isAll ? 'Todavía no hay cajas' : 'Esta sucursal no tiene cajas',
            description: 'Creá la primera caja para que los cajeros puedan abrir su turno y cobrar.',
            action: (
              <Button onClick={() => setForm({ open: true, register: null })} leftIcon={<Plus aria-hidden="true" />}>
                Nueva caja
              </Button>
            ),
          }}
        />
      </Card>

      <RegisterFormModal
        key={form.register?.id ?? 'new'}
        open={form.open}
        register={form.register}
        onClose={() => setForm({ open: false, register: null })}
        onSaved={() => {
          setForm({ open: false, register: null });
          void queryClient.invalidateQueries({ queryKey: ['pos'] });
        }}
      />
    </>
  );
}

function RegisterFormModal({
  open,
  register,
  onClose,
  onSaved,
}: {
  open: boolean;
  register: PosRegister | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const editing = !!register;
  const writeBranch = useWriteBranch();
  const [name, setName] = useState(register?.name ?? '');
  const [active, setActive] = useState(register?.active ?? true);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);

  useEffect(() => {
    if (open) {
      setName(register?.name ?? '');
      setActive(register?.active ?? true);
      setErrors({});
      setFormError(null);
    }
  }, [open, register]);

  const save = useMutation({
    mutationFn: () => {
      const body = {
        name: name.trim(),
        active,
        branchId: editing ? register.branchId : writeBranch.branchId,
      };
      return editing ? posApi.updateRegister(register.id, body) : posApi.createRegister(body);
    },
    meta: { errorToast: false },
    onSuccess: () => {
      toast.success(editing ? 'Guardaste la caja.' : 'Creaste la caja.');
      onSaved();
    },
    onError: (error) => {
      setErrors(getFieldErrors(error));
      setFormError(getErrorMessage(error));
    },
  });

  const submit = () => {
    setFormError(null);
    if (!name.trim()) {
      setErrors({ name: 'Poné un nombre, por ejemplo "Caja 1".' });
      return;
    }
    if (!editing && !writeBranch.isReady) {
      setFormError('Elegí una sucursal para la caja.');
      return;
    }
    save.mutate();
  };

  return (
    <Modal
      open={open}
      onClose={onClose}
      preventClose={save.isPending}
      size="sm"
      title={editing ? 'Editar caja' : 'Nueva caja'}
      description={
        editing
          ? 'Cambiá el nombre o desactivala. Una caja con un turno abierto no se puede desactivar.'
          : 'Dale un nombre que el cajero reconozca en el mostrador.'
      }
      footer={
        <>
          <Button variant="outline" onClick={onClose} disabled={save.isPending}>
            Cancelar
          </Button>
          <Button onClick={submit} loading={save.isPending}>
            {editing ? 'Guardar cambios' : 'Crear caja'}
          </Button>
        </>
      }
    >
      <form
        className="grid gap-4"
        onSubmit={(event) => {
          event.preventDefault();
          submit();
        }}
      >
        {editing ? (
          <p className="text-base text-muted-foreground">
            Sucursal: <span className="font-semibold text-foreground">{register.branchName}</span>
          </p>
        ) : (
          <BranchPicker
            value={writeBranch.branchId}
            onChange={writeBranch.setBranchId}
            label="Sucursal"
            hint="La caja pertenece a una sucursal."
          />
        )}

        <Field label="Nombre de la caja" error={errors.name}>
          <Input
            data-autofocus
            value={name}
            onChange={(event) => setName(event.target.value)}
            maxLength={60}
            placeholder="Caja 1"
            invalid={!!errors.name}
          />
        </Field>

        <Toggle
          checked={active}
          onChange={setActive}
          label="Caja activa"
          description="Las cajas desactivadas no aparecen al abrir un turno."
        />

        {formError ? (
          <p role="alert" className="text-base text-crit-ink">
            {formError}
          </p>
        ) : null}
      </form>
    </Modal>
  );
}
