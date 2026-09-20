import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { MessageCircle, Pencil, Plus, Trash2, Truck } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { toast } from 'sonner';
import { getErrorMessage, getFieldErrors } from '@/api/client';
import { StatusPill } from '@/components/gondola';
import {
  Button,
  Card,
  ConfirmDialog,
  Field,
  Input,
  Modal,
  PageHeader,
  Table,
  Textarea,
  Toggle,
  Truncate,
  type TableColumn,
} from '@/components/ui';
import { formatNumber } from '@/lib/format';
import { suppliersApi } from '../api';
import { whatsappLink } from '../lib';
import type { SupplierDto, SupplierRequest } from '../types';

interface SupplierForm {
  name: string;
  contactName: string;
  phone: string;
  email: string;
  leadTimeDays: string;
  notes: string;
  active: boolean;
}

const EMPTY: SupplierForm = {
  name: '',
  contactName: '',
  phone: '',
  email: '',
  leadTimeDays: '',
  notes: '',
  active: true,
};

export default function SuppliersPage() {
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<SupplierDto | 'new' | null>(null);
  const [form, setForm] = useState<SupplierForm>(EMPTY);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [deleting, setDeleting] = useState<SupplierDto | null>(null);

  const suppliersQuery = useQuery({ queryKey: ['suppliers'], queryFn: () => suppliersApi.list() });

  const openForm = (supplier: SupplierDto | 'new') => {
    setEditing(supplier);
    setErrors({});
    setForm(
      supplier === 'new'
        ? EMPTY
        : {
            name: supplier.name,
            contactName: supplier.contactName ?? '',
            phone: supplier.phone ?? '',
            email: supplier.email ?? '',
            leadTimeDays: supplier.leadTimeDays != null ? String(supplier.leadTimeDays) : '',
            notes: supplier.notes ?? '',
            active: supplier.active,
          },
    );
  };

  const set = <K extends keyof SupplierForm>(key: K, value: SupplierForm[K]) => {
    setForm((current) => ({ ...current, [key]: value }));
    setErrors((current) => (current[key] ? { ...current, [key]: '' } : current));
  };

  const save = useMutation({
    mutationFn: (body: SupplierRequest) =>
      editing && editing !== 'new' ? suppliersApi.update(editing.id, body) : suppliersApi.create(body),
    meta: { errorToast: false },
    onSuccess: () => {
      toast.success(editing === 'new' ? 'Proveedor creado.' : 'Proveedor actualizado.');
      void queryClient.invalidateQueries({ queryKey: ['suppliers'] });
      void queryClient.invalidateQueries({ queryKey: ['products'] });
      setEditing(null);
    },
    onError: (error) => {
      const fields = getFieldErrors(error);
      setErrors(fields);
      if (Object.keys(fields).length === 0) toast.error(getErrorMessage(error));
    },
  });

  const remove = useMutation({
    mutationFn: (supplier: SupplierDto) => suppliersApi.remove(supplier.id),
    onSuccess: (result) => {
      toast.success(result?.deactivated ? 'Proveedor dado de baja.' : 'Proveedor eliminado.', {
        description: result?.deactivated
          ? 'Lo usan productos o lotes cargados, así que queda en el historial pero no se ofrece en la carga.'
          : undefined,
      });
      void queryClient.invalidateQueries({ queryKey: ['suppliers'] });
      setDeleting(null);
    },
    onError: () => setDeleting(null),
  });

  const submit = () => {
    const nextErrors: Record<string, string> = {};
    if (!form.name.trim()) nextErrors.name = 'Poné el nombre del proveedor.';
    if (form.email.trim() && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email.trim())) {
      nextErrors.email = 'Revisá el correo: falta el @ o el dominio.';
    }
    const lead = form.leadTimeDays.trim() ? Number(form.leadTimeDays) : null;
    if (lead !== null && (!Number.isInteger(lead) || lead < 0)) {
      nextErrors.leadTimeDays = 'Poné los días de entrega como número entero.';
    }
    setErrors(nextErrors);
    if (Object.keys(nextErrors).length > 0) return;
    save.mutate({
      name: form.name.trim(),
      contactName: form.contactName.trim() || null,
      phone: form.phone.trim() || null,
      email: form.email.trim() || null,
      leadTimeDays: lead,
      notes: form.notes.trim() || null,
      active: form.active,
    });
  };

  const columns: Array<TableColumn<SupplierDto> | null> = [
    {
      id: 'name',
      header: 'Proveedor',
      mobile: 'title',
      cell: (supplier) => (
        <div className="min-w-0">
          <div className="font-medium text-foreground">{supplier.name}</div>
          {supplier.contactName && <div className="text-sm text-muted-foreground">{supplier.contactName}</div>}
        </div>
      ),
    },
    {
      id: 'contact',
      header: 'Contacto',
      mobile: 'field',
      mobileLabel: 'Contacto',
      cell: (supplier) => {
        const wa = whatsappLink(supplier.phone);
        return (
          <div className="min-w-0 text-sm">
            {supplier.phone ? (
              wa ? (
                <a
                  href={wa}
                  target="_blank"
                  rel="noreferrer"
                  className="inline-flex items-center gap-1.5 font-medium text-primary underline-offset-2 hover:underline"
                >
                  <MessageCircle className="h-4 w-4" aria-hidden="true" />
                  <span className="font-mono tabular-nums">{supplier.phone}</span>
                  <span className="sr-only">Escribir por WhatsApp</span>
                </a>
              ) : (
                <span className="font-mono tabular-nums">{supplier.phone}</span>
              )
            ) : (
              <span className="text-muted-foreground">Sin teléfono</span>
            )}
            {supplier.email && (
              <Truncate as="div">
                <a href={`mailto:${supplier.email}`} className="text-muted-foreground underline-offset-2 hover:underline">
                  {supplier.email}
                </a>
              </Truncate>
            )}
          </div>
        );
      },
    },
    {
      id: 'lead',
      header: 'Entrega',
      align: 'right',
      hideBelow: 'lg',
      mobile: 'field',
      mobileLabel: 'Entrega',
      cell: (supplier) =>
        supplier.leadTimeDays != null ? (
          <span className="whitespace-nowrap tabular-nums">
            {formatNumber(supplier.leadTimeDays)} {supplier.leadTimeDays === 1 ? 'día' : 'días'}
          </span>
        ) : (
          <span className="text-muted-foreground">—</span>
        ),
    },
    {
      id: 'products',
      header: 'Productos',
      align: 'right',
      mobile: 'field',
      mobileLabel: 'Productos',
      cell: (supplier) =>
        supplier.productCount > 0 ? (
          <Link
            to={`/app/inventory?q=${encodeURIComponent(supplier.name)}`}
            className="tabular-nums underline-offset-2 hover:underline"
          >
            {formatNumber(supplier.productCount)}
          </Link>
        ) : (
          <span className="tabular-nums text-muted-foreground">0</span>
        ),
    },
    {
      id: 'status',
      header: 'Estado',
      mobile: 'aside',
      cell: (supplier) => (
        <StatusPill tone={supplier.active ? 'ok' : 'neutral'}>{supplier.active ? 'Activo' : 'Dado de baja'}</StatusPill>
      ),
    },
    {
      id: 'actions',
      header: <span className="sr-only">Acciones</span>,
      align: 'right',
      mobile: 'actions',
      cell: (supplier) => (
        <div className="flex items-center justify-end gap-1">
          <Button
            variant="ghost"
            size="icon-sm"
            aria-label={`Editar ${supplier.name}`}
            title="Editar"
            onClick={() => openForm(supplier)}
          >
            <Pencil className="h-4 w-4" aria-hidden="true" />
          </Button>
          <Button
            variant="ghost"
            size="icon-sm"
            aria-label={`Dar de baja ${supplier.name}`}
            title="Dar de baja"
            onClick={() => setDeleting(supplier)}
          >
            <Trash2 className="h-4 w-4" aria-hidden="true" />
          </Button>
        </div>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        title="Proveedores"
        icon={Truck}
        description="Quién te trae cada producto, cuánto tarda y por dónde escribirle."
        actions={
          <Button leftIcon={<Plus className="h-4 w-4" />} onClick={() => openForm('new')}>
            Nuevo proveedor
          </Button>
        }
      />

      <Card padding="none">
        <Table
          columns={columns}
          data={suppliersQuery.data}
          rowKey={(supplier) => supplier.id}
          loading={suppliersQuery.isPending}
          error={suppliersQuery.isError ? suppliersQuery.error : undefined}
          onRetry={() => void suppliersQuery.refetch()}
          rowClassName={(supplier) => (supplier.active ? undefined : 'opacity-70')}
          empty={{
            icon: Truck,
            title: 'Todavía no cargaste proveedores',
            description: 'Cargalos una vez y elegilos en cada ingreso de mercadería.',
            action: (
              <Button leftIcon={<Plus className="h-4 w-4" />} onClick={() => openForm('new')}>
                Cargar el primero
              </Button>
            ),
          }}
        />
      </Card>

      <Modal
        open={editing !== null}
        onClose={() => setEditing(null)}
        title={editing === 'new' ? 'Nuevo proveedor' : 'Editar proveedor'}
        size="lg"
        footer={
          <>
            <Button variant="outline" onClick={() => setEditing(null)}>
              Cancelar
            </Button>
            <Button loading={save.isPending} onClick={submit}>
              {editing === 'new' ? 'Crear proveedor' : 'Guardar cambios'}
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-4">
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Nombre" error={errors.name}>
              <Input
                data-autofocus
                value={form.name}
                onChange={(event) => set('name', event.target.value)}
                placeholder="Distribuidora Paraná"
                invalid={!!errors.name}
              />
            </Field>
            <Field label="Persona de contacto" optional error={errors.contactName}>
              <Input
                value={form.contactName}
                onChange={(event) => set('contactName', event.target.value)}
                placeholder="Julián Ferrer"
              />
            </Field>
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Teléfono" optional error={errors.phone} hint="Con este número armamos el link de WhatsApp.">
              <Input
                value={form.phone}
                inputMode="tel"
                onChange={(event) => set('phone', event.target.value)}
                placeholder="341 555-1234"
                className="font-mono"
              />
            </Field>
            <Field label="Correo" optional error={errors.email}>
              <Input
                value={form.email}
                type="email"
                onChange={(event) => set('email', event.target.value)}
                placeholder="ventas@parana.com.ar"
                invalid={!!errors.email}
              />
            </Field>
          </div>
          <Field
            label="Días de entrega"
            optional
            error={errors.leadTimeDays}
            hint="Cuántos días tarda en traerte un pedido."
          >
            <Input
              value={form.leadTimeDays}
              inputMode="numeric"
              onChange={(event) => set('leadTimeDays', event.target.value)}
              placeholder="3"
              className="max-w-[160px] text-right tabular-nums"
              invalid={!!errors.leadTimeDays}
            />
          </Field>
          <Field label="Notas" optional error={errors.notes}>
            <Textarea
              value={form.notes}
              rows={2}
              onChange={(event) => set('notes', event.target.value)}
              placeholder="Entrega los martes y jueves por la mañana."
            />
          </Field>
          <Toggle
            checked={form.active}
            onChange={(checked) => set('active', checked)}
            label="Proveedor activo"
            description="Si lo desactivás deja de aparecer en la carga de mercadería."
          />
        </div>
      </Modal>

      <ConfirmDialog
        open={deleting !== null}
        onClose={() => setDeleting(null)}
        onConfirm={() => {
          if (deleting) remove.mutate(deleting);
        }}
        title={`¿Dar de baja ${deleting?.name ?? 'el proveedor'}?`}
        description="Si ya lo usaste en productos o lotes queda desactivado y sigue en el historial. Si nunca se usó, se elimina."
        confirmLabel="Dar de baja"
        tone="danger"
        loading={remove.isPending}
      />
    </>
  );
}
