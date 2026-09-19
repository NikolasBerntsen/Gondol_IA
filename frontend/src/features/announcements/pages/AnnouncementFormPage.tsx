import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Info, Megaphone, Plus, Search, ShieldAlert, Store, Trash2 } from 'lucide-react';
import {
  BUSINESS_TYPES,
  BUSINESS_TYPE_LABELS,
  SEVERITY_LABELS,
  type AnnouncementKind,
  type BusinessType,
  type Severity,
} from '@/api/types';
import { getErrorMessage, getFieldErrors } from '@/api/client';
import {
  Alert,
  Badge,
  Button,
  Card,
  CardHeader,
  Checkbox,
  ConfirmDialog,
  Field,
  Input,
  PageHeader,
  Segmented,
  Select,
  Textarea,
  Toggle,
} from '@/components/ui';
import { BarcodeDigits } from '@/components/gondola';
import { cn } from '@/lib/cn';
import { formatNumber } from '@/lib/format';
import { announcementKeys, ownerAnnouncementsApi } from '../api';
import type { CreateAnnouncementBody, RecallPreview } from '../types';

const SEVERITIES: Severity[] = ['INFO', 'WARNING', 'CRITICAL'];

/**
 * Alta de un aviso general o de un recall (SPEC §6.7). El recall exige producto, código de barras válido y lotes
 * (o "todos los lotes"); antes de publicar se muestra la vista previa con cuántos clientes quedarían alcanzados y
 * se pide una confirmación explícita, porque publicar pone mercadería en cuarentena en el acto.
 */
export default function AnnouncementFormPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const [kind, setKind] = useState<AnnouncementKind>('GENERAL');
  const [severity, setSeverity] = useState<Severity>('INFO');
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const [businessTypes, setBusinessTypes] = useState<BusinessType[]>([]);

  const [productName, setProductName] = useState('');
  const [brand, setBrand] = useState('');
  const [barcode, setBarcode] = useState('');
  const [allLots, setAllLots] = useState(false);
  const [lots, setLots] = useState<string[]>(['']);
  const [expiryFrom, setExpiryFrom] = useState('');
  const [expiryTo, setExpiryTo] = useState('');
  const [reason, setReason] = useState('');
  const [instructions, setInstructions] = useState('');

  const [errors, setErrors] = useState<Record<string, string>>({});
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [preview, setPreview] = useState<RecallPreview | null>(null);

  const isRecall = kind === 'RECALL';
  const cleanLots = useMemo(() => lots.map((lot) => lot.trim()).filter(Boolean), [lots]);
  const barcodeClean = barcode.replace(/\s+/g, '');
  const barcodeValid = isValidBarcode(barcodeClean);

  // Un recall siempre es crítico; un aviso general arranca informativo.
  useEffect(() => {
    setSeverity(isRecall ? 'CRITICAL' : 'INFO');
    setPreview(null);
  }, [isRecall]);

  useEffect(() => setPreview(null), [barcodeClean, allLots, expiryFrom, expiryTo, lots]);

  const previewMutation = useMutation({
    mutationFn: () =>
      ownerAnnouncementsApi.preview({
        barcode: barcodeClean,
        lotNumbers: cleanLots,
        allLots,
        expiryFrom: expiryFrom || null,
        expiryTo: expiryTo || null,
      }),
    onSuccess: setPreview,
  });

  const create = useMutation({
    mutationFn: (payload: CreateAnnouncementBody) => ownerAnnouncementsApi.create(payload),
    onSuccess: (created) => {
      toast.success(created.kind === 'RECALL' ? 'Recall publicado.' : 'Aviso publicado.');
      void queryClient.invalidateQueries({ queryKey: announcementKeys.owner });
      navigate('/owner/announcements');
    },
    onError: (error) => {
      setConfirmOpen(false);
      setErrors(getFieldErrors(error));
      toast.error(getErrorMessage(error));
    },
  });

  const validate = (): boolean => {
    const found: Record<string, string> = {};
    if (!title.trim()) found.title = 'Escribí un título.';
    if (!body.trim()) found.body = 'Contá de qué se trata el aviso.';
    if (isRecall) {
      if (!productName.trim()) found.productName = 'Indicá el producto que se retira.';
      if (!barcodeClean) found.barcode = 'Cargá el código de barras del producto.';
      else if (!barcodeValid) found.barcode = 'El código no es válido: revisá los dígitos del EAN.';
      if (!allLots && cleanLots.length === 0) {
        found.lots = 'Cargá al menos un lote o marcá "Todos los lotes".';
      }
      if (expiryFrom && expiryTo && expiryFrom > expiryTo) {
        found.expiry = 'El rango está invertido: "desde" tiene que ser anterior a "hasta".';
      }
      if (!reason.trim()) found.reason = 'Explicá por qué se retira el producto.';
      if (!instructions.trim()) found.instructions = 'Decile al comercio qué tiene que hacer.';
    }
    setErrors(found);
    return Object.keys(found).length === 0;
  };

  const submit = () => {
    if (!validate()) {
      toast.error('Revisá los datos marcados.');
      return;
    }
    setConfirmOpen(true);
  };

  const publish = () =>
    create.mutateAsync({
      kind,
      severity,
      title: title.trim(),
      body: body.trim(),
      targetBusinessTypes: isRecall || businessTypes.length === 0 ? null : businessTypes,
      recall: isRecall
        ? {
            productName: productName.trim(),
            brand: brand.trim() || null,
            barcode: barcodeClean,
            lotNumbers: allLots ? [] : cleanLots,
            allLots,
            expiryFrom: expiryFrom || null,
            expiryTo: expiryTo || null,
            reason: reason.trim(),
            instructions: instructions.trim(),
          }
        : null,
    });

  const canPreview = isRecall && barcodeValid && (allLots || cleanLots.length > 0);

  return (
    <>
      <PageHeader
        title="Publicar un aviso"
        icon={Megaphone}
        eyebrow="Avisos y recalls"
        back={{ to: '/owner/announcements', label: 'Volver a avisos' }}
        description="Se publica en el acto para todos los clientes activos."
      />

      <form
        className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_340px]"
        onSubmit={(event) => {
          event.preventDefault();
          submit();
        }}
      >
        <div className="space-y-4">
          <Card padding="md">
            <CardHeader title="Tipo de aviso" description="Un recall pone en cuarentena la mercadería alcanzada." />
            <Segmented<AnnouncementKind>
              label="Tipo de aviso"
              value={kind}
              onChange={setKind}
              options={[
                { value: 'GENERAL', label: 'Aviso general', icon: <Info aria-hidden="true" /> },
                { value: 'RECALL', label: 'Recall', icon: <ShieldAlert aria-hidden="true" />, tone: 'crit' },
              ]}
            />

            <div className="mt-4 space-y-4">
              <Field label="Título" error={errors.title} htmlFor="ann-title">
                <Input
                  id="ann-title"
                  value={title}
                  maxLength={200}
                  invalid={!!errors.title}
                  onChange={(event) => setTitle(event.target.value)}
                  placeholder={
                    isRecall ? 'Retiro: Sopa de tomate La Huerta 340 g' : 'Nueva función: importación de planillas'
                  }
                />
              </Field>
              <Field
                label="Cuerpo del aviso"
                error={errors.body}
                htmlFor="ann-body"
                hint="Lo primero que lee el comercio en la bandeja de Avisos."
              >
                <Textarea
                  id="ann-body"
                  rows={4}
                  value={body}
                  maxLength={4000}
                  invalid={!!errors.body}
                  onChange={(event) => setBody(event.target.value)}
                  placeholder={
                    isRecall
                      ? 'Retiro voluntario del lote indicado por el fabricante.'
                      : 'Contá la novedad en dos o tres líneas.'
                  }
                />
              </Field>
              {!isRecall ? (
                <Field label="Importancia" htmlFor="ann-severity">
                  <Select
                    id="ann-severity"
                    value={severity}
                    onChange={(event) => setSeverity(event.target.value as Severity)}
                    options={SEVERITIES.map((value) => ({ value, label: SEVERITY_LABELS[value] }))}
                  />
                </Field>
              ) : null}
            </div>
          </Card>

          {!isRecall ? (
            <Card padding="md">
              <CardHeader
                title="A qué rubros"
                description="Sin rubros elegidos el aviso le llega a todos los clientes activos."
                icon={Store}
              />
              <div className="grid gap-2 sm:grid-cols-2">
                {BUSINESS_TYPES.map((type) => (
                  <Checkbox
                    key={type}
                    checked={businessTypes.includes(type)}
                    onCheckedChange={(checked) =>
                      setBusinessTypes((current) =>
                        checked ? [...current, type] : current.filter((value) => value !== type),
                      )
                    }
                    label={BUSINESS_TYPE_LABELS[type]}
                  />
                ))}
              </div>
            </Card>
          ) : (
            <Card padding="md">
              <CardHeader
                title="Producto retirado"
                description="Con estos datos bloqueamos los lotes en el stock de los comercios."
                icon={ShieldAlert}
              />
              <div className="space-y-4">
                <div className="grid gap-4 md:grid-cols-2">
                  <Field label="Producto" error={errors.productName} htmlFor="recall-product">
                    <Input
                      id="recall-product"
                      value={productName}
                      maxLength={200}
                      invalid={!!errors.productName}
                      onChange={(event) => setProductName(event.target.value)}
                      placeholder="Sopa de tomate en lata 340 g"
                    />
                  </Field>
                  <Field label="Marca" optional htmlFor="recall-brand">
                    <Input
                      id="recall-brand"
                      value={brand}
                      maxLength={100}
                      onChange={(event) => setBrand(event.target.value)}
                      placeholder="La Huerta"
                    />
                  </Field>
                </div>

                <Field
                  label="Código de barras"
                  error={errors.barcode}
                  htmlFor="recall-barcode"
                  hint="EAN-13, EAN-8 o UPC-A. Validamos el dígito verificador."
                >
                  <Input
                    id="recall-barcode"
                    value={barcode}
                    maxLength={32}
                    inputMode="numeric"
                    invalid={!!errors.barcode}
                    onChange={(event) => setBarcode(event.target.value)}
                    placeholder="7791234500017"
                  />
                </Field>
                {barcodeClean && !errors.barcode ? (
                  <div className="flex items-center gap-3">
                    {barcodeValid ? (
                      <>
                        <BarcodeDigits code={barcodeClean} width={140} />
                        <Badge tone="ok" size="sm">
                          Código válido
                        </Badge>
                      </>
                    ) : (
                      <Badge tone="crit" size="sm">
                        Dígito verificador incorrecto
                      </Badge>
                    )}
                  </div>
                ) : null}

                <div className="rounded-panel border border-border p-3">
                  <Toggle
                    checked={allLots}
                    onChange={setAllLots}
                    label="Todos los lotes"
                    description="Se retira el producto entero, sin importar el número de lote."
                  />
                  {!allLots ? (
                    <div className="mt-3 space-y-2">
                      <span className="gd-eyebrow">Lotes alcanzados</span>
                      {lots.map((lot, index) => (
                        <div key={index} className="flex gap-2">
                          <Input
                            value={lot}
                            maxLength={60}
                            aria-label={`Lote ${index + 1}`}
                            className="font-mono"
                            invalid={!!errors.lots && cleanLots.length === 0}
                            onChange={(event) =>
                              setLots((current) =>
                                current.map((value, position) => (position === index ? event.target.value : value)),
                              )
                            }
                            placeholder="L2409A"
                          />
                          <Button
                            type="button"
                            variant="ghost"
                            size="icon"
                            aria-label={`Quitar el lote ${index + 1}`}
                            disabled={lots.length === 1}
                            onClick={() => setLots((current) => current.filter((_, position) => position !== index))}
                          >
                            <Trash2 className="h-4 w-4" aria-hidden="true" />
                          </Button>
                        </div>
                      ))}
                      <Button
                        type="button"
                        variant="ghost"
                        size="sm"
                        leftIcon={<Plus aria-hidden="true" />}
                        onClick={() => setLots((current) => [...current, ''])}
                      >
                        Agregar otro lote
                      </Button>
                      {errors.lots ? (
                        <p role="alert" className="text-sm text-crit-ink">
                          {errors.lots}
                        </p>
                      ) : null}
                    </div>
                  ) : null}
                </div>

                <fieldset className="rounded-panel border border-border p-3">
                  <legend className="gd-eyebrow px-1">Rango de vencimiento (opcional)</legend>
                  <p className="mb-2 text-sm text-muted-foreground">
                    Si lo dejás vacío, el recall alcanza cualquier vencimiento.
                  </p>
                  <div className="grid gap-3 sm:grid-cols-2">
                    <Field label="Desde" optional htmlFor="recall-from">
                      <Input
                        id="recall-from"
                        type="date"
                        value={expiryFrom}
                        invalid={!!errors.expiry}
                        onChange={(event) => setExpiryFrom(event.target.value)}
                      />
                    </Field>
                    <Field label="Hasta" optional htmlFor="recall-to">
                      <Input
                        id="recall-to"
                        type="date"
                        value={expiryTo}
                        invalid={!!errors.expiry}
                        onChange={(event) => setExpiryTo(event.target.value)}
                      />
                    </Field>
                  </div>
                  {errors.expiry ? (
                    <p role="alert" className="mt-2 text-sm text-crit-ink">
                      {errors.expiry}
                    </p>
                  ) : null}
                </fieldset>

                <Field label="Motivo del retiro" error={errors.reason} htmlFor="recall-reason">
                  <Textarea
                    id="recall-reason"
                    rows={2}
                    value={reason}
                    maxLength={2000}
                    invalid={!!errors.reason}
                    onChange={(event) => setReason(event.target.value)}
                    placeholder="Posible presencia de cuerpos extraños detectada por el fabricante."
                  />
                </Field>
                <Field
                  label="Qué tiene que hacer el comercio"
                  error={errors.instructions}
                  htmlFor="recall-instructions"
                  hint="Se muestra en la alerta que bloquea la pantalla del comercio."
                >
                  <Textarea
                    id="recall-instructions"
                    rows={2}
                    value={instructions}
                    maxLength={2000}
                    invalid={!!errors.instructions}
                    onChange={(event) => setInstructions(event.target.value)}
                    placeholder="Retirá el producto de la góndola, separalo del resto y esperá el retiro del proveedor."
                  />
                </Field>
              </div>
            </Card>
          )}
        </div>

        <aside className="space-y-4 xl:sticky xl:top-4 xl:self-start">
          {isRecall ? (
            <Card padding="md">
              <CardHeader
                title="Vista previa del alcance"
                description="Cuántos clientes y lotes quedarían alcanzados ahora mismo."
              />
              <Button
                type="button"
                variant="outline"
                fullWidth
                leftIcon={<Search aria-hidden="true" />}
                disabled={!canPreview}
                loading={previewMutation.isPending}
                onClick={() => previewMutation.mutate()}
              >
                Calcular alcance
              </Button>
              {!canPreview ? (
                <p className="mt-2 text-sm text-muted-foreground">
                  Cargá un código de barras válido y al menos un lote para calcularlo.
                </p>
              ) : null}
              {previewMutation.isError ? (
                <p role="alert" className="mt-2 text-sm text-crit-ink">
                  {getErrorMessage(previewMutation.error)}
                </p>
              ) : null}
              {preview ? (
                <div className="mt-3 space-y-3">
                  <div
                    className={cn(
                      'rounded-panel border p-3',
                      preview.affectedTenantsCount > 0
                        ? 'border-crit/40 bg-crit-soft/60'
                        : 'border-border bg-muted/40',
                    )}
                  >
                    <div className="gd-eyebrow">Clientes afectados</div>
                    <div className="font-display text-2xl font-semibold tabular-nums text-foreground">
                      {formatNumber(preview.affectedTenantsCount)}
                    </div>
                    <p className="text-sm text-muted-foreground">
                      {preview.affectedTenantsCount === 1 ? '1 comercio' : `${preview.affectedTenantsCount} comercios`}{' '}
                      con stock del lote
                    </p>
                  </div>
                  <dl className="grid grid-cols-2 gap-3">
                    <div>
                      <dt className="gd-eyebrow">Lotes</dt>
                      <dd className="font-display text-md font-semibold tabular-nums">
                        {formatNumber(preview.affectedLotsCount)}
                      </dd>
                    </div>
                    <div>
                      <dt className="gd-eyebrow">Unidades</dt>
                      <dd className="font-display text-md font-semibold tabular-nums">
                        {formatNumber(preview.affectedUnits)}
                      </dd>
                    </div>
                  </dl>
                  <p className="text-xs text-muted-foreground">
                    Solo cantidades: nunca mostramos qué cliente tiene la mercadería.
                  </p>
                </div>
              ) : null}
            </Card>
          ) : (
            <Alert tone="info" title="Aviso general">
              Le llega a todos los usuarios de los clientes activos
              {businessTypes.length > 0
                ? ` de ${businessTypes.map((type) => BUSINESS_TYPE_LABELS[type]).join(', ')}`
                : ''}
              . No bloquea mercadería.
            </Alert>
          )}

          {isRecall ? (
            <Alert tone="crit" title="Publicar bloquea mercadería">
              Los lotes alcanzados pasan a cuarentena en el acto y no se pueden vender hasta que el comercio los
              retire.
            </Alert>
          ) : null}

          <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end xl:flex-col-reverse">
            <Button type="button" variant="outline" onClick={() => navigate('/owner/announcements')}>
              Cancelar
            </Button>
            <Button type="submit" variant={isRecall ? 'destructive' : 'default'} loading={create.isPending}>
              {isRecall ? 'Revisar y publicar recall' : 'Publicar aviso'}
            </Button>
          </div>
        </aside>
      </form>

      <ConfirmDialog
        open={confirmOpen}
        onClose={() => setConfirmOpen(false)}
        onConfirm={publish}
        title={isRecall ? 'Publicar el recall' : 'Publicar el aviso'}
        confirmLabel={isRecall ? 'Publicar recall' : 'Publicar aviso'}
        tone={isRecall ? 'danger' : 'primary'}
        loading={create.isPending}
        description={
          isRecall
            ? preview
              ? `"${title.trim()}" se publica ahora: ${formatNumber(preview.affectedTenantsCount)} ${preview.affectedTenantsCount === 1 ? 'cliente afectado' : 'clientes afectados'}, ${formatNumber(preview.affectedLotsCount)} ${preview.affectedLotsCount === 1 ? 'lote' : 'lotes'} y ${formatNumber(preview.affectedUnits)} u. pasan a cuarentena. No se puede deshacer.`
              : `"${title.trim()}" se publica ahora y pone en cuarentena todos los lotes que coincidan. No calculaste el alcance: podés cancelar y calcularlo antes.`
            : `"${title.trim()}" le va a llegar ahora a todos los usuarios de los clientes activos${businessTypes.length > 0 ? ` de ${businessTypes.map((type) => BUSINESS_TYPE_LABELS[type]).join(', ')}` : ''}.`
        }
      />
    </>
  );
}

/** EAN-8/UPC-A/EAN-13 con dígito verificador, o alfanumérico (Code128) de hasta 32 caracteres. */
function isValidBarcode(code: string): boolean {
  if (!code || code.length > 32 || !/^[A-Za-z0-9]+$/.test(code)) return false;
  if (!/^\d+$/.test(code)) return true;
  if (![8, 12, 13].includes(code.length)) return false;
  let sum = 0;
  for (let index = 0; index < code.length - 1; index += 1) {
    const digit = Number(code[index]);
    sum += (code.length - 1 - index) % 2 === 1 ? digit * 3 : digit;
  }
  return (10 - (sum % 10)) % 10 === Number(code[code.length - 1]);
}
