import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Check,
  Copy,
  Download,
  FileSpreadsheet,
  KeyRound,
  Play,
  Plug,
  Store,
  Upload,
} from 'lucide-react';
import { useRef, useState } from 'react';
import { toast } from 'sonner';
import { getErrorMessage } from '@/api/client';
import { StatusPill } from '@/components/gondola';
import {
  Alert,
  Button,
  Card,
  CardHeader,
  ConfirmDialog,
  ErrorState,
  Field,
  Input,
  PageHeader,
  Select,
  Skeleton,
} from '@/components/ui';
import { formatDateTime, formatMoney, formatNumber, formatRelative } from '@/lib/format';
import { movementKeys, posIntegrationApi, salesApi } from '../api';
import type { PosApiKey, PosIntegration, SalesImportResult, SimulateResult } from '../types';

/** Ejemplo de uso del webhook con la key recién generada (o un marcador si todavía no se ve). */
function curlExample(apiKey: string): string {
  return `curl -X POST ${window.location.origin}/api/integrations/pos/sales \\
  -H "X-API-Key: ${apiKey}" \\
  -H "Content-Type: application/json" \\
  -d '{"externalId":"T-1001","items":[{"barcode":"7791234000012","quantity":2}]}'`;
}

function CopyButton({ value, label }: { value: string; label: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <Button
      variant="outline"
      size="sm"
      leftIcon={copied ? <Check className="h-3.5 w-3.5" /> : <Copy className="h-3.5 w-3.5" />}
      onClick={async () => {
        try {
          await navigator.clipboard.writeText(value);
          setCopied(true);
          toast.success(`${label} copiado.`);
          window.setTimeout(() => setCopied(false), 2000);
        } catch {
          toast.error('No pudimos copiar. Seleccioná el texto y copialo a mano.');
        }
      }}
    >
      {copied ? 'Copiado' : 'Copiar'}
    </Button>
  );
}

// ---------------------------------------------------------------------------
// API keys por sucursal
// ---------------------------------------------------------------------------

function BranchKeyCard({
  branch,
  onGenerated,
}: {
  branch: PosIntegration;
  onGenerated: (key: PosApiKey) => void;
}) {
  const queryClient = useQueryClient();
  const [confirming, setConfirming] = useState(false);

  const generate = useMutation({
    mutationFn: () => posIntegrationApi.generateKey(branch.branchId),
    onSuccess: (key) => {
      toast.success(`Generaste la API key de ${key.branchName}. Copiala: no se vuelve a mostrar.`);
      onGenerated(key);
      setConfirming(false);
      queryClient.invalidateQueries({ queryKey: movementKeys.integrations });
    },
    onError: (error) => toast.error(getErrorMessage(error, 'No pudimos generar la API key.')),
  });

  return (
    <>
      <Card>
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0">
            <p className="flex items-center gap-1.5 text-base font-semibold text-foreground">
              <Store className="h-4 w-4 text-muted-foreground" aria-hidden="true" />
              {branch.branchName}
              {branch.branchCode ? (
                <span className="font-mono text-xs text-muted-foreground">{branch.branchCode}</span>
              ) : null}
            </p>
            <p className="mt-1 text-sm text-muted-foreground">
              {branch.configured ? (
                <>
                  Key <span className="font-mono text-foreground">{branch.prefix}…</span> generada el{' '}
                  {formatDateTime(branch.createdAt)}
                </>
              ) : (
                'Todavía no generaste la API key de esta sucursal.'
              )}
            </p>
          </div>
          <StatusPill tone={branch.configured ? 'ok' : 'neutral'}>
            {branch.configured ? 'Conectada' : 'Sin configurar'}
          </StatusPill>
        </div>

        <dl className="mt-3 grid grid-cols-2 gap-3 border-t border-border pt-3 text-sm sm:grid-cols-3">
          <div>
            <dt className="gd-eyebrow">Ventas 24 h</dt>
            <dd className="tabular-nums text-foreground">{formatNumber(branch.salesLast24h)}</dd>
          </div>
          <div>
            <dt className="gd-eyebrow">Unidades 24 h</dt>
            <dd className="tabular-nums text-foreground">{formatNumber(branch.unitsLast24h)}</dd>
          </div>
          <div>
            <dt className="gd-eyebrow">Última venta</dt>
            <dd className="text-foreground">
              {branch.lastSaleAt ? formatRelative(branch.lastSaleAt) : '—'}
            </dd>
          </div>
        </dl>

        <Button
          variant={branch.configured ? 'outline' : 'default'}
          className="mt-3"
          leftIcon={<KeyRound className="h-4 w-4" />}
          loading={generate.isPending}
          onClick={() => (branch.configured ? setConfirming(true) : generate.mutate())}
        >
          {branch.configured ? 'Volver a generar la key' : 'Generar la API key'}
        </Button>
      </Card>

      <ConfirmDialog
        open={confirming}
        onClose={() => setConfirming(false)}
        onConfirm={() => generate.mutateAsync()}
        tone="danger"
        title={`Volver a generar la key de ${branch.branchName}`}
        description="La key actual deja de funcionar al instante: tu POS va a dejar de enviar ventas hasta que cargues la nueva."
        confirmLabel="Generar una key nueva"
        loading={generate.isPending}
      />
    </>
  );
}

function NewKeyPanel({ apiKey, onDismiss }: { apiKey: PosApiKey; onDismiss: () => void }) {
  return (
    <Alert tone="brand" title={`API key de ${apiKey.branchName}`} icon={KeyRound}>
      <p className="mb-2">
        Copiala ahora y guardala en tu POS: por seguridad no la volvemos a mostrar. Si la perdés, generá una nueva.
      </p>
      <div className="flex flex-wrap items-center gap-2">
        <code className="min-w-0 flex-1 overflow-x-auto rounded-control border border-border bg-card px-3 py-2 font-mono text-sm text-foreground gd-scroll">
          {apiKey.apiKey}
        </code>
        <CopyButton value={apiKey.apiKey} label="La API key" />
      </div>

      <p className="mb-2 mt-4 font-semibold">Probala desde tu terminal</p>
      <div className="flex flex-wrap items-start gap-2">
        <pre className="min-w-0 flex-1 overflow-x-auto rounded-control border border-border bg-card px-3 py-2 font-mono text-xs text-foreground gd-scroll">
          {curlExample(apiKey.apiKey)}
        </pre>
        <CopyButton value={curlExample(apiKey.apiKey)} label="El ejemplo" />
      </div>

      <Button variant="ghost" size="sm" className="mt-3" onClick={onDismiss}>
        Ya la guardé
      </Button>
    </Alert>
  );
}

// ---------------------------------------------------------------------------
// Importación CSV
// ---------------------------------------------------------------------------

function ImportCard({ branches }: { branches: PosIntegration[] }) {
  const queryClient = useQueryClient();
  const inputRef = useRef<HTMLInputElement>(null);
  const [branchId, setBranchId] = useState<number | null>(branches[0]?.branchId ?? null);
  const [result, setResult] = useState<SalesImportResult | null>(null);

  const importCsv = useMutation({
    mutationFn: (file: File) => salesApi.importCsv(file, branchId, file.name),
    onSuccess: (data) => {
      setResult(data);
      if (data.imported > 0) {
        toast.success(`Importaste ${formatNumber(data.imported)} ventas.`);
        queryClient.invalidateQueries({ queryKey: movementKeys.sales });
        queryClient.invalidateQueries({ queryKey: movementKeys.movements });
        queryClient.invalidateQueries({ queryKey: ['products'] });
      } else {
        toast.warning('No se importó ninguna línea. Revisá el detalle.');
      }
    },
    onError: (error) => toast.error(getErrorMessage(error, 'No pudimos importar el archivo.')),
  });

  const download = useMutation({
    mutationFn: () => salesApi.downloadTemplate(),
    onError: (error) => toast.error(getErrorMessage(error, 'No pudimos descargar la plantilla.')),
  });

  return (
    <Card padding="none">
      <CardHeader
        icon={FileSpreadsheet}
        title="Importar ventas desde un CSV"
        description="Columnas: fecha, codigo_barras, cantidad, precio_unitario. La fecha va como aaaa-mm-dd o dd/mm/aaaa."
        actions={
          <Button
            variant="outline"
            size="sm"
            leftIcon={<Download className="h-4 w-4" />}
            loading={download.isPending}
            onClick={() => download.mutate()}
          >
            Descargar plantilla
          </Button>
        }
      />
      <div className="flex flex-col gap-3 p-4 pt-0">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-end">
          <Field label="Sucursal" className="sm:max-w-xs sm:flex-1">
            <Select
              value={branchId ?? ''}
              options={branches.map((branch) => ({ value: branch.branchId, label: branch.branchName }))}
              onChange={(event) => setBranchId(Number(event.target.value))}
            />
          </Field>
          <input
            ref={inputRef}
            type="file"
            accept=".csv,text/csv"
            className="sr-only"
            onChange={(event) => {
              const file = event.target.files?.[0];
              if (file) importCsv.mutate(file);
              event.target.value = '';
            }}
          />
          <Button
            leftIcon={<Upload className="h-4 w-4" />}
            loading={importCsv.isPending}
            disabled={branchId == null}
            onClick={() => inputRef.current?.click()}
          >
            Elegir archivo CSV
          </Button>
        </div>

        {result ? (
          <div className="rounded-panel border border-border p-3">
            <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-sm">
              <span className="font-semibold text-ok-ink tabular-nums">
                {formatNumber(result.imported)} importadas
              </span>
              <span className="tabular-nums text-muted-foreground">
                {formatNumber(result.skipped)} salteadas
              </span>
              <span className="tabular-nums text-muted-foreground">
                {formatNumber(result.units)} u. · {formatMoney(result.total)}
              </span>
            </div>
            {result.errors.length > 0 ? (
              <div className="mt-2 border-t border-border pt-2">
                <p className="mb-1 text-sm font-semibold text-crit-ink">
                  {formatNumber(result.errors.length)} líneas con problemas
                </p>
                <ul className="max-h-40 overflow-y-auto gd-scroll text-sm text-muted-foreground">
                  {result.errors.map((error) => (
                    <li key={error.line} className="tabular-nums">
                      Línea {error.line}: {error.message}
                    </li>
                  ))}
                </ul>
              </div>
            ) : null}
          </div>
        ) : null}
      </div>
    </Card>
  );
}

// ---------------------------------------------------------------------------
// Simulador
// ---------------------------------------------------------------------------

function SimulatorCard({ branches }: { branches: PosIntegration[] }) {
  const queryClient = useQueryClient();
  const [branchId, setBranchId] = useState<number | null>(branches[0]?.branchId ?? null);
  const [sales, setSales] = useState('10');
  const [result, setResult] = useState<SimulateResult | null>(null);

  const simulate = useMutation({
    mutationFn: () => posIntegrationApi.simulate(branchId as number, Number(sales)),
    onSuccess: (data) => {
      setResult(data);
      toast.success(`Generaste ${formatNumber(data.sales)} ventas de prueba en ${data.branchName}.`);
      queryClient.invalidateQueries({ queryKey: movementKeys.sales });
      queryClient.invalidateQueries({ queryKey: movementKeys.movements });
      queryClient.invalidateQueries({ queryKey: movementKeys.integrations });
      queryClient.invalidateQueries({ queryKey: ['products'] });
    },
    onError: (error) => toast.error(getErrorMessage(error, 'No pudimos simular las ventas.')),
  });

  const parsed = Number(sales);
  const invalid = !Number.isInteger(parsed) || parsed < 1 || parsed > 200;

  return (
    <Card padding="none">
      <CardHeader
        icon={Play}
        title="Simulador de ventas"
        description="Genera ventas aleatorias realistas por el mismo camino que el webhook, para probar la integración."
      />
      <div className="flex flex-col gap-3 p-4 pt-0">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-end">
          <Field label="Sucursal" className="sm:max-w-xs sm:flex-1">
            <Select
              value={branchId ?? ''}
              options={branches.map((branch) => ({ value: branch.branchId, label: branch.branchName }))}
              onChange={(event) => setBranchId(Number(event.target.value))}
            />
          </Field>
          <Field
            label="Cantidad de ventas"
            error={invalid ? 'Entre 1 y 200.' : undefined}
            className="sm:w-40"
          >
            <Input
              type="number"
              min={1}
              max={200}
              step={1}
              inputMode="numeric"
              value={sales}
              onChange={(event) => setSales(event.target.value)}
              className="tabular-nums"
            />
          </Field>
          <Button
            leftIcon={<Play className="h-4 w-4" />}
            loading={simulate.isPending}
            disabled={invalid || branchId == null}
            onClick={() => simulate.mutate()}
          >
            Simular ventas
          </Button>
        </div>

        {result ? (
          <p className="rounded-panel border border-border px-3 py-2 text-sm tabular-nums text-muted-foreground">
            {formatNumber(result.sales)} ventas · {formatNumber(result.lines)} líneas ·{' '}
            {formatNumber(result.units)} u. · {formatMoney(result.total)}
            {result.shortages > 0 ? (
              <span className="text-warn-ink">
                {' '}
                · {formatNumber(result.shortages)} líneas sin stock suficiente
              </span>
            ) : null}
          </p>
        ) : null}
      </div>
    </Card>
  );
}

// ---------------------------------------------------------------------------

export default function IntegrationsPage() {
  const [newKey, setNewKey] = useState<PosApiKey | null>(null);

  const integrations = useQuery({
    queryKey: movementKeys.integrations,
    queryFn: () => posIntegrationApi.status(),
  });

  const branches = integrations.data ?? [];

  return (
    <>
      <PageHeader
        title="Integración con tu POS"
        icon={Plug}
        description="Conectá el punto de venta que ya usás: cada venta descuenta stock en GondolIA."
      />

      {integrations.isPending ? (
        <div className="grid gap-4 sm:grid-cols-2">
          <Skeleton className="h-48 w-full" />
          <Skeleton className="h-48 w-full" />
        </div>
      ) : integrations.isError ? (
        <ErrorState error={integrations.error} onRetry={() => void integrations.refetch()} />
      ) : (
        <div className="flex flex-col gap-4">
          {newKey ? <NewKeyPanel apiKey={newKey} onDismiss={() => setNewKey(null)} /> : null}

          <section className="flex flex-col gap-3">
            <h2 className="gd-eyebrow">API key por sucursal</h2>
            <div className="grid gap-4 sm:grid-cols-2">
              {branches.map((branch) => (
                <BranchKeyCard key={branch.branchId} branch={branch} onGenerated={setNewKey} />
              ))}
            </div>
          </section>

          {branches.length > 0 ? (
            <>
              <ImportCard branches={branches} />
              <SimulatorCard branches={branches} />
            </>
          ) : null}
        </div>
      )}
    </>
  );
}
