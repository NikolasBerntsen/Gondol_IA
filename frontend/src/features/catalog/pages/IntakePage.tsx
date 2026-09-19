import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  AlertTriangle,
  Camera,
  CheckCircle2,
  Keyboard,
  PackagePlus,
  PackageSearch,
  RotateCcw,
  ScanBarcode,
} from 'lucide-react';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { toast } from 'sonner';
import { getErrorMessage, isApiError } from '@/api/client';
import { useCurrentUser } from '@/auth/AuthContext';
import { useBranch, useWriteBranch } from '@/branches/BranchContext';
import { BranchPicker } from '@/branches/BranchPicker';
import { BarcodeDigits } from '@/components/gondola';
import { BarcodeScanner, CameraCapture, useBarcodeWedge } from '@/components/scanner';
import {
  Alert,
  Button,
  ButtonLink,
  Card,
  Field,
  Input,
  Modal,
  PageHeader,
  QtyStepper,
  SecureContextWarning,
  Select,
  Skeleton,
} from '@/components/ui';
import { isCameraSupported } from '@/lib/secureContext';
import { formatDate, formatMoney, formatNumber } from '@/lib/format';
import { catalogLookupApi, lotsApi, ocrApi, productsApi, suppliersApi } from '../api';
import { LotRotationList } from '../components/LotRotationList';
import { OcrChoice } from '../components/OcrChoice';
import { RecallPanel } from '../components/RecallPanel';
import { normalizeBarcode, normalizeLotNumber, parseDateInput, parseDecimal, unitShort } from '../lib';
import type { BarcodeLookupResponse, LotDto, OcrLabelResponse, ProductDetail, ReceiveLotResponse } from '../types';

interface TodayIntake {
  lotId: number;
  productName: string;
  quantity: number;
  unitLabel: string;
  lotNumber: string | null;
  expiryDate: string | null;
  branchName: string;
  quarantined: boolean;
}

export default function IntakePage() {
  const me = useCurrentUser();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [searchParams, setSearchParams] = useSearchParams();
  const { isAll } = useBranch();
  const writeBranch = useWriteBranch();
  const rotation = me.tenant?.stockRotation ?? 'FIFO';

  const [code, setCode] = useState('');
  const [manualCode, setManualCode] = useState('');
  const [product, setProduct] = useState<ProductDetail | null>(null);
  const [unknown, setUnknown] = useState<BarcodeLookupResponse | null>(null);
  const [ocr, setOcr] = useState<OcrLabelResponse | null>(null);
  const [cameraOpen, setCameraOpen] = useState(false);
  const [expiryText, setExpiryText] = useState('');
  const [lotNumber, setLotNumber] = useState('');
  const [quantity, setQuantity] = useState(1);
  const [cost, setCost] = useState('');
  const [supplierId, setSupplierId] = useState('');
  const [source, setSource] = useState<'MANUAL' | 'SCAN' | 'OCR'>('MANUAL');
  const [branchError, setBranchError] = useState<string>();
  const [result, setResult] = useState<ReceiveLotResponse | null>(null);
  const [today, setToday] = useState<TodayIntake[]>([]);

  const suppliersQuery = useQuery({ queryKey: ['suppliers'], queryFn: () => suppliersApi.list(false) });

  const resetForm = useCallback((loaded: ProductDetail | null) => {
    setExpiryText('');
    setLotNumber('');
    setQuantity(1);
    setOcr(null);
    setBranchError(undefined);
    setCost(loaded?.costPrice != null && loaded.costPrice > 0 ? String(loaded.costPrice) : '');
    setSupplierId(loaded?.supplierId ? String(loaded.supplierId) : '');
  }, []);

  const applyProduct = useCallback(
    (loaded: ProductDetail, detectedSource: 'MANUAL' | 'SCAN' | 'OCR') => {
      setProduct(loaded);
      setUnknown(null);
      setResult(null);
      setCode(loaded.barcode ?? '');
      setSource(detectedSource);
      resetForm(loaded);
    },
    [resetForm],
  );

  const findByBarcode = useMutation({
    mutationFn: async (barcode: string) => {
      try {
        return { product: await productsApi.getByBarcode(barcode), lookup: null as BarcodeLookupResponse | null };
      } catch (error) {
        if (isApiError(error) && error.status === 404) {
          const lookup = await catalogLookupApi.byBarcode(barcode).catch(() => null);
          return { product: null as ProductDetail | null, lookup: lookup ?? { found: false, source: null, barcode, name: null, brand: null, quantity: null, categoryHint: null, imageUrl: null } };
        }
        throw error;
      }
    },
    meta: { errorToast: false },
    onSuccess: (data, barcode) => {
      if (data.product) {
        applyProduct(data.product, 'SCAN');
        return;
      }
      setProduct(null);
      setResult(null);
      setCode(barcode);
      setUnknown(data.lookup);
    },
    onError: (error) => toast.error(getErrorMessage(error, 'No pudimos buscar ese código. Probá de nuevo.')),
  });

  const findById = useMutation({
    mutationFn: (productId: number) => productsApi.get(productId),
    meta: { errorToast: false },
    onSuccess: (loaded) => applyProduct(loaded, 'MANUAL'),
    onError: (error) => toast.error(getErrorMessage(error, 'No pudimos abrir ese producto.')),
  });

  // Llegada desde el inventario o la ficha: /app/intake?productId=12
  const presetProductId = searchParams.get('productId');
  useEffect(() => {
    if (!presetProductId) return;
    const id = Number(presetProductId);
    const next = new URLSearchParams(searchParams);
    next.delete('productId');
    next.delete('barcode');
    setSearchParams(next, { replace: true });
    if (Number.isFinite(id)) findById.mutate(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [presetProductId]);

  const handleCode = useCallback(
    (raw: string) => {
      const barcode = normalizeBarcode(raw);
      if (!barcode) return;
      setManualCode('');
      findByBarcode.mutate(barcode);
    },
    [findByBarcode],
  );

  // Lector USB de caja: captura ráfagas terminadas en Enter mientras no hay un producto cargado.
  useBarcodeWedge({ onScan: handleCode, enabled: !product });

  const readLabel = useMutation({
    mutationFn: (photo: Blob) => ocrApi.label(photo),
    meta: { errorToast: false },
    onSuccess: (data) => {
      setOcr(data);
      setCameraOpen(false);
      const bestExpiry = data.expiryDates[0];
      const bestLot = data.lotNumbers[0];
      if (bestExpiry) setExpiryText(formatDate(bestExpiry.value));
      if (bestLot) setLotNumber(normalizeLotNumber(bestLot.value));
      setSource('OCR');
      if (!bestExpiry && !bestLot) {
        toast('No pudimos leer la etiqueta', { description: 'Cargá el vencimiento y el lote a mano.' });
      }
      if (!product && data.matchedProduct) {
        handleCode(data.matchedProduct.barcode ?? '');
      }
    },
    onError: (error) => {
      setCameraOpen(false);
      toast.error(
        isApiError(error, 'AI_UNAVAILABLE')
          ? 'La lectura con cámara no está disponible en este momento. Cargá el vencimiento y el lote a mano.'
          : getErrorMessage(error, 'No pudimos leer la etiqueta. Cargá los datos a mano.'),
      );
    },
  });

  const expiryIso = parseDateInput(expiryText);
  const expiryError = expiryText.trim() && !expiryIso ? `Fecha inválida: ${expiryText}. Usá dd/mm/aaaa.` : undefined;

  // Chequeo previo de recall: avisa antes de cargar (SPEC §6.3).
  const recallQuery = useQuery({
    queryKey: ['recalls', 'check', code, lotNumber, expiryIso ?? ''],
    queryFn: () =>
      catalogLookupApi.checkRecall({
        barcode: code,
        lotNumber: lotNumber || undefined,
        expiryDate: expiryIso ?? undefined,
      }),
    enabled: !!code && !!product,
    retry: false,
    meta: { errorToast: false },
  });
  const blockedByRecall = !!recallQuery.data?.recalled;

  // Lotes que ya están en la sucursal elegida, en orden de salida.
  const branchLots: LotDto[] = useMemo(() => {
    if (!product) return [];
    return product.lots
      .filter((lot) => lot.rotationRank != null && (!writeBranch.branchId || lot.branchId === writeBranch.branchId))
      .sort((a, b) => (a.rotationRank ?? 0) - (b.rotationRank ?? 0));
  }, [product, writeBranch.branchId]);

  // Con FIFO avisamos si el lote nuevo vence antes que mercadería que entró antes (SPEC §4.2).
  const breaksRotation =
    rotation === 'FIFO' && !!expiryIso && branchLots.some((lot) => !!lot.expiryDate && lot.expiryDate > expiryIso);

  const receive = useMutation({
    mutationFn: () =>
      lotsApi.receive({
        branchId: writeBranch.branchId,
        productId: product?.id as number,
        lotNumber: lotNumber || null,
        expiryDate: expiryIso,
        quantity,
        costPrice: parseDecimal(cost),
        supplierId: supplierId ? Number(supplierId) : null,
        source,
      }),
    meta: { errorToast: false },
    onSuccess: (response) => {
      setResult(response);
      setToday((current) => [
        {
          lotId: response.lot.id,
          productName: product?.name ?? response.lot.productName ?? 'Producto',
          quantity: response.lot.quantity,
          unitLabel: unitShort(product?.unit),
          lotNumber: response.lot.lotNumber,
          expiryDate: response.lot.expiryDate,
          branchName: response.lot.branchName,
          quarantined: response.quarantined,
        },
        ...current,
      ]);
      toast.success('Ingreso registrado.', {
        description: `${formatNumber(response.lot.quantity)} ${unitShort(product?.unit)} de ${
          product?.name ?? ''
        } · ${response.lot.lotNumber ?? 'sin lote'} · ${response.lot.branchName}`,
      });
      void queryClient.invalidateQueries({ queryKey: ['products'] });
      void queryClient.invalidateQueries({ queryKey: ['lots'] });
      void queryClient.invalidateQueries({ queryKey: ['expirations'] });
      void queryClient.invalidateQueries({ queryKey: ['dashboard'] });
      resetForm(product);
    },
    onError: (error) => {
      if (isApiError(error, 'BRANCH_REQUIRED')) {
        setBranchError(getErrorMessage(error));
        return;
      }
      toast.error(getErrorMessage(error, 'No pudimos registrar el ingreso.'));
    },
  });

  const startOver = () => {
    setProduct(null);
    setUnknown(null);
    setResult(null);
    setCode('');
    setManualCode('');
    resetForm(null);
  };

  const canSave =
    !!product && !!writeBranch.isReady && quantity > 0 && !expiryError && !blockedByRecall && !receive.isPending;
  const needsExpiry = !!product?.perishable && !expiryIso;

  return (
    <div className="flex flex-col gap-4 lg:flex-row lg:items-start lg:gap-6">
      <div className="flex min-w-0 flex-1 flex-col gap-4 lg:max-w-[560px]">
        <PageHeader
          title="Carga de mercadería"
          icon={ScanBarcode}
          description={`${me.fullName} · ${writeBranch.branchId ? '' : 'Elegí la sucursal · '}Cada ingreso crea su propio lote.`}
          actions={
            product || unknown ? (
              <Button variant="outline" size="sm" leftIcon={<RotateCcw className="h-4 w-4" />} onClick={startOver}>
                Escanear otro
              </Button>
            ) : undefined
          }
        />

        <SecureContextWarning />

        {isAll && writeBranch.needsPicker && (
          <BranchPicker
            value={writeBranch.branchId}
            onChange={(value) => {
              writeBranch.setBranchId(value);
              setBranchError(undefined);
            }}
            label="Sucursal donde entra la mercadería"
            error={branchError}
          />
        )}

        {result && (
          <div className="flex flex-col gap-3">
            <Alert tone="ok" icon={CheckCircle2} title="Ingreso registrado">
              Se creó el lote {result.lot.lotNumber ?? 'sin número'} con {formatNumber(result.lot.quantity)}{' '}
              {unitShort(product?.unit)} en {result.lot.branchName}.
            </Alert>
            {result.rotationWarning && (
              <Alert tone="warn" icon={AlertTriangle} title="Revisá el orden de salida">
                {result.rotationWarning}
              </Alert>
            )}
            {result.quarantined && <RecallPanel recalls={result.recalls} quarantined />}
          </div>
        )}

        {!product && !unknown && (
          <div className="flex flex-col gap-3">
            {isCameraSupported() ? (
              <BarcodeScanner
                onDetected={handleCode}
                active={!findByBarcode.isPending}
                hint="Apuntá al código de barras del producto."
                fallback={<p className="text-base text-muted-foreground">Ingresá el código a mano acá abajo.</p>}
              />
            ) : (
              <Card padding="lg">
                <p className="text-base text-muted-foreground">
                  En esta pantalla no hay cámara disponible. Ingresá el código a mano o usá el lector USB: escaneá y el
                  código se carga solo.
                </p>
              </Card>
            )}

            <form
              className="flex items-end gap-2"
              onSubmit={(event) => {
                event.preventDefault();
                handleCode(manualCode);
              }}
            >
              <Field label="Código de barras" className="flex-1" htmlFor="intake-manual-code">
                <Input
                  id="intake-manual-code"
                  value={manualCode}
                  inputMode="numeric"
                  autoComplete="off"
                  placeholder="7791234500017"
                  className="h-11 font-mono tabular-nums"
                  leftIcon={<Keyboard className="h-4 w-4" aria-hidden="true" />}
                  onChange={(event) => setManualCode(normalizeBarcode(event.target.value))}
                />
              </Field>
              <Button type="submit" size="lg" loading={findByBarcode.isPending} disabled={!manualCode}>
                Buscar
              </Button>
            </form>

            {findByBarcode.isPending && (
              <div className="flex flex-col gap-2" aria-live="polite">
                <Skeleton className="h-20 w-full" />
              </div>
            )}
          </div>
        )}

        {unknown && (
          <Card padding="lg">
            <div className="flex flex-col gap-3">
              <div className="flex items-center gap-2 text-warn-ink">
                <PackageSearch className="h-5 w-5" aria-hidden="true" />
                <p className="text-md font-semibold">Este código no está en tu catálogo</p>
              </div>
              <BarcodeDigits code={unknown.barcode} width={170} />
              {unknown.found ? (
                <div className="rounded-panel border border-dashed border-input p-3">
                  <p className="gd-eyebrow">Encontramos estos datos públicos</p>
                  <p className="mt-1 text-md font-semibold text-foreground">{unknown.name ?? 'Sin nombre'}</p>
                  <p className="text-base text-muted-foreground">
                    {[unknown.brand, unknown.quantity, unknown.categoryHint].filter(Boolean).join(' · ') ||
                      'Sin más datos'}
                  </p>
                  <p className="mt-2 text-sm text-muted-foreground">
                    Podés crear el producto con estos datos y corregirlos después.
                  </p>
                </div>
              ) : (
                <p className="text-base text-muted-foreground">
                  No encontramos el producto en la base pública. Creálo a mano y después cargá su mercadería.
                </p>
              )}
              <div className="flex flex-col-reverse gap-2 sm:flex-row">
                <Button variant="outline" onClick={startOver}>
                  Escanear otro
                </Button>
                <Button
                  leftIcon={<PackagePlus className="h-4 w-4" />}
                  onClick={() => {
                    const params = new URLSearchParams({ barcode: unknown.barcode });
                    if (unknown.name) params.set('name', unknown.name);
                    if (unknown.brand) params.set('brand', unknown.brand);
                    navigate(`/app/products/new?${params.toString()}`);
                  }}
                >
                  Crear el producto
                </Button>
              </div>
            </div>
          </Card>
        )}

        {product && (
          <>
            <div
              className="flex items-center gap-2 rounded-control border border-ok/30 bg-ok-soft px-3 py-2 text-sm font-semibold text-ok-ink"
              role="status"
            >
              <CheckCircle2 className="h-4 w-4" aria-hidden="true" />
              Producto encontrado
            </div>

            <section aria-label="Producto detectado" className="rounded-panel border bg-card p-4">
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0">
                  <div className="gd-eyebrow">{product.categoryName ?? 'Sin categoría'}</div>
                  <h2 className="mt-0.5 text-md font-semibold leading-6 text-foreground">{product.name}</h2>
                  <p className="text-sm text-muted-foreground">{product.brand ?? 'Sin marca'}</p>
                </div>
                <div className="shrink-0 text-right">
                  <div className="text-xs text-muted-foreground">Precio de venta</div>
                  <div className="font-display text-lg font-semibold leading-6 tabular-nums">
                    {formatMoney(product.salePrice)}
                  </div>
                </div>
              </div>
              <div className="mt-3 flex items-end justify-between gap-3 border-t pt-3">
                {product.barcode ? (
                  <BarcodeDigits code={product.barcode} width={150} />
                ) : (
                  <span className="text-sm text-muted-foreground">Sin código de barras</span>
                )}
                <div className="text-right text-sm">
                  <div className="text-muted-foreground">Stock vendible</div>
                  <div className="font-semibold tabular-nums">
                    {formatNumber(product.sellableStock)} {unitShort(product.unit)}
                  </div>
                </div>
              </div>
            </section>

            {blockedByRecall && <RecallPanel recalls={recallQuery.data?.recalls ?? []} />}

            <section aria-label="Vencimiento y lote" className="flex flex-col gap-3">
              {readLabel.isPending ? (
                <div className="flex flex-col gap-2" aria-live="polite">
                  <Button variant="outline" size="lg" fullWidth loading>
                    Leyendo etiqueta…
                  </Button>
                  <div className="flex gap-2">
                    <Skeleton className="h-11 w-40" />
                    <Skeleton className="h-11 w-28" />
                  </div>
                </div>
              ) : (
                <Button
                  variant="outline"
                  size="lg"
                  fullWidth
                  leftIcon={<Camera className="h-4 w-4" />}
                  onClick={() => setCameraOpen(true)}
                >
                  Leer vencimiento y lote con la cámara
                </Button>
              )}

              {ocr && (ocr.expiryDates.length > 0 || ocr.lotNumbers.length > 0) && (
                <div
                  className="flex flex-col gap-3 rounded-panel border border-dashed border-input p-3"
                  aria-live="polite"
                >
                  <p className="text-sm text-muted-foreground">
                    La cámara sugiere, vos elegís. Tocá la lectura correcta o corregila abajo.
                  </p>
                  <OcrChoice
                    name="ocr-vto"
                    label="Vencimiento"
                    value={expiryIso ?? ''}
                    onChange={(value) => setExpiryText(formatDate(value))}
                    options={ocr.expiryDates.map((candidate) => ({
                      value: candidate.value,
                      confidence: candidate.confidence * 100,
                      display: `VTO ${formatDate(candidate.value)}`,
                    }))}
                  />
                  <OcrChoice
                    name="ocr-lote"
                    label="Lote"
                    value={lotNumber}
                    onChange={setLotNumber}
                    options={ocr.lotNumbers.map((candidate) => ({
                      value: normalizeLotNumber(candidate.value),
                      confidence: candidate.confidence * 100,
                      display: `LOTE ${normalizeLotNumber(candidate.value)}`,
                    }))}
                  />
                </div>
              )}

              <div className="grid grid-cols-2 gap-3">
                <Field
                  label="Vencimiento"
                  error={expiryError}
                  optional={!product.perishable}
                  hint={needsExpiry ? 'Este producto vence: cargá la fecha.' : undefined}
                  htmlFor="intake-expiry"
                >
                  <Input
                    id="intake-expiry"
                    inputMode="numeric"
                    placeholder="dd/mm/aaaa"
                    value={expiryText}
                    onChange={(event) => setExpiryText(event.target.value)}
                    invalid={!!expiryError}
                    className="h-11 font-mono tabular-nums"
                  />
                </Field>
                <Field label="Lote" optional htmlFor="intake-lot">
                  <Input
                    id="intake-lot"
                    value={lotNumber}
                    onChange={(event) => setLotNumber(normalizeLotNumber(event.target.value))}
                    placeholder="L2409A"
                    className="h-11 font-mono uppercase"
                  />
                </Field>
              </div>
            </section>

            <section aria-label="Cantidad, costo y proveedor" className="flex flex-col gap-3">
              <div className="flex flex-wrap items-end gap-3">
                <div className="flex flex-col gap-1.5">
                  <span className="text-sm font-semibold text-foreground">Cantidad</span>
                  <QtyStepper
                    id="intake-qty"
                    label="Cantidad"
                    size="lg"
                    value={quantity}
                    onChange={setQuantity}
                    min={1}
                    max={9999}
                  />
                </div>
                <Field label="Costo unitario" optional className="min-w-[140px] flex-1" htmlFor="intake-cost">
                  <Input
                    id="intake-cost"
                    inputMode="decimal"
                    value={cost}
                    onChange={(event) => setCost(event.target.value)}
                    placeholder={product.costPrice ? String(product.costPrice) : '0'}
                    className="h-11 text-right tabular-nums"
                  />
                </Field>
              </div>
              <Field label="Proveedor" optional htmlFor="intake-supplier">
                <Select
                  id="intake-supplier"
                  value={supplierId}
                  onChange={(event) => setSupplierId(event.target.value)}
                  placeholder="Sin proveedor"
                  selectSize="lg"
                  options={(suppliersQuery.data ?? []).map((supplier) => ({
                    value: String(supplier.id),
                    label: supplier.name,
                  }))}
                />
              </Field>
            </section>

            <section aria-labelledby="intake-existing-lots" className="rounded-panel border bg-card">
              <div className="flex items-baseline justify-between gap-2 px-4 pb-2 pt-3">
                <h2 id="intake-existing-lots" className="text-base font-semibold">
                  Ya tenés en {writeBranch.branchId ? branchLots[0]?.branchName ?? 'esta sucursal' : 'la sucursal'}
                </h2>
                <span className="text-xs text-muted-foreground">Orden de salida {rotation}</span>
              </div>
              {branchLots.length === 0 && !expiryIso ? (
                <p className="border-t px-4 py-3 text-base text-muted-foreground">
                  No hay stock de este producto todavía: este va a ser el primer lote.
                </p>
              ) : (
                <LotRotationList
                  lots={branchLots}
                  rotation={rotation}
                  pending={
                    expiryIso || quantity > 0
                      ? { lotNumber: lotNumber || null, expiryDate: expiryIso, quantity, breaksRotation }
                      : null
                  }
                />
              )}
            </section>

            {breaksRotation && (
              <div
                role="status"
                className="flex gap-3 rounded-control border border-warn/40 bg-warn-soft px-3 py-3 text-warn-ink"
              >
                <AlertTriangle className="mt-0.5 h-5 w-5 shrink-0" aria-hidden="true" />
                <div className="min-w-0">
                  <p className="text-base font-semibold">Revisá el orden de salida</p>
                  <p className="mt-0.5 text-base">
                    Este lote vence antes que mercadería que ingresó antes: con FIFO se venderá después. Revisalo o
                    aplicá un descuento.
                  </p>
                </div>
              </div>
            )}
          </>
        )}

        {today.length > 0 && (
          <Card padding="none">
            <div className="flex items-baseline justify-between gap-2 px-4 pb-2 pt-3">
              <h2 className="text-base font-semibold">Cargaste hoy</h2>
              <span className="text-xs text-muted-foreground">
                {today.length} {today.length === 1 ? 'ingreso' : 'ingresos'}
              </span>
            </div>
            <ul className="divide-y border-t">
              {today.map((entry) => (
                <li key={entry.lotId} className={`px-4 py-2.5 ${entry.quarantined ? 'gd-stripe-crit' : ''}`}>
                  <div className="flex items-start justify-between gap-3">
                    <div className="min-w-0">
                      <p className="truncate text-base font-medium">{entry.productName}</p>
                      <p className="text-xs text-muted-foreground">
                        <span className="font-mono">{entry.lotNumber ?? 'Sin lote'}</span>
                        {entry.expiryDate ? ` · vence ${formatDate(entry.expiryDate)}` : ''} · {entry.branchName}
                      </p>
                      {entry.quarantined && (
                        <p className="text-xs font-semibold text-crit-ink">En cuarentena por recall</p>
                      )}
                    </div>
                    <span className="shrink-0 font-semibold tabular-nums">
                      +{formatNumber(entry.quantity)} {entry.unitLabel}
                    </span>
                  </div>
                </li>
              ))}
            </ul>
          </Card>
        )}

        {product && (
          <div
            data-bottom-action-bar=""
            className="sticky bottom-0 -mx-4 mt-auto border-t bg-card px-4 pt-3 sm:mx-0 sm:rounded-b-panel"
            style={{ paddingBottom: 'calc(16px + env(safe-area-inset-bottom, 0px))' }}
          >
            <Button
              size="xl"
              fullWidth
              loading={receive.isPending}
              disabled={!canSave || needsExpiry}
              onClick={() => {
                if (!writeBranch.isReady) {
                  setBranchError('Elegí una sucursal para esta operación.');
                  return;
                }
                receive.mutate();
              }}
            >
              {receive.isPending ? 'Registrando…' : `Registrar ingreso · ${formatNumber(quantity)} ${unitShort(product.unit)}`}
            </Button>
            {blockedByRecall && (
              <p className="mt-2 text-center text-sm font-semibold text-crit-ink">
                No se puede cargar: hay una alerta de seguridad alimentaria vigente.
              </p>
            )}
            {!blockedByRecall && needsExpiry && (
              <p className="mt-2 text-center text-sm text-muted-foreground">
                Cargá el vencimiento para registrar el ingreso.
              </p>
            )}
            {!blockedByRecall && !needsExpiry && !writeBranch.isReady && (
              <p className="mt-2 text-center text-sm text-muted-foreground">Elegí la sucursal para continuar.</p>
            )}
          </div>
        )}
      </div>

      <aside className="hidden max-w-[44ch] lg:block lg:pt-16">
        <div className="gd-eyebrow">Cómo funciona</div>
        <h2 className="mt-1 font-display text-xl font-semibold leading-8 tracking-[-0.015em] text-foreground">
          Cada ingreso crea su propio lote
        </h2>
        <p className="mt-2 text-read text-muted-foreground">
          Aunque el producto ya tenga stock con otra fecha, la mercadería que entra hoy se guarda aparte: así el orden
          de salida es exacto y las alertas de vencimiento apuntan al lote correcto.
        </p>
        <ol className="mt-5 space-y-3">
          {[
            ['Escaneá el código', 'Con la cámara, con el lector USB o escribiéndolo a mano.'],
            ['Leé vencimiento y lote', 'La cámara propone lecturas con su confianza: vos elegís la correcta.'],
            ['Revisá la rotación', `Si el lote nuevo vence antes que uno más viejo, te avisamos (${rotation}).`],
            ['Registrá el ingreso', 'Queda listo para vender y entra en el control de vencimientos.'],
          ].map(([title, description], index) => (
            <li key={title} className="flex gap-3">
              <span className="grid h-6 w-6 shrink-0 place-items-center rounded-full border border-primary text-xs font-bold tabular-nums text-primary">
                {index + 1}
              </span>
              <span>
                <span className="block text-base font-semibold text-foreground">{title}</span>
                <span className="block text-base text-muted-foreground">{description}</span>
              </span>
            </li>
          ))}
        </ol>
        <div className="mt-6 border-t pt-4">
          <ButtonLink to="/app/inventory" variant="outline" size="sm">
            Ver el inventario
          </ButtonLink>
        </div>
      </aside>

      <Modal
        open={cameraOpen}
        onClose={() => setCameraOpen(false)}
        title="Leé la etiqueta"
        description="Enfocá el vencimiento y el número de lote. La foto se procesa y se descarta."
        size="md"
      >
        {cameraOpen && (
          <CameraCapture
            captureLabel="Leer vencimiento y lote"
            hint="Acercá la cámara hasta que el texto se lea con nitidez."
            busy={readLabel.isPending}
            onCapture={(photo) => readLabel.mutate(photo)}
          />
        )}
      </Modal>
    </div>
  );
}
