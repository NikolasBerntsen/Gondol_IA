import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Pencil, Plus, Tags, Trash2 } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { toast } from 'sonner';
import { getErrorMessage, getFieldErrors, isApiError } from '@/api/client';
import {
  Button,
  ButtonLink,
  Card,
  ConfirmDialog,
  Field,
  Input,
  Modal,
  PageHeader,
  Table,
  type TableColumn,
} from '@/components/ui';
import { formatNumber } from '@/lib/format';
import { categoriesApi } from '../api';
import type { CategoryDto } from '../types';

export default function CategoriesPage() {
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<CategoryDto | 'new' | null>(null);
  const [name, setName] = useState('');
  const [nameError, setNameError] = useState<string>();
  const [deleting, setDeleting] = useState<CategoryDto | null>(null);

  const categoriesQuery = useQuery({ queryKey: ['categories'], queryFn: () => categoriesApi.list() });

  const openForm = (category: CategoryDto | 'new') => {
    setEditing(category);
    setName(category === 'new' ? '' : category.name);
    setNameError(undefined);
  };

  const save = useMutation({
    mutationFn: (value: string) =>
      editing && editing !== 'new' ? categoriesApi.update(editing.id, { name: value }) : categoriesApi.create({ name: value }),
    meta: { errorToast: false },
    onSuccess: () => {
      toast.success(editing === 'new' ? 'Categoría creada.' : 'Categoría actualizada.');
      void queryClient.invalidateQueries({ queryKey: ['categories'] });
      void queryClient.invalidateQueries({ queryKey: ['products'] });
      setEditing(null);
    },
    onError: (error) => {
      const fields = getFieldErrors(error);
      setNameError(fields.name ?? getErrorMessage(error));
    },
  });

  const remove = useMutation({
    mutationFn: (category: CategoryDto) => categoriesApi.remove(category.id),
    meta: { errorToast: false },
    onSuccess: () => {
      toast.success('Categoría eliminada.');
      void queryClient.invalidateQueries({ queryKey: ['categories'] });
      void queryClient.invalidateQueries({ queryKey: ['products'] });
      setDeleting(null);
    },
    onError: (error) => {
      toast.error(
        isApiError(error, 'CONFLICT')
          ? getErrorMessage(error, 'La categoría tiene productos: movelos a otra antes de eliminarla.')
          : getErrorMessage(error),
      );
      setDeleting(null);
    },
  });

  const columns: Array<TableColumn<CategoryDto> | null> = [
    {
      id: 'name',
      header: 'Categoría',
      mobile: 'title',
      cell: (category) => <span className="font-medium text-foreground">{category.name}</span>,
    },
    {
      id: 'products',
      header: 'Productos',
      align: 'right',
      mobile: 'aside',
      cell: (category) =>
        category.productCount > 0 ? (
          <Link
            to={`/app/inventory?categoryId=${category.id}`}
            className="tabular-nums underline-offset-2 hover:underline"
          >
            {formatNumber(category.productCount)}
          </Link>
        ) : (
          <span className="tabular-nums text-muted-foreground">0</span>
        ),
    },
    {
      id: 'actions',
      header: <span className="sr-only">Acciones</span>,
      align: 'right',
      mobile: 'actions',
      cell: (category) => (
        <div className="flex items-center justify-end gap-1">
          <Button
            variant="ghost"
            size="icon-sm"
            aria-label={`Editar ${category.name}`}
            title="Editar"
            onClick={() => openForm(category)}
          >
            <Pencil className="h-4 w-4" aria-hidden="true" />
          </Button>
          <Button
            variant="ghost"
            size="icon-sm"
            aria-label={`Eliminar ${category.name}`}
            title="Eliminar"
            onClick={() => setDeleting(category)}
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
        title="Categorías"
        icon={Tags}
        description="Ordenan el inventario, los tableros y el punto de venta. El catálogo es único para todo tu comercio."
        actions={
          <Button leftIcon={<Plus className="h-4 w-4" />} onClick={() => openForm('new')}>
            Nueva categoría
          </Button>
        }
      />

      <Card padding="none">
        <Table
          columns={columns}
          data={categoriesQuery.data}
          rowKey={(category) => category.id}
          loading={categoriesQuery.isPending}
          error={categoriesQuery.isError ? categoriesQuery.error : undefined}
          onRetry={() => void categoriesQuery.refetch()}
          empty={{
            icon: Tags,
            title: 'Todavía no creaste categorías',
            description: 'Agrupá tus productos (Bebidas, Almacén, Lácteos) para encontrarlos más rápido.',
            action: (
              <div className="flex flex-col gap-2 sm:flex-row">
                <Button leftIcon={<Plus className="h-4 w-4" />} onClick={() => openForm('new')}>
                  Crear la primera
                </Button>
                <ButtonLink to="/app/inventory" variant="outline">
                  Ver el inventario
                </ButtonLink>
              </div>
            ),
          }}
        />
      </Card>

      <Modal
        open={editing !== null}
        onClose={() => setEditing(null)}
        title={editing === 'new' ? 'Nueva categoría' : 'Editar categoría'}
        description="El nombre se muestra en el inventario y en los filtros."
        footer={
          <>
            <Button variant="outline" onClick={() => setEditing(null)}>
              Cancelar
            </Button>
            <Button
              loading={save.isPending}
              onClick={() => {
                if (!name.trim()) {
                  setNameError('Poné el nombre de la categoría.');
                  return;
                }
                save.mutate(name.trim());
              }}
            >
              {editing === 'new' ? 'Crear categoría' : 'Guardar cambios'}
            </Button>
          </>
        }
      >
        <Field label="Nombre" error={nameError}>
          <Input
            data-autofocus
            value={name}
            onChange={(event) => {
              setName(event.target.value);
              setNameError(undefined);
            }}
            placeholder="Conservas"
            invalid={!!nameError}
          />
        </Field>
      </Modal>

      <ConfirmDialog
        open={deleting !== null}
        onClose={() => setDeleting(null)}
        onConfirm={() => {
          if (deleting) remove.mutate(deleting);
        }}
        title={`¿Eliminar ${deleting?.name ?? 'la categoría'}?`}
        description={
          deleting && deleting.productCount > 0
            ? `Tiene ${formatNumber(deleting.productCount)} productos: movelos a otra categoría antes de eliminarla.`
            : 'Los productos sin categoría siguen funcionando igual.'
        }
        confirmLabel="Eliminar categoría"
        tone="danger"
        loading={remove.isPending}
        confirmDisabled={!!deleting && deleting.productCount > 0}
      />
    </>
  );
}
