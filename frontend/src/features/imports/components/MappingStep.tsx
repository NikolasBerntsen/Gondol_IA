import { useMemo } from 'react';
import { Check, CircleSlash, Store, Wand2 } from 'lucide-react';
import { Badge, Card, CardHeader, Field, Select, Toggle } from '@/components/ui';
import { useBranch } from '@/branches/BranchContext';
import { cn } from '@/lib/cn';
import type { ImportFieldDto, ImportJob, ImportOptions } from '../types';

const NONE = '__none__';

export interface MappingDraft {
  columnMapping: Record<string, string>;
  options: Omit<ImportOptions, 'defaultBranchName'>;
}

export interface MappingStepProps {
  job: ImportJob;
  fields: ImportFieldDto[];
  draft: MappingDraft;
  onChange: (draft: MappingDraft) => void;
  error?: string | null;
}

/** Paso 2 «Columnas»: por cada campo un select de encabezados con sugerencia y valores de muestra (SPEC §16.4). */
export function MappingStep({ job, fields, draft, onChange, error }: MappingStepProps) {
  const { branches } = useBranch();
  const usedHeaders = useMemo(() => new Set(Object.values(draft.columnMapping)), [draft.columnMapping]);

  const setField = (fieldKey: string, header: string) => {
    const columnMapping = { ...draft.columnMapping };
    // Un encabezado no puede estar en dos campos a la vez.
    for (const [key, value] of Object.entries(columnMapping)) {
      if (value === header && key !== fieldKey) delete columnMapping[key];
    }
    if (header === NONE) delete columnMapping[fieldKey];
    else columnMapping[fieldKey] = header;
    onChange({ ...draft, columnMapping });
  };

  const setOption = <K extends keyof MappingDraft['options']>(key: K, value: MappingDraft['options'][K]) =>
    onChange({ ...draft, options: { ...draft.options, [key]: value } });

  const sampleValues = (header: string | undefined) => {
    if (!header) return [];
    return job.sampleRows
      .map((row) => row[header])
      .filter((value): value is string => Boolean(value && value.trim()))
      .slice(0, 3);
  };

  const renderField = (field: ImportFieldDto) => {
    const header = draft.columnMapping[field.key];
    const suggested = job.suggestedMapping[field.key];
    const samples = sampleValues(header);
    return (
      <div key={field.key} className="border-t border-border px-4 py-3 first:border-t-0 sm:px-5">
        <div className="flex flex-col gap-2 sm:flex-row sm:items-start sm:gap-4">
          <div className="sm:w-[240px] sm:shrink-0">
            <p className="flex items-center gap-1.5 font-medium text-foreground">
              {field.label}
              {field.required ? (
                <Badge tone="primary" size="sm">
                  obligatorio
                </Badge>
              ) : null}
            </p>
            <p className="mt-0.5 text-sm text-muted-foreground">{field.description}</p>
          </div>
          <div className="min-w-0 flex-1">
            <Select
              aria-label={`Columna del archivo para ${field.label}`}
              value={header ?? NONE}
              invalid={field.required && !header}
              onChange={(event) => setField(field.key, event.target.value)}
              options={[
                { value: NONE, label: 'No importar este campo' },
                ...job.headers.map((h) => ({
                  value: h,
                  label: h === suggested ? `${h}  (sugerida)` : h,
                  disabled: usedHeaders.has(h) && h !== header,
                })),
              ]}
            />
            <div className="mt-1.5 flex min-h-5 flex-wrap items-center gap-1.5 text-sm">
              {header ? (
                samples.length ? (
                  <>
                    <span className="text-muted-foreground">Ejemplos:</span>
                    {samples.map((value, index) => (
                      <span key={index} className="rounded-tag bg-muted px-1.5 py-0.5 font-mono text-xs text-foreground">
                        {value.length > 28 ? `${value.slice(0, 28)}…` : value}
                      </span>
                    ))}
                  </>
                ) : (
                  <span className="text-muted-foreground">La columna está vacía en las primeras filas.</span>
                )
              ) : (
                <span className="flex items-center gap-1 text-muted-foreground">
                  <CircleSlash className="h-3.5 w-3.5" aria-hidden="true" />
                  Sin columna asignada
                </span>
              )}
              {header && header === suggested ? (
                <span className="ml-auto flex items-center gap-1 text-ok-ink">
                  <Check className="h-3.5 w-3.5" aria-hidden="true" />
                  Detectada automáticamente
                </span>
              ) : null}
            </div>
          </div>
        </div>
      </div>
    );
  };

  const productFields = fields.filter((f) => f.group === 'producto');
  const stockFields = fields.filter((f) => f.group === 'stock');

  return (
    <div className="flex flex-col gap-4">
      {error ? (
        <p role="alert" className="rounded-control border border-crit/35 bg-crit-soft px-3 py-2 text-base text-crit-ink">
          {error}
        </p>
      ) : null}

      <Card padding="none">
        <div className="px-4 py-3 sm:px-5">
          <CardHeader
            title="Datos del producto"
            description="Reconocimos las columnas por su nombre. Revisá las que no coincidan y elegí la correcta."
            icon={Wand2}
          />
        </div>
        <div className="border-t border-border">{productFields.map(renderField)}</div>
      </Card>

      <Card padding="none">
        <div className="px-4 py-3 sm:px-5">
          <CardHeader
            title="Stock inicial (opcional)"
            description="Si tu planilla trae cantidades, cada fila carga un lote. Repetí el código en varias filas para cargar varios lotes del mismo producto."
          />
        </div>
        <div className={cn('border-t border-border', !draft.options.importStock && 'opacity-55')}>
          {stockFields.map(renderField)}
        </div>
      </Card>

      <Card padding="lg">
        <CardHeader title="Opciones" description="Cómo tratamos lo que ya existe en tu catálogo." />
        <div className="mt-3 divide-y divide-border">
          <Toggle
            checked={draft.options.importStock}
            onChange={(checked) => setOption('importStock', checked)}
            label="Cargar el stock de la planilla"
            description="Crea un lote por fila con cantidad. Desactivalo para importar solo el catálogo."
          />
          <Toggle
            checked={draft.options.updateExisting}
            onChange={(checked) => setOption('updateExisting', checked)}
            label="Actualizar los productos que ya existen"
            description="Se comparan por código de barras y, si no hay código, por nombre exacto."
          />
          <Toggle
            checked={draft.options.createCategories}
            onChange={(checked) => setOption('createCategories', checked)}
            label="Crear las categorías nuevas"
            description="Si está apagado, una categoría desconocida marca la fila como error."
          />
          <Toggle
            checked={draft.options.createSuppliers}
            onChange={(checked) => setOption('createSuppliers', checked)}
            label="Crear los proveedores nuevos"
            description="Si está apagado, un proveedor desconocido marca la fila como error."
          />
        </div>
        <div className="mt-4 grid gap-4 sm:grid-cols-2">
          <Field
            label="Formato de fecha del archivo"
            hint="Con qué orden están escritas las fechas de vencimiento e ingreso."
          >
            <Select
              value={draft.options.dateFormat}
              onChange={(event) =>
                setOption('dateFormat', event.target.value as MappingDraft['options']['dateFormat'])
              }
              options={[
                { value: 'DMY', label: 'Día/Mes/Año — 25/12/2026' },
                { value: 'MDY', label: 'Mes/Día/Año — 12/25/2026' },
                { value: 'YMD', label: 'Año/Mes/Día — 2026-12-25' },
              ]}
            />
          </Field>
          <Field
            label="Sucursal por defecto"
            hint="Se usa en las filas que no traen la columna «Sucursal»."
            optional={!draft.options.importStock}
          >
            <Select
              value={draft.options.defaultBranchId ?? ''}
              placeholder="Sin sucursal por defecto"
              leftIcon={<Store className="h-4 w-4" aria-hidden="true" />}
              options={branches.map((branch) => ({
                value: branch.id,
                label: branch.code ? `${branch.name} (${branch.code})` : branch.name,
              }))}
              onChange={(event) =>
                setOption('defaultBranchId', event.target.value ? Number(event.target.value) : null)
              }
            />
          </Field>
        </div>
        {draft.options.importStock && draft.options.defaultBranchId === null && !draft.columnMapping.branch ? (
          <p className="mt-2 text-base text-warn-ink">
            Elegí una sucursal por defecto o asigná la columna «Sucursal»: si no, las filas con cantidad van a quedar
            con error.
          </p>
        ) : null}
        {branches.length === 0 ? (
          <p className="mt-2 text-base text-crit-ink">No tenés ninguna sucursal activa asignada.</p>
        ) : null}
      </Card>
    </div>
  );
}
