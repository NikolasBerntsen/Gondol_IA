import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState, type FormEvent } from 'react';
import { toast } from 'sonner';
import { getErrorMessage, getFieldErrors } from '@/api/client';
import { Alert, Button, Field, Input, Modal } from '@/components/ui';
import { branchesApi, tenantAdminKeys } from '../api';
import type { TenantBranch } from '../types';

interface BranchFormDialogProps {
  open: boolean;
  onClose: () => void;
  /** `null` = alta. */
  branch: TenantBranch | null;
}

interface FormState {
  name: string;
  code: string;
  address: string;
  city: string;
  province: string;
  phone: string;
}

function initialState(branch: TenantBranch | null): FormState {
  return {
    name: branch?.name ?? '',
    code: branch?.code ?? '',
    address: branch?.address ?? '',
    city: branch?.city ?? '',
    province: branch?.province ?? '',
    phone: branch?.phone ?? '',
  };
}

/** Alta y edición de una sucursal. El estado se cambia desde el listado (desactivar / reactivar). */
export function BranchFormDialog({ open, onClose, branch }: BranchFormDialogProps) {
  const queryClient = useQueryClient();
  const isEdit = branch !== null;
  const [form, setForm] = useState<FormState>(() => initialState(branch));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string>();

  useEffect(() => {
    if (open) {
      setForm(initialState(branch));
      setErrors({});
      setFormError(undefined);
    }
  }, [open, branch]);

  const mutation = useMutation({
    mutationFn: (values: FormState) => {
      const body = {
        name: values.name.trim(),
        code: values.code.trim() || null,
        address: values.address.trim() || null,
        city: values.city.trim() || null,
        province: values.province.trim() || null,
        phone: values.phone.trim() || null,
      };
      return isEdit ? branchesApi.update(branch.id, body) : branchesApi.create(body);
    },
    onSuccess: (saved) => {
      toast.success(isEdit ? 'Sucursal actualizada.' : `Sucursal creada: ${saved.name}`);
      queryClient.invalidateQueries({ queryKey: tenantAdminKeys.branches });
      onClose();
    },
    onError: (error) => {
      setErrors(getFieldErrors(error));
      setFormError(getErrorMessage(error));
    },
  });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (!form.name.trim()) {
      setErrors({ name: 'Escribí el nombre con el que la vas a reconocer.' });
      setFormError(undefined);
      return;
    }
    mutation.mutate(form);
  };

  return (
    <Modal
      open={open}
      onClose={onClose}
      size="lg"
      preventClose={mutation.isPending}
      title={isEdit ? `Editar ${branch.name}` : 'Crear una sucursal'}
      description={
        isEdit
          ? 'Los datos aparecen en los tickets y en los listados por sucursal.'
          : 'Cada sucursal lleva su propio stock, sus lotes, sus ventas y sus alertas.'
      }
      footer={
        <>
          <Button variant="outline" onClick={onClose} disabled={mutation.isPending}>
            Cancelar
          </Button>
          <Button type="submit" form="branch-form" loading={mutation.isPending}>
            {isEdit ? 'Guardar cambios' : 'Crear sucursal'}
          </Button>
        </>
      }
    >
      <form id="branch-form" className="grid gap-4" onSubmit={submit} noValidate>
        {formError && (
          <Alert tone="crit" title="No se pudo guardar">
            {formError}
          </Alert>
        )}

        <div className="grid gap-4 md:grid-cols-[1fr_auto]">
          <Field label="Nombre" error={errors.name} required>
            <Input
              data-autofocus
              value={form.name}
              maxLength={100}
              placeholder="Sucursal Centro"
              onChange={(event) => setForm((state) => ({ ...state, name: event.target.value }))}
            />
          </Field>
          <Field label="Código" optional hint="Para identificarla rápido." error={errors.code}>
            <Input
              value={form.code}
              maxLength={20}
              placeholder="CEN"
              className="font-mono uppercase md:w-32"
              onChange={(event) => setForm((state) => ({ ...state, code: event.target.value }))}
            />
          </Field>
        </div>

        <Field label="Dirección" optional error={errors.address}>
          <Input
            value={form.address}
            maxLength={200}
            placeholder="San Martín 1234"
            onChange={(event) => setForm((state) => ({ ...state, address: event.target.value }))}
          />
        </Field>

        <div className="grid gap-4 md:grid-cols-3">
          <Field label="Localidad" optional error={errors.city}>
            <Input
              value={form.city}
              maxLength={100}
              placeholder="Rosario"
              onChange={(event) => setForm((state) => ({ ...state, city: event.target.value }))}
            />
          </Field>
          <Field label="Provincia" optional error={errors.province}>
            <Input
              value={form.province}
              maxLength={100}
              placeholder="Santa Fe"
              onChange={(event) => setForm((state) => ({ ...state, province: event.target.value }))}
            />
          </Field>
          <Field label="Teléfono" optional error={errors.phone}>
            <Input
              value={form.phone}
              maxLength={50}
              placeholder="341 555-0100"
              onChange={(event) => setForm((state) => ({ ...state, phone: event.target.value }))}
            />
          </Field>
        </div>
      </form>
    </Modal>
  );
}
