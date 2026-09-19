import { useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Check, PackageX, ShieldAlert, ShieldCheck } from 'lucide-react';
import { RECALL_MATCH_STATUS_LABELS, RECALL_RESOLUTION_LABELS, type RecallResolution } from '@/api/types';
import { useAccess } from '@/auth/useAccess';
import { useBranch, useBranchQueryKey } from '@/branches/BranchContext';
import { BarcodeDigits, ExpiryChip, StatusPill } from '@/components/gondola';
import {
  Alert,
  Badge,
  Button,
  Card,
  EmptyState,
  ErrorState,
  Field,
  Modal,
  PageHeader,
  Segmented,
  Select,
  Skeleton,
  StatCard,
  Textarea,
} from '@/components/ui';
import { getErrorMessage } from '@/api/client';
import { cn } from '@/lib/cn';
import { formatDateTime, formatNumber, formatRelative } from '@/lib/format';
import { announcementKeys, recallsApi } from '../api';
import type { RecallMatch, RecallMatchFilter, ResolveRecallBody } from '../types';

const FILTERS: ReadonlyArray<{ value: RecallMatchFilter; label: string }> = [
  { value: 'ACTIVE', label: 'Para resolver' },
  { value: 'RESOLVED', label: 'Resueltos' },
  { value: 'ALL', label: 'Todos' },
];

const RESOLUTION_OPTIONS: ReadonlyArray<{ value: RecallResolution; label: string }> = [
  { value: 'REMOVED_FROM_STOCK', label: RECALL_RESOLUTION_LABELS.REMOVED_FROM_STOCK },
  { value: 'RETURNED_TO_SUPPLIER', label: RECALL_RESOLUTION_LABELS.RETURNED_TO_SUPPLIER },
  { value: 'NOT_FOUND_IN_STORE', label: RECALL_RESOLUTION_LABELS.NOT_FOUND_IN_STORE },
];

/**
 * Seguridad alimentaria (SPEC §6.7): los lotes en cuarentena por un recall, por sucursal, con "Entendido" y el
 * retiro del stock. Solo el administrador y el empleado pueden resolver (matriz §3.3).
 * <p>
 * El listado sigue la sucursal del topbar, pero una alerta puede ser de otra sucursal del usuario: con `?match=<id>`
 * (el botón del diálogo de seguridad) se cambia a la sucursal de esa coincidencia, y con una sucursal elegida se
 * avisa si quedan alertas sin resolver en las otras (el link de la campana no dice de qué sucursal es).
 */
export default function RecallsPage() {
  const { can } = useAccess();
  const { isAll, scopeLabel, selectedBranchId, setBranch, canSelectAll } = useBranch();
  const queryClient = useQueryClient();
  const [searchParams, setSearchParams] = useSearchParams();
  const [filter, setFilter] = useState<RecallMatchFilter>('ACTIVE');
  const [resolving, setResolving] = useState<RecallMatch | null>(null);

  const canResolve = can('recalls.resolve');
  const highlightedId = Number(searchParams.get('match')) || null;

  const queryKey = useBranchQueryKey('recall-matches', 'list', filter);
  const query = useQuery({
    queryKey,
    queryFn: () => recallsApi.list(filter),
  });

  // Todas las sucursales accesibles (`X-Branch-Id: all`), sin importar la elegida: ubica la coincidencia del link y
  // cuenta las pendientes de las otras sucursales. Con "Todas" elegido el listado ya las incluye.
  const acrossBranches = useQuery({
    queryKey: announcementKeys.recallsAllBranches,
    queryFn: () => recallsApi.list('ALL', 'all'),
    enabled: highlightedId !== null || (!isAll && canSelectAll),
  });

  // Si llegamos desde la alerta con ?match=…, vamos a la sucursal y al filtro donde esa fila existe (una sola vez
  // por link: si después el usuario cambia de sucursal, no lo volvemos a mover).
  const located = useRef<number | null>(null);
  useEffect(() => {
    if (highlightedId === null || located.current === highlightedId || !acrossBranches.data) return;
    const target = acrossBranches.data.find((match) => match.id === highlightedId);
    if (!target) {
      // Puede ser una alerta recién llegada que el caché todavía no tiene: esperamos el refetch.
      if (!acrossBranches.isFetching) located.current = highlightedId;
      return;
    }
    located.current = highlightedId;
    if (target.status === 'RESOLVED') {
      if (filter === 'ACTIVE') setFilter('ALL');
    } else if (filter === 'RESOLVED') {
      setFilter('ACTIVE');
    }
    if (!isAll && selectedBranchId !== target.branchId) {
      setBranch(target.branchId);
      toast.info(`Te mostramos ${target.branchName}, la sucursal de esta alerta.`);
    }
  }, [acrossBranches.data, acrossBranches.isFetching, filter, highlightedId, isAll, selectedBranchId, setBranch]);

  // Alertas sin resolver de las otras sucursales accesibles (solo con una sucursal elegida).
  const pendingElsewhere = useMemo(() => {
    if (isAll || !acrossBranches.data) return [];
    const byBranch = new Map<number, { branchId: number; branchName: string; count: number }>();
    for (const match of acrossBranches.data) {
      if (match.status === 'RESOLVED' || match.branchId === selectedBranchId) continue;
      const entry = byBranch.get(match.branchId) ?? { branchId: match.branchId, branchName: match.branchName, count: 0 };
      entry.count += 1;
      byBranch.set(match.branchId, entry);
    }
    return [...byBranch.values()].sort((a, b) => a.branchName.localeCompare(b.branchName, 'es'));
  }, [acrossBranches.data, isAll, selectedBranchId]);
  const pendingElsewhereCount = pendingElsewhere.reduce((total, entry) => total + entry.count, 0);

  const acknowledge = useMutation({
    mutationFn: (id: number) => recallsApi.acknowledge(id),
    onSuccess: () => {
      toast.success('Alerta confirmada.');
      void queryClient.invalidateQueries({ queryKey: announcementKeys.recalls });
      void queryClient.invalidateQueries({ queryKey: announcementKeys.notices });
    },
  });

  const resolve = useMutation({
    mutationFn: ({ id, body }: { id: number; body: ResolveRecallBody }) => recallsApi.resolve(id, body),
    onSuccess: () => {
      toast.success('Lote retirado del stock.');
      setResolving(null);
      void queryClient.invalidateQueries({ queryKey: announcementKeys.recalls });
      void queryClient.invalidateQueries({ queryKey: announcementKeys.notices });
      void queryClient.invalidateQueries({ queryKey: ['products'] });
      void queryClient.invalidateQueries({ queryKey: ['expirations'] });
      void queryClient.invalidateQueries({ queryKey: ['alerts'] });
    },
  });

  const matches = query.data ?? [];
  const stats = useMemo(
    () => ({
      open: matches.filter((match) => match.status === 'OPEN').length,
      acknowledged: matches.filter((match) => match.status === 'ACKNOWLEDGED').length,
      units: matches
        .filter((match) => match.status !== 'RESOLVED')
        .reduce((total, match) => total + match.currentQuantity, 0),
    }),
    [matches],
  );

  const clearHighlight = () => {
    if (highlightedId) {
      searchParams.delete('match');
      setSearchParams(searchParams, { replace: true });
    }
  };

  return (
    <>
      <PageHeader
        title="Seguridad alimentaria"
        icon={ShieldAlert}
        description={`Lotes en cuarentena por un recall · ${scopeLabel}`}
      >
        <Segmented<RecallMatchFilter>
          label="Filtrar alertas de recall"
          value={filter}
          onChange={(value) => {
            clearHighlight();
            setFilter(value);
          }}
          options={FILTERS.map((option) => ({ ...option }))}
        />
      </PageHeader>

      {pendingElsewhere.length > 0 ? (
        <Alert
          tone="crit"
          className="mb-4"
          title={
            pendingElsewhere.length === 1
              ? `${pendingElsewhereCount === 1 ? 'Hay 1 alerta' : `Hay ${pendingElsewhereCount} alertas`} sin resolver en ${pendingElsewhere[0].branchName}`
              : `Hay ${pendingElsewhereCount} alertas sin resolver en otras sucursales`
          }
          action={
            pendingElsewhere.length === 1 ? (
              <Button size="sm" variant="outline" onClick={() => setBranch(pendingElsewhere[0].branchId)}>
                Ver {pendingElsewhere[0].branchName}
              </Button>
            ) : (
              <Button size="sm" variant="outline" onClick={() => setBranch('all')}>
                Ver todas
              </Button>
            )
          }
        >
          {pendingElsewhere.length === 1
            ? `Estás viendo ${scopeLabel}. Cambiá de sucursal para ver el lote y retirarlo del stock.`
            : `Estás viendo ${scopeLabel}. ${pendingElsewhere
                .map((entry) => `${entry.branchName}: ${entry.count}`)
                .join(' · ')}.`}
        </Alert>
      ) : null}

      {filter !== 'RESOLVED' && matches.length > 0 ? (
        <div className="mb-4 grid gap-4 sm:grid-cols-3">
          <StatCard label="Sin confirmar" value={stats.open} icon={ShieldAlert} tone={stats.open ? 'crit' : 'ok'} />
          <StatCard label="Confirmados, sin retirar" value={stats.acknowledged} icon={Check} tone="warn" />
          <StatCard
            label="Unidades en cuarentena"
            value={formatNumber(stats.units)}
            icon={PackageX}
            tone="neutral"
            hint="No se pueden vender"
          />
        </div>
      ) : null}

      {query.isPending ? (
        <div className="space-y-3" aria-busy="true">
          {[0, 1].map((row) => (
            <Card key={row} padding="md">
              <Skeleton className="h-4 w-28" />
              <Skeleton className="mt-3 h-5 w-1/2" />
              <Skeleton className="mt-2 h-4 w-full" />
            </Card>
          ))}
        </div>
      ) : query.isError ? (
        <ErrorState error={query.error} onRetry={() => void query.refetch()} />
      ) : matches.length === 0 ? (
        <EmptyState
          icon={ShieldCheck}
          title={
            filter === 'RESOLVED'
              ? 'Todavía no resolviste ningún recall'
              : pendingElsewhere.length > 0
                ? `No hay recalls pendientes en ${scopeLabel}`
                : 'No tenés recalls pendientes'
          }
          description={
            filter === 'RESOLVED'
              ? 'Acá van a quedar los retiros que ya hiciste, con quién los resolvió y cómo.'
              : `Ninguna mercadería de ${isAll ? 'tus sucursales' : scopeLabel} está alcanzada por un retiro. Te avisamos al instante si eso cambia.`
          }
          bordered
        />
      ) : (
        <div className="space-y-3">
          {matches.map((match) => (
            <MatchCard
              key={match.id}
              match={match}
              highlighted={match.id === highlightedId}
              canResolve={canResolve}
              acknowledging={acknowledge.isPending && acknowledge.variables === match.id}
              onAcknowledge={() => acknowledge.mutate(match.id)}
              onResolve={() => setResolving(match)}
            />
          ))}
        </div>
      )}

      <ResolveDialog
        match={resolving}
        onClose={() => setResolving(null)}
        onSubmit={(body) => resolving && resolve.mutate({ id: resolving.id, body })}
        loading={resolve.isPending}
        error={resolve.isError ? getErrorMessage(resolve.error) : undefined}
      />
    </>
  );
}

function MatchCard({
  match,
  highlighted,
  canResolve,
  acknowledging,
  onAcknowledge,
  onResolve,
}: {
  match: RecallMatch;
  highlighted: boolean;
  canResolve: boolean;
  acknowledging: boolean;
  onAcknowledge: () => void;
  onResolve: () => void;
}) {
  const resolved = match.status === 'RESOLVED';
  const cardRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (highlighted) cardRef.current?.scrollIntoView({ block: 'center', behavior: 'smooth' });
  }, [highlighted]);
  return (
    <Card
      ref={cardRef}
      padding="none"
      className={cn(
        'overflow-hidden',
        resolved ? 'gd-stripe-ok' : 'gd-stripe-crit',
        highlighted && 'ring-2 ring-crit ring-offset-2 ring-offset-background',
      )}
    >
      <div className="flex flex-col gap-4 p-4 sm:p-5">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0 flex-1 basis-[240px]">
            <div className="flex flex-wrap items-center gap-2">
              <StatusPill tone={resolved ? 'ok' : match.status === 'OPEN' ? 'crit' : 'warn'} solid={!resolved}>
                {RECALL_MATCH_STATUS_LABELS[match.status]}
              </StatusPill>
              <Badge tone="neutral" size="sm">
                {match.branchName}
              </Badge>
              <span className="text-xs text-muted-foreground">{formatRelative(match.matchedAt)}</span>
            </div>
            <h2 className="mt-2 font-display text-md font-semibold leading-6 text-foreground">
              {match.productName}
            </h2>
            <div className="mt-1.5 flex flex-wrap items-center gap-2">
              {match.lotNumber ? (
                <span className="inline-flex h-[22px] items-center rounded-tag border border-crit/40 bg-crit-soft px-1.5 font-mono text-xs font-semibold text-crit-ink">
                  LOTE {match.lotNumber.toUpperCase()}
                </span>
              ) : null}
              {match.expiryDate ? <ExpiryChip expiry={match.expiryDate} longYear /> : null}
              <span className="font-mono text-xs tabular-nums text-muted-foreground">
                {formatNumber(match.currentQuantity)} de {formatNumber(match.quantityAtMatch)} u.
              </span>
            </div>
          </div>
          {match.barcode ? <BarcodeDigits code={match.barcode} digitsOnly /> : null}
        </div>

        <div className="rounded-control border border-border bg-muted/40 px-3 py-2.5">
          <div className="gd-eyebrow">{match.title}</div>
          {match.reason ? <p className="mt-1 text-base text-foreground">{match.reason}</p> : null}
          {match.instructions ? (
            <p className="mt-1 text-base text-muted-foreground">{match.instructions}</p>
          ) : null}
        </div>

        {resolved ? (
          <div className="space-y-0.5 border-t border-border pt-3 text-sm text-muted-foreground">
            {/* Quién lo reconoció, quién lo resolvió y la nota (datos-demo §5). Si se resolvió sin un "Entendido"
                previo, el backend registra la confirmación en el mismo instante y por el mismo usuario: no se repite. */}
            {match.acknowledgedAt && match.acknowledgedAt !== match.resolvedAt ? (
              <p>
                Confirmado por {match.acknowledgedByName ?? 'un usuario'} el {formatDateTime(match.acknowledgedAt)}
              </p>
            ) : null}
            <p>
              {match.resolution ? RECALL_RESOLUTION_LABELS[match.resolution] : 'Resuelto'} por{' '}
              {match.resolvedByName ?? 'un usuario'} el {match.resolvedAt ? formatDateTime(match.resolvedAt) : '—'}
              {match.resolutionNote ? ` · ${match.resolutionNote}` : ''}
            </p>
          </div>
        ) : (
          <div className="flex flex-col gap-2 border-t border-border pt-3 sm:flex-row sm:items-center sm:justify-between">
            <p className="text-sm text-muted-foreground">
              {match.acknowledgedAt
                ? `Confirmado por ${match.acknowledgedByName ?? 'un usuario'} el ${formatDateTime(match.acknowledgedAt)}`
                : 'Nadie confirmó esta alerta todavía.'}
            </p>
            <div className="flex flex-col-reverse gap-2 sm:flex-row">
              {match.status === 'OPEN' ? (
                <Button variant="outline" size="sm" onClick={onAcknowledge} loading={acknowledging}>
                  Entendido
                </Button>
              ) : null}
              {canResolve ? (
                <Button variant="destructive" size="sm" onClick={onResolve}>
                  Retirar del stock
                </Button>
              ) : (
                <span className="text-sm text-muted-foreground">
                  Solo el administrador o un empleado pueden retirarlo.
                </span>
              )}
            </div>
          </div>
        )}
      </div>
    </Card>
  );
}

function ResolveDialog({
  match,
  onClose,
  onSubmit,
  loading,
  error,
}: {
  match: RecallMatch | null;
  onClose: () => void;
  onSubmit: (body: ResolveRecallBody) => void;
  loading: boolean;
  error?: string;
}) {
  const [resolution, setResolution] = useState<RecallResolution>('REMOVED_FROM_STOCK');
  const [note, setNote] = useState('');

  useEffect(() => {
    if (match) {
      setResolution('REMOVED_FROM_STOCK');
      setNote('');
    }
  }, [match]);

  if (!match) return null;

  return (
    <Modal
      open
      onClose={onClose}
      title="Retirar el lote del stock"
      description={`${match.productName} · lote ${match.lotNumber ?? 'sin número'} · ${match.branchName}`}
      size="md"
      footer={
        <>
          <Button variant="outline" onClick={onClose} disabled={loading}>
            Cancelar
          </Button>
          <Button
            variant="destructive"
            loading={loading}
            onClick={() => onSubmit({ resolution, note: note.trim() || null })}
          >
            Retirar {formatNumber(match.currentQuantity)} u.
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        <p className="text-base text-muted-foreground">
          Se van a descontar del stock las {formatNumber(match.currentQuantity)} unidades que quedan en cuarentena y
          queda registrado el movimiento. Esta acción no se puede deshacer.
        </p>
        <Field label="¿Qué hiciste con la mercadería?" htmlFor="recall-resolution">
          <Select
            id="recall-resolution"
            value={resolution}
            onChange={(event) => setResolution(event.target.value as RecallResolution)}
            options={RESOLUTION_OPTIONS.map((option) => ({ ...option }))}
          />
        </Field>
        <Field label="Nota" optional htmlFor="recall-note" hint="Por ejemplo, quién se la lleva o el remito.">
          <Textarea
            id="recall-note"
            rows={2}
            value={note}
            maxLength={300}
            onChange={(event) => setNote(event.target.value)}
            placeholder="Lo retira el distribuidor el jueves"
          />
        </Field>
        {error ? (
          <p role="alert" className="text-sm text-crit-ink">
            {error}
          </p>
        ) : null}
      </div>
    </Modal>
  );
}
