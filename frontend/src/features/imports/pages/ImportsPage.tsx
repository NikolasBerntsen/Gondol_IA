import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { keepPreviousData } from '@tanstack/react-query';
import { toast } from 'sonner';
import { ChevronDown, Download, FileSpreadsheet, History, Upload } from 'lucide-react';
import {
  Button,
  Card,
  CardHeader,
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
  EmptyState,
  ErrorState,
  Pagination,
  Skeleton,
  Table,
  pageInfo,
  type TableColumn,
} from '@/components/ui';
import { StatusPill } from '@/components/gondola';
import { useBranch } from '@/branches/BranchContext';
import { getErrorMessage } from '@/api/client';
import { formatDateTime, formatNumber } from '@/lib/format';
import { importKeys, importsApi } from '../api';
import { FileDropzone } from '../components/FileDropzone';
import { FILE_FORMAT_LABELS, IMPORT_STATUS_LABELS, IMPORT_STATUS_TONES } from '../labels';
import type { ImportJobSummary } from '../types';

const PAGE_SIZE = 10;

/** `/app/imports` — historial de importaciones, nueva importación, plantilla y exportación (SPEC §16.4). */
export default function ImportsPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { scopeLabel } = useBranch();
  const [page, setPage] = useState(0);
  const [progress, setProgress] = useState(0);

  const history = useQuery({
    queryKey: importKeys.list({ page, size: PAGE_SIZE }),
    queryFn: () => importsApi.list({ page, size: PAGE_SIZE }),
    placeholderData: keepPreviousData,
  });

  const upload = useMutation({
    mutationFn: (file: File) => importsApi.upload(file, undefined, setProgress),
    onMutate: () => setProgress(0),
    onSuccess: (job, file) => {
      queryClient.invalidateQueries({ queryKey: importKeys.all });
      queryClient.setQueryData(importKeys.detail(job.id), job);
      toast.success(`Leímos ${formatNumber(job.totalRows)} filas de ${job.fileName}.`);
      // El archivo viaja en el estado de la navegación para poder cambiar de hoja sin volver a elegirlo.
      navigate(`/app/imports/${job.id}`, { state: { file } });
    },
    meta: { errorToast: false },
    onSettled: () => setProgress(0),
  });

  const download = useMutation({
    mutationFn: (action: () => Promise<void>) => action(),
    onError: (error) => toast.error(getErrorMessage(error)),
  });

  const columns: Array<TableColumn<ImportJobSummary>> = [
    {
      id: 'file',
      header: 'Archivo',
      mobile: 'title',
      cell: (job) => (
        <div className="flex min-w-0 items-center gap-2">
          <FileSpreadsheet className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden="true" />
          <div className="min-w-0">
            <p className="truncate font-medium text-foreground">{job.fileName}</p>
            <p className="text-sm text-muted-foreground">{FILE_FORMAT_LABELS[job.fileFormat]}</p>
          </div>
        </div>
      ),
    },
    {
      id: 'status',
      header: 'Estado',
      mobile: 'aside',
      cell: (job) => (
        <div className="flex flex-col items-start gap-1">
          <StatusPill tone={IMPORT_STATUS_TONES[job.status]}>{IMPORT_STATUS_LABELS[job.status]}</StatusPill>
          {job.status === 'APPLYING' ? (
            <span className="text-sm tabular-nums text-muted-foreground">{job.progressPct}%</span>
          ) : null}
        </div>
      ),
    },
    {
      id: 'rows',
      header: 'Filas',
      align: 'right',
      mobile: 'field',
      mobileLabel: 'Filas',
      cell: (job) => <span className="tabular-nums">{formatNumber(job.totalRows)}</span>,
    },
    {
      id: 'result',
      header: 'Resultado',
      mobile: 'field',
      mobileLabel: 'Resultado',
      cell: (job) =>
        job.result ? (
          <span className="text-base text-muted-foreground">
            <span className="tabular-nums text-foreground">{formatNumber(job.result.productsCreated)}</span> altas ·{' '}
            <span className="tabular-nums text-foreground">{formatNumber(job.result.productsUpdated)}</span>{' '}
            actualizados ·{' '}
            <span className="tabular-nums text-foreground">{formatNumber(job.result.lotsCreated)}</span> lotes
          </span>
        ) : job.errorRows > 0 ? (
          <span className="text-base text-crit-ink">
            {formatNumber(job.errorRows)} {job.errorRows === 1 ? 'fila con error' : 'filas con error'}
          </span>
        ) : (
          <span className="text-muted-foreground">—</span>
        ),
    },
    {
      id: 'createdAt',
      header: 'Fecha',
      hideBelow: 'lg',
      cell: (job) => (
        <div>
          <p className="whitespace-nowrap">{formatDateTime(job.createdAt)}</p>
          <p className="text-sm text-muted-foreground">{job.createdByName ?? 'Sin dato'}</p>
        </div>
      ),
    },
    {
      id: 'go',
      header: '',
      align: 'right',
      mobile: 'actions',
      cell: (job) => (
        <Button variant="ghost" size="sm" onClick={() => navigate(`/app/imports/${job.id}`)}>
          {job.status === 'APPLIED' || job.status === 'CANCELLED' ? 'Ver detalle' : 'Continuar'}
        </Button>
      ),
    },
  ];

  return (
    <>
      <div className="flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <p className="gd-eyebrow">Inventario</p>
          <h1 className="font-display text-xl font-semibold text-foreground">Importar Excel/CSV</h1>
          <p className="mt-1 text-read text-muted-foreground">
            Traé tu planilla de productos y stock, revisá fila por fila y confirmá. También podés exportar el
            catálogo actual, editarlo en Excel y volver a subirlo.
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="outline" leftIcon={<Download aria-hidden="true" />} rightIcon={<ChevronDown aria-hidden="true" />}>
                Descargar plantilla
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              <DropdownMenuItem onSelect={() => download.mutate(() => importsApi.downloadTemplate('xlsx'))}>
                Plantilla Excel (.xlsx)
              </DropdownMenuItem>
              <DropdownMenuItem onSelect={() => download.mutate(() => importsApi.downloadTemplate('csv'))}>
                Plantilla CSV
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="outline" leftIcon={<Upload aria-hidden="true" />} rightIcon={<ChevronDown aria-hidden="true" />}>
                Exportar catálogo
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end">
              <DropdownMenuItem onSelect={() => download.mutate(() => importsApi.downloadCatalog('xlsx', true))}>
                Excel con stock por lote
              </DropdownMenuItem>
              <DropdownMenuItem onSelect={() => download.mutate(() => importsApi.downloadCatalog('csv', true))}>
                CSV con stock por lote
              </DropdownMenuItem>
              <DropdownMenuItem onSelect={() => download.mutate(() => importsApi.downloadCatalog('xlsx', false))}>
                Excel solo con los productos
              </DropdownMenuItem>
              <DropdownMenuSeparator />
              <p className="max-w-64 px-2 py-1.5 text-sm text-muted-foreground">
                Para cambiar solo precios o datos, exportá «solo con los productos»: cada fila con cantidad que
                reimportes suma un lote nuevo.
              </p>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </div>

      <Card className="mt-5" padding="lg">
        <CardHeader
          title="Nueva importación"
          description="Una fila por producto. Si el mismo código aparece en varias filas con distinto lote o vencimiento, se carga un lote por fila."
          icon={Upload}
        />
        <FileDropzone
          className="mt-4"
          busy={upload.isPending}
          progress={progress}
          onFile={(file) => upload.mutate(file)}
        />
        {upload.isError ? (
          <p role="alert" className="mt-2 text-base text-crit-ink">
            {getErrorMessage(upload.error)}
          </p>
        ) : null}
        <p className="mt-3 text-base text-muted-foreground">
          Sucursal elegida arriba: <strong className="font-medium text-foreground">{scopeLabel}</strong>. En el paso
          «Columnas» definís a qué sucursal entra el stock, o usás la columna «Sucursal» del archivo.
        </p>
      </Card>

      <section className="mt-6">
        <h2 className="flex items-center gap-2 font-display text-md font-semibold text-foreground">
          <History className="h-4 w-4 text-muted-foreground" aria-hidden="true" />
          Historial de importaciones
        </h2>
        <div className="mt-3">
          {history.isPending ? (
            <div className="space-y-2">
              <Skeleton className="h-10 w-full" />
              <Skeleton className="h-10 w-full" />
              <Skeleton className="h-10 w-full" />
            </div>
          ) : history.isError ? (
            <ErrorState error={history.error} onRetry={() => history.refetch()} />
          ) : history.data.content.length === 0 ? (
            <EmptyState
              icon={FileSpreadsheet}
              title="Todavía no importaste ninguna planilla"
              description="Subí tu Excel o CSV acá arriba: te mostramos qué se va a crear antes de tocar el inventario."
              bordered
            />
          ) : (
            <>
              <Table
                columns={columns}
                data={history.data.content}
                rowKey={(job) => job.id}
                onRowClick={(job) => navigate(`/app/imports/${job.id}`)}
                mobileLayout="cards"
              />
              <Pagination {...pageInfo(history.data)} onPageChange={setPage} />
            </>
          )}
        </div>
      </section>
    </>
  );
}
