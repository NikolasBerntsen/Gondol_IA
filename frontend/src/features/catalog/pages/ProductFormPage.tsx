import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Package, PackagePlus, Plus, Sparkles, X } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { toast } from 'sonner';
import { getErrorMessage, getFieldErrors, isApiError } from '@/api/client';
import { PRODUCT_UNIT_LABELS, type ProductUnit } from '@/api/types';
import {
  Alert,
  Button,
  Card,
  CardHeader,
  ErrorState,
  Field,
  Input,
  PageHeader,
  PageSpinner,
  Select,
  Textarea,
  Toggle,
} from '@/components/ui';
import { formatMoney } from '@/lib/format';
import { catalogLookupApi, categoriesApi, productsApi, suppliersApi } from '../api';
import { BarcodeField } from '../components/BarcodeField';
import { parseDecimal } from '../lib';
import type { ProductRequest } from '../types';

interface FormState {
  barcode: string;
  name: string;
  brand: string;
  description: string;
  categoryId: string;
  newCategoryName: string;
  supplierId: string;
  unit: ProductUnit;
  costPrice: string;
  salePrice: string;
  minStock: string;
  perishable: boolean;
  active: boolean;
}

const EMPTY_FORM: FormState = {
  barcode: '',
  name: '',
  brand: '',
  description: '',
  categoryId: '',
  newCategoryName: '',
  supplierId: '',
  unit: 'UNIDAD',
  costPrice: '',
  salePrice: '',
  minStock: '0',
  perishable: true,
  active: true,
};

export default function ProductFormPage() {
  const { id } = useParams<{ id: string }>();
  const productId = id ? Number(id) : null;
  const isEdit = productId !== null;
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [searchParams] = useSearchParams();

  const [form, setForm] = useState<FormState>(() => ({
    ...EMPTY_FORM,
    barcode: searchParams.get('barcode') ?? '',
    name: searchParams.get('name') ?? '',
    brand: searchParams.get('brand') ?? '',
  }));
  const [creatingCategory, setCreatingCategory] = useState(false);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);

  const set = <K extends keyof FormState>(key: K, value: FormState[K]) => {
    setForm((current) => ({ ...current, [key]: value }));
    setFieldErrors((current) => (current[key] ? { ...current, [key]: '' } : current));
  };

  const productQuery = useQuery({
    queryKey: ['products', 'detail', productId],
    queryFn: () => productsApi.get(productId as number),
    enabled: isEdit,
  });

  const categoriesQuery = useQuery({ queryKey: ['categories'], queryFn: () => categoriesApi.list() });
  const suppliersQuery = useQuery({ queryKey: ['suppliers'], queryFn: () => suppliersApi.list() });

  // Carga los datos del producto una vez que llegan.
  const loaded = productQuery.data;
  useEffect(() => {
    if (!loaded) return;
    setForm({
      barcode: loaded.barcode ?? '',
      name: loaded.name,
      brand: loaded.brand ?? '',
      description: loaded.description ?? '',
      categoryId: loaded.categoryId ? String(loaded.categoryId) : '',
      newCategoryName: '',
      supplierId: loaded.supplierId ? String(loaded.supplierId) : '',
      unit: loaded.unit,
      costPrice: loaded.costPrice != null ? String(loaded.costPrice) : '',
      salePrice: loaded.salePrice != null ? String(loaded.salePrice) : '',
      minStock: String(loaded.minStock ?? 0),
      perishable: loaded.perishable,
      active: loaded.active,
    });
  }, [loaded]);

  const lookup = useMutation({
    mutationFn: (barcode: string) => catalogLookupApi.byBarcode(barcode),
    meta: { errorToast: false },
    onSuccess: (data) => {
      if (!data.found) {
        toast('No encontramos ese código en la base pública', {
          description: 'Completá los datos a mano: igual queda guardado en tu catálogo.',
        });
        return;
      }
      setForm((current) => ({
        ...current,
        name: current.name || data.name || '',
        brand: current.brand || data.brand || '',
        description: current.description || (data.quantity ? `Contenido: ${data.quantity}` : ''),
      }));
      toast.success('Datos traídos de la base pública', { description: 'Revisalos antes de guardar.' });
    },
    onError: () => {
      toast('No pudimos consultar la base pública', { description: 'Completá los datos a mano.' });
    },
  });

  const save = useMutation({
    mutationFn: (body: ProductRequest) =>
      isEdit ? productsApi.update(productId as number, body) : productsApi.create(body),
    onSuccess: (product) => {
      toast.success(isEdit ? 'Producto actualizado.' : 'Producto creado.');
      void queryClient.invalidateQueries({ queryKey: ['products'] });
      void queryClient.invalidateQueries({ queryKey: ['categories'] });
      navigate(`/app/products/${product.id}`);
    },
    meta: { errorToast: false },
    onError: (error) => {
      const errors = getFieldErrors(error);
      setFieldErrors(errors);
      setFormError(
        isApiError(error, 'DUPLICATE_BARCODE')
          ? 'Ya tenés otro producto con ese código de barras. Buscalo en el inventario o usá otro código.'
          : Object.keys(errors).length > 0
            ? 'Revisá los datos marcados abajo.'
            : getErrorMessage(error),
      );
    },
  });

  const categoryOptions = useMemo(
    () => (categoriesQuery.data ?? []).map((category) => ({ value: String(category.id), label: category.name })),
    [categoriesQuery.data],
  );

  const supplierOptions = useMemo(
    () =>
      (suppliersQuery.data ?? [])
        .filter((supplier) => supplier.active || String(supplier.id) === form.supplierId)
        .map((supplier) => ({ value: String(supplier.id), label: supplier.name })),
    [suppliersQuery.data, form.supplierId],
  );

  const validate = (): ProductRequest | null => {
    const errors: Record<string, string> = {};
    if (!form.name.trim()) errors.name = 'Poné el nombre del producto.';
    const cost = parseDecimal(form.costPrice);
    const sale = parseDecimal(form.salePrice);
    if (form.costPrice.trim() && cost === null) errors.costPrice = 'Usá números, por ejemplo 1.380,50.';
    if (form.salePrice.trim() && sale === null) errors.salePrice = 'Usá números, por ejemplo 2.100.';
    if (cost !== null && cost < 0) errors.costPrice = 'El costo no puede ser negativo.';
    if (sale !== null && sale < 0) errors.salePrice = 'El precio no puede ser negativo.';
    const minStock = parseDecimal(form.minStock);
    if (form.minStock.trim() && (minStock === null || minStock < 0 || !Number.isInteger(minStock))) {
      errors.minStock = 'Poné un número entero de unidades (0 o más).';
    }
    if (creatingCategory && !form.newCategoryName.trim()) {
      errors.newCategoryName = 'Poné el nombre de la categoría nueva.';
    }
    setFieldErrors(errors);
    if (Object.keys(errors).length > 0) {
      setFormError('Revisá los datos marcados abajo.');
      return null;
    }
    setFormError(null);
    return {
      barcode: form.barcode.trim() || null,
      name: form.name.trim(),
      brand: form.brand.trim() || null,
      description: form.description.trim() || null,
      categoryId: creatingCategory || !form.categoryId ? null : Number(form.categoryId),
      categoryName: creatingCategory ? form.newCategoryName.trim() : null,
      supplierId: form.supplierId ? Number(form.supplierId) : null,
      unit: form.unit,
      costPrice: cost,
      salePrice: sale,
      minStock: minStock ?? 0,
      perishable: form.perishable,
      active: form.active,
    };
  };

  if (isEdit && productQuery.isPending) return <PageSpinner />;
  if (isEdit && productQuery.isError) {
    return (
      <>
        <PageHeader title="Producto" back={{ to: '/app/inventory', label: 'Inventario' }} />
        <ErrorState error={productQuery.error} onRetry={() => void productQuery.refetch()} />
      </>
    );
  }

  const margin =
    parseDecimal(form.salePrice) !== null && parseDecimal(form.costPrice)
      ? (parseDecimal(form.salePrice) as number) - (parseDecimal(form.costPrice) as number)
      : null;

  return (
    <>
      <PageHeader
        title={isEdit ? `Editar ${productQuery.data?.name ?? 'producto'}` : 'Nuevo producto'}
        icon={isEdit ? Package : PackagePlus}
        description={
          isEdit
            ? 'El catálogo es único para todo tu comercio: los cambios se ven en todas las sucursales.'
            : 'Cargá el producto una vez y después registrá su mercadería con el escáner.'
        }
        back={{ to: isEdit ? `/app/products/${productId}` : '/app/inventory', label: isEdit ? 'Ficha' : 'Inventario' }}
      />

      <form
        className="flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault();
          const body = validate();
          if (body) save.mutate(body);
        }}
      >
        {formError && <Alert tone="crit" title="No pudimos guardar el producto">{formError}</Alert>}

        <Card padding="lg">
          <CardHeader title="Identificación" description="Con el código de barras la carga con escáner es instantánea." />
          <div className="flex flex-col gap-4">
            <div className="grid gap-4 md:grid-cols-2">
              <div className="flex flex-col gap-2">
                <BarcodeField
                  value={form.barcode}
                  onChange={(value) => set('barcode', value)}
                  error={fieldErrors.barcode}
                  optional
                  hint="EAN-13, EAN-8, UPC o Code 128."
                  onScanned={(code) => lookup.mutate(code)}
                />
                {!isEdit && (
                  <Button
                    variant="ghost"
                    size="sm"
                    className="self-start"
                    leftIcon={<Sparkles className="h-4 w-4" />}
                    loading={lookup.isPending}
                    disabled={!form.barcode.trim()}
                    onClick={() => lookup.mutate(form.barcode.trim())}
                  >
                    Completar con la base pública
                  </Button>
                )}
              </div>
              <Field label="Nombre" error={fieldErrors.name} required>
                <Input
                  value={form.name}
                  onChange={(event) => set('name', event.target.value)}
                  placeholder="Sopa de tomate en lata 340 g"
                  invalid={!!fieldErrors.name}
                  autoFocus={!isEdit}
                />
              </Field>
            </div>
            <div className="grid gap-4 md:grid-cols-2">
              <Field label="Marca" optional error={fieldErrors.brand}>
                <Input
                  value={form.brand}
                  onChange={(event) => set('brand', event.target.value)}
                  placeholder="La Huerta"
                />
              </Field>
              <Field label="Unidad de medida" error={fieldErrors.unit}>
                <Select
                  value={form.unit}
                  onChange={(event) => set('unit', event.target.value as ProductUnit)}
                  options={Object.entries(PRODUCT_UNIT_LABELS).map(([value, label]) => ({ value, label }))}
                />
              </Field>
            </div>
            <Field label="Descripción" optional error={fieldErrors.description}>
              <Textarea
                value={form.description}
                onChange={(event) => set('description', event.target.value)}
                rows={2}
                placeholder="Detalles que le sirvan a quien carga o vende el producto."
              />
            </Field>
          </div>
        </Card>

        <Card padding="lg">
          <CardHeader title="Clasificación" description="Las categorías ordenan el inventario y los tableros." />
          <div className="grid gap-4 md:grid-cols-2">
            {creatingCategory ? (
              <Field
                label="Categoría nueva"
                error={fieldErrors.newCategoryName}
                hint="Se crea junto con el producto."
                labelAction={
                  <Button
                    variant="link"
                    size="sm"
                    className="h-auto px-0"
                    leftIcon={<X className="h-3.5 w-3.5" />}
                    onClick={() => {
                      setCreatingCategory(false);
                      set('newCategoryName', '');
                    }}
                  >
                    Elegir una existente
                  </Button>
                }
              >
                <Input
                  value={form.newCategoryName}
                  onChange={(event) => set('newCategoryName', event.target.value)}
                  placeholder="Conservas"
                  invalid={!!fieldErrors.newCategoryName}
                />
              </Field>
            ) : (
              <Field
                label="Categoría"
                optional
                error={fieldErrors.categoryId}
                labelAction={
                  <Button
                    variant="link"
                    size="sm"
                    className="h-auto px-0"
                    leftIcon={<Plus className="h-3.5 w-3.5" />}
                    onClick={() => setCreatingCategory(true)}
                  >
                    Crear categoría
                  </Button>
                }
              >
                <Select
                  value={form.categoryId}
                  onChange={(event) => set('categoryId', event.target.value)}
                  placeholder="Sin categoría"
                  options={categoryOptions}
                />
              </Field>
            )}
            <Field label="Proveedor habitual" optional error={fieldErrors.supplierId}>
              <Select
                value={form.supplierId}
                onChange={(event) => set('supplierId', event.target.value)}
                placeholder="Sin proveedor"
                options={supplierOptions}
              />
            </Field>
          </div>
        </Card>

        <Card padding="lg">
          <CardHeader title="Precios y stock" description="El costo se puede ajustar en cada carga de mercadería." />
          <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
            <Field label="Costo unitario" error={fieldErrors.costPrice} optional>
              <Input
                value={form.costPrice}
                inputMode="decimal"
                onChange={(event) => set('costPrice', event.target.value)}
                className="text-right tabular-nums"
                placeholder="1.380"
                invalid={!!fieldErrors.costPrice}
              />
            </Field>
            <Field
              label="Precio de venta"
              error={fieldErrors.salePrice}
              hint={margin !== null ? `Margen: ${formatMoney(margin)}` : undefined}
              optional
            >
              <Input
                value={form.salePrice}
                inputMode="decimal"
                onChange={(event) => set('salePrice', event.target.value)}
                className="text-right tabular-nums"
                placeholder="2.100"
                invalid={!!fieldErrors.salePrice}
              />
            </Field>
            <Field
              label="Stock mínimo"
              error={fieldErrors.minStock}
              hint="Aplica a cada sucursal por separado."
            >
              <Input
                value={form.minStock}
                inputMode="numeric"
                onChange={(event) => set('minStock', event.target.value)}
                className="text-right tabular-nums"
                invalid={!!fieldErrors.minStock}
              />
            </Field>
          </div>
          <div className="mt-4 flex flex-col gap-3 border-t pt-4">
            <Toggle
              checked={form.perishable}
              onChange={(checked) => set('perishable', checked)}
              label="Tiene vencimiento"
              description="Pedimos la fecha en cada carga y avisamos antes de que venza."
            />
            {isEdit && (
              <Toggle
                checked={form.active}
                onChange={(checked) => set('active', checked)}
                label="Producto activo"
                description="Si lo desactivás deja de aparecer en el punto de venta y en la carga."
              />
            )}
          </div>
        </Card>

        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <Button
            variant="outline"
            onClick={() => navigate(isEdit ? `/app/products/${productId}` : '/app/inventory')}
          >
            Cancelar
          </Button>
          <Button type="submit" loading={save.isPending}>
            {isEdit ? 'Guardar cambios' : 'Crear producto'}
          </Button>
        </div>
      </form>
    </>
  );
}
