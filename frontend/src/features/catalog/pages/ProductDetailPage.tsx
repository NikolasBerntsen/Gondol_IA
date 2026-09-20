import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  AlertTriangle,
  History,
  Layers,
  Package,
  Pencil,
  ScanBarcode,
  ShieldAlert,
  Sparkles,
  Store,
  Trash2,
} from 'lucide-react';
import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { toast } from 'sonner';
import { getErrorMessage } from '@/api/client';
import { LOT_STATUS_LABELS, SALES_PATTERN_LABELS } from '@/api/types';
import { useCurrentUser } from '@/auth/AuthContext';
import { useAccess } from '@/auth/useAccess';
import { useBranch } from '@/branches/BranchContext';
import { useBranchQueryKey } from '@/branches/BranchContext';
import { BarcodeDigits, ExpiryChip, LotRankChip, StatusPill, StockStatusPill } from '@/components/gondola';
import {
  Alert,
  Badge,
  Button,
  ButtonLink,
  Card,
  CardHeader,
  ConfirmDialog,
  ErrorState,
  PageHeader,
  PageSpinner,
  StatCard,
  Table,
  Truncate,
  type TableColumn,
} from '@/components/ui';
import { formatDate, formatDateTime, formatMoney, formatNumber, formatRelative } from '@/lib/format';
import { productInsightApi, productsApi } from '../api';
import { movementSign, movementSourceLabel, movementTypeLabel, unitShort } from '../lib';
import type { LotDto, ProductMovement } from '../types';

/** Lote alcanzado por un recall que ya se retiró del stock (queda `RECALLED` con 0 u.). */
function isWithdrawn(lot: LotDto): boolean {
  return lot.status === 'RECALLED' && lot.quantity === 0;
}

export default function ProductDetailPage() {
  const { id } = useParams<{ id: string }>();
  const productId = Number(id);
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const me = useCurrentUser();
  const { can } = useAccess();
  const { isAll } = useBranch();
  // El jefe ve la ficha completa (lotes, stock, movimientos, IA) pero no carga, edita ni da de baja (SPEC §3.3).
  const canIntake = can('intake.use');
  const canEdit = can('products.write');
  const canDelete = can('products.delete');
  const canSeeInsights = can('dashboard.view');
  const rotation = me.tenant?.stockRotation ?? 'FIFO';

  const [confirmDelete, setConfirmDelete] = useState(false);

  const productQuery = useQuery({
    queryKey: useBranchQueryKey('products', 'detail', productId),
    queryFn: () => productsApi.get(productId),
    enabled: Number.isFinite(productId),
  });

  const movementsQuery = useQuery({
    queryKey: useBranchQueryKey('products', 'movements', productId),
    queryFn: () => productsApi.movements(productId),
    enabled: Number.isFinite(productId),
  });

  // Resumen de la IA (módulo B, por sucursal): una fila por sucursal del alcance. Si todavía no hay análisis o la
  // lectura falla, la tarjeta simplemente no aparece.
  const loadedProduct = productQuery.data;
  const insightQuery = useQuery({
    queryKey: useBranchQueryKey('products', 'insight', productId),
    queryFn: () => productInsightApi.byBranch(loadedProduct!),
    enabled: !!loadedProduct && canSeeInsights,
    retry: false,
    meta: { errorToast: false },
  });

  const remove = useMutation({
    mutationFn: () => productsApi.remove(productId),
    onSuccess: (result) => {
      toast.success(result?.deactivated ? 'Producto dado de baja.' : 'Producto eliminado.', {
        description: result?.deactivated
          ? 'Tenía movimientos, así que queda en el historial pero ya no se puede vender ni cargar.'
          : undefined,
      });
      void queryClient.invalidateQueries({ queryKey: ['products'] });
      navigate('/app/inventory');
    },
  });

  if (productQuery.isPending) return <PageSpinner />;
  if (productQuery.isError) {
    return (
      <>
        <PageHeader title="Producto" back={{ to: '/app/inventory', label: 'Inventario' }} />
        <ErrorState error={productQuery.error} onRetry={() => void productQuery.refetch()} />
      </>
    );
  }

  const product = productQuery.data;
  const insights = (insightQuery.data ?? []).filter((row) => !!row.pattern || !!row.patternDescription);
  const hasInsight = insights.length > 0;
  const lastAnalysis = insights.reduce<string | null>(
    (latest, row) => (row.updatedAt && (!latest || row.updatedAt > latest) ? row.updatedAt : latest),
    null,
  );
  // Sin movimientos en el alcance: nunca entró mercadería. Si los hay, el producto tuvo stock y sus lotes se
  // vendieron, vencieron o se retiraron (la ficha solo muestra los lotes vacíos recientes, docs/api-a1.md §4).
  const neverHadStock = movementsQuery.isSuccess && movementsQuery.data.length === 0;

  const lotColumns: Array<TableColumn<LotDto> | null> = [
    {
      id: 'rank',
      header: 'Orden',
      mobile: 'aside',
      cell: (lot) =>
        lot.rotationRank != null ? (
          <LotRankChip rank={lot.rotationRank} rotation={rotation} discounted={!!lot.discountPct} />
        ) : isWithdrawn(lot) ? (
          <StatusPill tone="neutral">Retirado por recall</StatusPill>
        ) : (
          <StatusPill tone={lot.status === 'RECALLED' ? 'crit' : 'neutral'}>
            {LOT_STATUS_LABELS[lot.status]}
          </StatusPill>
        ),
    },
    {
      id: 'lot',
      header: 'Lote',
      mobile: 'title',
      cell: (lot) => (
        <div className="min-w-0">
          <div className="font-mono text-sm font-medium">{lot.lotNumber || 'Sin lote'}</div>
          {isAll && <div className="text-xs text-muted-foreground">{lot.branchName}</div>}
        </div>
      ),
    },
    {
      id: 'expiry',
      header: 'Vencimiento',
      mobile: 'aside',
      cell: (lot) =>
        lot.expiryDate ? (
          <ExpiryChip expiry={lot.expiryDate} bucket={lot.expiryBucket} showDays />
        ) : (
          <span className="text-sm text-muted-foreground">Sin vencimiento</span>
        ),
    },
    {
      id: 'quantity',
      header: 'Cantidad',
      align: 'right',
      mobile: 'field',
      mobileLabel: 'Cantidad',
      cell: (lot) => (
        <span className="whitespace-nowrap tabular-nums">
          <span className="font-semibold">{formatNumber(lot.quantity)}</span>{' '}
          <span className="text-sm text-muted-foreground">
            de {formatNumber(lot.initialQuantity)} {unitShort(product.unit)}
          </span>
        </span>
      ),
    },
    {
      id: 'cost',
      header: 'Costo',
      align: 'right',
      hideBelow: 'lg',
      mobile: 'field',
      mobileLabel: 'Costo',
      cell: (lot) => (
        <span className="tabular-nums">{lot.costPrice != null ? formatMoney(lot.costPrice) : '—'}</span>
      ),
    },
    {
      id: 'discount',
      header: 'Liquidación',
      align: 'right',
      hideBelow: 'xl',
      mobile: 'hidden',
      cell: (lot) =>
        lot.discountPct ? (
          <Badge tone="warn">-{formatNumber(lot.discountPct)}%</Badge>
        ) : (
          <span className="text-muted-foreground">—</span>
        ),
    },
    {
      id: 'received',
      header: 'Ingresó',
      hideBelow: 'lg',
      mobile: 'field',
      mobileLabel: 'Ingresó',
      cell: (lot) => (
        <div className="whitespace-nowrap">
          <div className="text-sm">{formatDate(lot.receivedAt)}</div>
          {lot.supplierName && <div className="text-xs text-muted-foreground">{lot.supplierName}</div>}
        </div>
      ),
    },
  ];

  const movementColumns: Array<TableColumn<ProductMovement> | null> = [
    {
      id: 'type',
      header: 'Movimiento',
      mobile: 'title',
      cell: (movement) => (
        <div className="min-w-0">
          <div className="font-semibold">{movementTypeLabel(movement.type)}</div>
          <div className="text-xs text-muted-foreground">
            {movementSourceLabel(movement.source)}
            {movement.lotNumber ? ` · lote ${movement.lotNumber}` : ''}
            {isAll ? ` · ${movement.branchName}` : ''}
          </div>
          {movement.reason && <div className="text-xs text-muted-foreground">{movement.reason}</div>}
        </div>
      ),
    },
    {
      id: 'quantity',
      header: 'Cantidad',
      align: 'right',
      mobile: 'aside',
      cell: (movement) => {
        const sign = movementSign(movement.type);
        return (
          <span className={`whitespace-nowrap font-semibold tabular-nums ${sign > 0 ? 'text-ok-ink' : 'text-crit-ink'}`}>
            {sign > 0 ? '+' : '−'}
            {formatNumber(movement.quantity)} {unitShort(product.unit)}
          </span>
        );
      },
    },
    {
      id: 'when',
      header: 'Cuándo',
      align: 'right',
      mobile: 'field',
      mobileLabel: 'Cuándo',
      cell: (movement) => (
        <div className="whitespace-nowrap text-sm">
          <div>{formatRelative(movement.occurredAt)}</div>
          <div className="text-xs text-muted-foreground">{movement.userName ?? 'Sistema'}</div>
        </div>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        eyebrow={product.categoryName ?? 'Sin categoría'}
        title={product.name}
        icon={Package}
        description={
          [product.brand, product.supplierName ? `Proveedor: ${product.supplierName}` : null]
            .filter(Boolean)
            .join(' · ') || 'Sin marca cargada'
        }
        back={{ to: '/app/inventory', label: 'Inventario' }}
        actions={
          canIntake || canEdit || canDelete ? (
          <div className="flex flex-wrap items-center gap-2">
            {canIntake && (
              <ButtonLink
                to={`/app/intake?productId=${product.id}${
                  product.barcode ? `&barcode=${encodeURIComponent(product.barcode)}` : ''
                }`}
                leftIcon={<ScanBarcode className="h-4 w-4" />}
              >
                Cargar mercadería
              </ButtonLink>
            )}
            {canEdit && (
              <ButtonLink
                to={`/app/products/${product.id}/edit`}
                variant="outline"
                leftIcon={<Pencil className="h-4 w-4" />}
              >
                Editar
              </ButtonLink>
            )}
            {canDelete && (
              <Button
                variant="ghost"
                size="icon"
                aria-label="Dar de baja el producto"
                title="Dar de baja"
                onClick={() => setConfirmDelete(true)}
              >
                <Trash2 className="h-4 w-4" aria-hidden="true" />
              </Button>
            )}
          </div>
          ) : undefined
        }
      />

      <div className="flex flex-col gap-4">
        {!product.active && (
          <Alert tone="warn" title="Producto dado de baja">
            No aparece en el punto de venta ni en la carga de mercadería.
            {canEdit ? ' Podés reactivarlo desde Editar.' : ''}
          </Alert>
        )}
        {product.quarantinedStock > 0 && (
          <Alert tone="crit" icon={ShieldAlert} title="Hay mercadería en cuarentena">
            {formatNumber(product.quarantinedStock)} {unitShort(product.unit)} retenidas por una alerta de seguridad
            alimentaria. {can('recalls.resolve') ? 'Resolvelas desde' : 'Las ves en'} Seguridad alimentaria.
          </Alert>
        )}

        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
          <StatCard
            label="Stock vendible"
            value={`${formatNumber(product.sellableStock)} ${unitShort(product.unit)}`}
            icon={Package}
            tone={product.stockStatus === 'OUT' ? 'crit' : product.stockStatus === 'LOW' ? 'warn' : 'ok'}
            hint={product.minStock > 0 ? `Mínimo por sucursal: ${formatNumber(product.minStock)}` : 'Sin mínimo definido'}
          />
          <StatCard
            label="Próximo vencimiento"
            value={product.nextExpiryDate ? formatDate(product.nextExpiryDate) : '—'}
            icon={AlertTriangle}
            tone={product.nextExpiryDate ? 'warn' : 'neutral'}
            hint={`${formatNumber(product.lotsCount)} ${product.lotsCount === 1 ? 'lote vivo' : 'lotes vivos'}`}
          />
          <StatCard
            label="Vencido pendiente"
            value={`${formatNumber(product.expiredStock)} ${unitShort(product.unit)}`}
            icon={AlertTriangle}
            tone={product.expiredStock > 0 ? 'crit' : 'neutral'}
            hint={
              product.expiredStock > 0
                ? can('expirations.discard')
                  ? 'Descartalo desde Vencimientos'
                  : 'Pendiente de descarte en Vencimientos'
                : 'Nada vencido en stock'
            }
          />
          <StatCard
            label="Precio de venta"
            money={product.salePrice}
            icon={Store}
            tone="primary"
            hint={`Costo de lista: ${formatMoney(product.costPrice)}`}
          />
        </div>

        <Card padding="none">
          <CardHeader
            title="Lotes en orden de salida"
            description={
              rotation === 'FIFO'
                ? 'FIFO: primero sale lo que entró antes. Los lotes en liquidación salen antes que el resto.'
                : 'FEFO: primero sale lo que vence antes. Los lotes en liquidación salen antes que el resto.'
            }
            icon={Layers}
            className="px-4 pt-4"
          />
          <Table
            columns={lotColumns}
            data={product.lots}
            rowKey={(lot) => lot.id}
            rowSeverity={(lot) =>
              lot.status === 'RECALLED' && !isWithdrawn(lot)
                ? 'crit'
                : lot.expiryBucket === 'EXPIRED'
                  ? 'crit'
                  : lot.expiryBucket === 'CRITICAL' || lot.expiryBucket === 'WARNING'
                    ? 'warn'
                    : 'none'
            }
            empty={{
              icon: Layers,
              title: neverHadStock ? 'Este producto no tiene lotes cargados' : 'Sin lotes con stock',
              description: neverHadStock
                ? canIntake
                  ? 'Registrá el primer ingreso para empezar a controlar vencimientos.'
                  : 'Cuando se registre el primer ingreso vas a ver acá sus lotes y vencimientos.'
                : `No quedan lotes con stock${isAll ? '' : ' en esta sucursal'}. Lo que pasó con sus lotes (ventas, descartes, retiros) está en Movimientos recientes.${
                    canIntake ? ' Registrá un ingreso para volver a tener stock.' : ''
                  }`,
              action: canIntake ? (
                <ButtonLink to={`/app/intake?productId=${product.id}`} leftIcon={<ScanBarcode className="h-4 w-4" />}>
                  Cargar mercadería
                </ButtonLink>
              ) : undefined,
            }}
          />
        </Card>

        <div className={`grid gap-4 md:grid-cols-2${hasInsight ? ' xl:grid-cols-3' : ''}`}>
          <Card padding="lg">
              <CardHeader title="Datos del producto" />
              <dl className="flex flex-col gap-3 text-base">
                {product.barcode ? (
                  <div>
                    <dt className="gd-eyebrow">Código de barras</dt>
                    <dd className="mt-1">
                      <BarcodeDigits code={product.barcode} width={160} />
                    </dd>
                  </div>
                ) : (
                  <div>
                    <dt className="gd-eyebrow">Código de barras</dt>
                    <dd className="text-muted-foreground">Sin código cargado</dd>
                  </div>
                )}
                <div className="grid grid-cols-2 gap-3 border-t pt-3">
                  <div>
                    <dt className="gd-eyebrow">Unidad</dt>
                    <dd>{unitShort(product.unit)}</dd>
                  </div>
                  <div>
                    <dt className="gd-eyebrow">Stock mínimo</dt>
                    <dd className="tabular-nums">{formatNumber(product.minStock)}</dd>
                  </div>
                  <div>
                    <dt className="gd-eyebrow">Costo</dt>
                    <dd className="tabular-nums">{formatMoney(product.costPrice)}</dd>
                  </div>
                  <div>
                    <dt className="gd-eyebrow">Vence</dt>
                    <dd>{product.perishable ? 'Sí, se controla' : 'No perece'}</dd>
                  </div>
                </div>
                {product.description && (
                  <div className="border-t pt-3">
                    <dt className="gd-eyebrow">Descripción</dt>
                    <dd className="text-muted-foreground">{product.description}</dd>
                  </div>
                )}
                <div className="border-t pt-3 text-sm text-muted-foreground">
                  Creado el {formatDateTime(product.createdAt)} · última edición {formatRelative(product.updatedAt)}
                </div>
              </dl>
            </Card>

            <Card padding="lg">
              <CardHeader title="Stock por sucursal" icon={Store} />
              {product.stockByBranch.length === 0 ? (
                <p className="text-base text-muted-foreground">Todavía no hay stock de este producto.</p>
              ) : (
                <ul className="divide-y">
                  {product.stockByBranch.map((branch) => (
                    <li key={branch.branchId} className="flex items-center justify-between gap-3 py-2.5">
                      <Truncate className="min-w-0 font-medium">{branch.branchName}</Truncate>
                      <span className="flex shrink-0 items-center gap-2">
                        <span className="font-semibold tabular-nums">
                          {formatNumber(branch.sellableStock)} {unitShort(product.unit)}
                        </span>
                        <StockStatusPill status={branch.stockStatus} size="sm" />
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </Card>

            {hasInsight && (
              <Card padding="lg">
                <CardHeader
                  title="Lo que ve la IA"
                  icon={Sparkles}
                  description={lastAnalysis ? `Análisis de ${formatRelative(lastAnalysis)}` : undefined}
                />
                <div className="flex flex-col gap-3">
                  <ul className="flex flex-col gap-3">
                    {insights.map((row) => (
                      <li key={row.branchId} className="flex flex-col gap-2 border-b pb-3 last:border-b-0 last:pb-0">
                        {insights.length > 1 && <div className="gd-eyebrow">{row.branchName}</div>}
                        <div className="flex flex-wrap gap-2">
                          {row.pattern && (
                            <Badge tone="info">{SALES_PATTERN_LABELS[row.pattern] ?? row.pattern}</Badge>
                          )}
                          {row.abcClass && <Badge tone="neutral">Clase {row.abcClass}</Badge>}
                        </div>
                        {row.patternDescription && (
                          <p className="text-read text-foreground">{row.patternDescription}</p>
                        )}
                        {row.avgDailySales != null && row.avgDailySales > 0 && (
                          <p className="text-base text-muted-foreground">
                            Vende {formatNumber(row.avgDailySales, { decimals: 1 })} {unitShort(product.unit)} por día
                            {row.daysOfCover != null
                              ? ` · stock para ${formatNumber(row.daysOfCover, { decimals: 1 })} días`
                              : ''}
                            .
                          </p>
                        )}
                        {row.predictedStockoutDate && (
                          <p className="text-base text-muted-foreground">
                            Se quedaría sin stock cerca del {formatDate(row.predictedStockoutDate)}
                            {row.suggestedOrderQty
                              ? ` · sugiere pedir ${formatNumber(row.suggestedOrderQty)} ${unitShort(product.unit)}`
                              : ''}
                          </p>
                        )}
                      </li>
                    ))}
                  </ul>
                  <ButtonLink to="/app/insights" variant="link" className="h-auto self-start px-0">
                    Ver Inteligencia IA
                  </ButtonLink>
                </div>
              </Card>
            )}
        </div>

        <Card padding="none">
          <CardHeader
            title="Movimientos recientes"
            description="Últimos ingresos, ventas y ajustes de este producto en el alcance elegido."
            icon={History}
            className="px-4 pt-4"
          />
          <Table
            columns={movementColumns}
            data={movementsQuery.data}
            rowKey={(movement) => movement.id}
            loading={movementsQuery.isPending}
            error={movementsQuery.isError ? movementsQuery.error : undefined}
            onRetry={() => void movementsQuery.refetch()}
            dense
            empty={{
              icon: History,
              title: 'Todavía no hay movimientos',
              description: 'Cuando cargues mercadería o registres ventas los vas a ver acá.',
            }}
          />
        </Card>
      </div>

      <ConfirmDialog
        open={confirmDelete}
        onClose={() => setConfirmDelete(false)}
        onConfirm={async () => {
          try {
            await remove.mutateAsync();
          } catch (error) {
            toast.error(getErrorMessage(error));
          } finally {
            setConfirmDelete(false);
          }
        }}
        title={`¿Dar de baja ${product.name}?`}
        description="Si el producto ya tuvo movimientos queda desactivado y sigue en el historial. Si nunca se usó, se elimina del catálogo."
        confirmLabel="Dar de baja"
        tone="danger"
        loading={remove.isPending}
      />
    </>
  );
}
