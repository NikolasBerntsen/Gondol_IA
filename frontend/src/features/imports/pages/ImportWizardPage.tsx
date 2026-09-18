import { useEffect, useMemo, useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { ArrowLeft, ArrowRight, Download, FileSpreadsheet, X } from 'lucide-react';
import {
  Button,
  Card,
  Checkbox,
  ConfirmDialog,
  EmptyState,
  ErrorState,
  PageHeader,
  PageSpinner,
  WizardSteps,
} from '@/components/ui';
import { StatusPill } from '@/components/gondola';
import { getErrorMessage, isApiError } from '@/api/client';
import { cn } from '@/lib/cn';
import { formatNumber } from '@/lib/format';
import { importKeys, importsApi } from '../api';
import { ConfirmStep } from '../components/ConfirmStep';
import { FileStep } from '../components/FileStep';
import { MappingStep, type MappingDraft } from '../components/MappingStep';
import { ResultStep } from '../components/ResultStep';
import { ReviewStep } from '../components/ReviewStep';
import { IMPORT_STATUS_LABELS, IMPORT_STATUS_TONES, WIZARD_STEPS } from '../labels';
import type { ImportJob } from '../types';

/** Pasos del asistente. */
const FILE = 0;
const COLUMNS = 1;
const REVIEW = 2;
const CONFIRM = 3;
const RESULT = 4;

/** Ruta para empezar una importación sin archivo todavía (`/app/imports/nueva`). */
const NEW_ID = 'nueva';

interface LocationState {
  /** Archivo original: permite cambiar de hoja sin volver a elegirlo. */
  file?: File;
}

function draftFrom(job: ImportJob): MappingDraft {
  const { defaultBranchName: _ignored, ...options } = job.options;
  void _ignored;
  return { columnMapping: { ...job.columnMapping }, options };
}

/** Paso en el que conviene abrir la importación según su estado. */
function initialStep(job: ImportJob): number {
  switch (job.status) {
    case 'UPLOADED':
      return job.sheetNames.length > 1 ? FILE : COLUMNS;
    case 'VALIDATED':
      return REVIEW;
    case 'APPLYING':
      return CONFIRM;
    default:
      return RESULT;
  }
}

/** `/app/imports/:id` — asistente de importación en 5 pasos (SPEC §16.4). */
export default function ImportWizardPage() {
  const { id = NEW_ID } = useParams();
  const isNew = id === NEW_ID;
  const jobId = isNew ? NaN : Number(id);
  const navigate = useNavigate();
  const location = useLocation();
  const queryClient = useQueryClient();
  const originalFile = (location.state as LocationState | null)?.file;

  const [step, setStep] = useState<number>(FILE);
  const [draft, setDraft] = useState<MappingDraft | null>(null);
  const [skipErrors, setSkipErrors] = useState(false);
  const [uploadProgress, setUploadProgress] = useState(0);
  const [confirmCancel, setConfirmCancel] = useState(false);
  const [readOnlyReview, setReadOnlyReview] = useState(false);

  const jobQuery = useQuery({
    queryKey: importKeys.detail(jobId),
    queryFn: () => importsApi.get(jobId),
    enabled: !isNew && Number.isFinite(jobId),
    refetchInterval: (query) => (query.state.data?.status === 'APPLYING' ? 1_000 : false),
  });
  const fieldsQuery = useQuery({
    queryKey: importKeys.fields(),
    queryFn: importsApi.fields,
    staleTime: Infinity,
  });
  const job = jobQuery.data ?? null;

  // Al cargar otra importación (o al volver a esta página) arrancamos en el paso que corresponde a su estado.
  useEffect(() => {
    if (!job) {
      setStep(FILE);
      return;
    }
    setDraft(draftFrom(job));
    setStep(initialStep(job));
    setReadOnlyReview(false);
    // Solo cuando cambia la importación, no en cada refetch.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [job?.id]);

  // Cuando termina de aplicarse, pasamos solos al resultado.
  useEffect(() => {
    if (job && (job.status === 'APPLIED' || job.status === 'FAILED' || job.status === 'CANCELLED') && step === CONFIRM) {
      setStep(RESULT);
      queryClient.invalidateQueries({ queryKey: ['imports', 'list'] });
      if (job.status === 'APPLIED') toast.success('Importación terminada.');
    }
  }, [job, step, queryClient]);

  const setJob = (updated: ImportJob) => queryClient.setQueryData(importKeys.detail(updated.id), updated);

  // -------------------------------------------------------------------------
  // Mutaciones
  // -------------------------------------------------------------------------

  const upload = useMutation({
    mutationFn: ({ file, sheetName }: { file: File; sheetName?: string }) =>
      importsApi.upload(file, sheetName, setUploadProgress),
    onMutate: () => setUploadProgress(0),
    onSuccess: async (created, { file }) => {
      // El archivo anterior queda cancelado para no ensuciar el historial.
      if (job && job.id !== created.id && !['APPLIED', 'APPLYING'].includes(job.status)) {
        await importsApi.cancel(job.id).catch(() => undefined);
      }
      queryClient.setQueryData(importKeys.detail(created.id), created);
      queryClient.invalidateQueries({ queryKey: ['imports', 'list'] });
      toast.success(`Leímos ${formatNumber(created.totalRows)} filas de ${created.fileName}.`);
      navigate(`/app/imports/${created.id}`, { replace: true, state: { file } satisfies LocationState });
    },
    meta: { errorToast: false },
  });

  const saveMapping = useMutation({
    mutationFn: (value: MappingDraft) =>
      importsApi.saveMapping(jobId, { columnMapping: value.columnMapping, options: value.options }),
    onSuccess: (updated) => {
      setJob(updated);
      queryClient.invalidateQueries({ queryKey: ['imports', 'rows', updated.id] });
      setSkipErrors(false);
      setStep(REVIEW);
      toast.success(`Revisamos ${formatNumber(updated.totalRows)} filas.`, {
        description:
          updated.errorRows > 0
            ? `${formatNumber(updated.errorRows)} con error: corregilas en la grilla u omitilas.`
            : 'No hay errores: podés continuar a confirmar.',
      });
    },
    meta: { errorToast: false },
  });

  const apply = useMutation({
    mutationFn: () => importsApi.apply(jobId, skipErrors),
    onSuccess: (updated) => {
      setJob(updated);
      setStep(CONFIRM);
    },
    onError: (error) => {
      toast.error(getErrorMessage(error));
      if (isApiError(error, 'IMPORT_HAS_ERRORS')) setStep(REVIEW);
    },
  });

  const cancel = useMutation({
    mutationFn: () => importsApi.cancel(jobId),
    onSuccess: (updated) => {
      setJob(updated);
      queryClient.invalidateQueries({ queryKey: ['imports', 'list'] });
      setConfirmCancel(false);
      toast.success(updated.status === 'CANCELLED' ? 'Cancelaste la importación.' : 'Pediste cortar la importación.');
      if (updated.status === 'CANCELLED') navigate('/app/imports');
    },
  });

  // -------------------------------------------------------------------------
  // Derivados
  // -------------------------------------------------------------------------

  const status = job?.status;
  const finished = status === 'APPLIED' || status === 'FAILED' || status === 'CANCELLED';
  const applying = status === 'APPLYING' || apply.isPending;
  const maxStep = !job
    ? FILE
    : finished
      ? RESULT
      : status === 'APPLYING'
        ? CONFIRM
        : status === 'VALIDATED'
          ? CONFIRM
          : COLUMNS;
  const importable = job ? job.validRows + job.warningRows : 0;
  const blocked = !!job && job.errorRows > 0 && !skipErrors;
  const mappingChanged = useMemo(() => {
    if (!job || !draft) return false;
    return JSON.stringify(draftFrom(job)) !== JSON.stringify(draft);
  }, [job, draft]);

  // -------------------------------------------------------------------------
  // Render
  // -------------------------------------------------------------------------

  if (!isNew && !Number.isFinite(jobId)) {
    return (
      <EmptyState
        icon={FileSpreadsheet}
        title="No encontramos esa importación"
        description="Volvé al historial y elegí una importación o empezá una nueva."
        action={<Button onClick={() => navigate('/app/imports')}>Ir a importaciones</Button>}
        bordered
      />
    );
  }
  if (!isNew && jobQuery.isPending) return <PageSpinner label="Cargando la importación…" />;
  if (!isNew && jobQuery.isError) {
    return (
      <>
        <PageHeader title="Importar Excel/CSV" back={{ to: '/app/imports', label: 'Importaciones' }} />
        <ErrorState error={jobQuery.error} onRetry={() => jobQuery.refetch()} />
      </>
    );
  }

  const fields = fieldsQuery.data ?? [];
  const description =
    step === FILE
      ? 'Subí tu planilla de Excel o CSV. Todavía no se modifica nada del inventario.'
      : step === COLUMNS
        ? 'Decinos qué columna de tu archivo corresponde a cada dato de GondolIA.'
        : step === REVIEW
          ? readOnlyReview
            ? 'Así quedaron las filas de esta importación.'
            : 'Revisá las filas antes de importar. Tocá una celda para corregirla: los contadores se actualizan al instante.'
          : step === CONFIRM
            ? 'Revisá qué va a pasar y confirmá la importación.'
            : 'La importación terminó.';

  return (
    <div className="flex min-h-full flex-col gap-5">
      <PageHeader
        eyebrow={`Importaciones · ${job ? `#${job.id}` : 'Nueva importación'}`}
        title="Importar Excel/CSV"
        description={description}
        back={{ to: '/app/imports', label: 'Importaciones' }}
        actions={
          job ? (
            <div className="flex min-w-0 max-w-full flex-wrap items-center gap-2">
              <div className="flex h-10 min-w-0 max-w-full items-center gap-2.5 rounded-control border border-border bg-card px-3">
                <FileSpreadsheet className="h-4 w-4 shrink-0 text-ok" aria-hidden="true" />
                <span className="min-w-0 truncate text-sm">
                  <span className="font-semibold text-foreground">{job.fileName}</span>
                  <span className="text-muted-foreground">
                    {job.sheetName ? ` · hoja ${job.sheetName}` : ''} · {formatNumber(job.totalRows)} filas
                  </span>
                </span>
              </div>
              <StatusPill tone={IMPORT_STATUS_TONES[job.status]}>{IMPORT_STATUS_LABELS[job.status]}</StatusPill>
              {!finished ? (
                <Button
                  variant="ghost"
                  size="sm"
                  leftIcon={<X aria-hidden="true" />}
                  onClick={() => setConfirmCancel(true)}
                >
                  {status === 'APPLYING' ? 'Cortar' : 'Cancelar'}
                </Button>
              ) : null}
            </div>
          ) : null
        }
      />

      <Card padding="none" className="px-4 py-3 sm:px-5">
        <WizardSteps steps={WIZARD_STEPS} current={step} />
      </Card>

      {step === FILE ? (
        <FileStep
          job={job}
          busy={upload.isPending}
          progress={uploadProgress}
          canSwitchSheet={Boolean(originalFile)}
          onFile={(file) => upload.mutate({ file })}
          onSheet={(sheetName) => originalFile && upload.mutate({ file: originalFile, sheetName })}
          error={upload.isError ? getErrorMessage(upload.error) : null}
        />
      ) : null}

      {step === COLUMNS && job && draft ? (
        fieldsQuery.isPending ? (
          <PageSpinner label="Cargando los campos…" />
        ) : fieldsQuery.isError ? (
          <ErrorState error={fieldsQuery.error} onRetry={() => fieldsQuery.refetch()} />
        ) : (
          <MappingStep
            job={job}
            fields={fields}
            draft={draft}
            onChange={setDraft}
            error={saveMapping.isError ? getErrorMessage(saveMapping.error) : null}
          />
        )
      ) : null}

      {step === REVIEW && job ? (
        fieldsQuery.isPending ? (
          <PageSpinner label="Cargando los campos…" />
        ) : (
          <ReviewStep job={job} fields={fields} onJobChange={setJob} readOnly={readOnlyReview || finished} />
        )
      ) : null}

      {step === CONFIRM && job ? <ConfirmStep job={job} applying={applying} /> : null}

      {step === RESULT && job ? (
        <ResultStep
          job={job}
          onReview={() => {
            setReadOnlyReview(true);
            setStep(REVIEW);
          }}
        />
      ) : null}

      {/* Barra de acciones fija */}
      <div
        className={cn(
          'sticky bottom-0 z-10 -mx-4 mt-auto flex flex-wrap items-center gap-x-4 gap-y-3 border-t border-border bg-card px-4 py-3 pr-20 sm:-mx-6 sm:px-6 sm:pr-6 lg:-mx-8 lg:px-8',
          step === RESULT && 'hidden',
        )}
        style={{ paddingBottom: 'calc(12px + env(safe-area-inset-bottom, 0px))' }}
      >
        {step === FILE ? (
          <>
            <span className="text-base text-muted-foreground">
              {job ? 'Archivo listo. Seguí con las columnas.' : 'Elegí un archivo para empezar.'}
            </span>
            <div className="flex-1" />
            <Button disabled={!job || upload.isPending} rightIcon={<ArrowRight aria-hidden="true" />} onClick={() => setStep(COLUMNS)}>
              Continuar a columnas
            </Button>
          </>
        ) : step === COLUMNS ? (
          <>
            <Button variant="outline" leftIcon={<ArrowLeft aria-hidden="true" />} onClick={() => setStep(FILE)}>
              <span className="hidden sm:inline">Volver al archivo</span>
              <span className="sm:hidden">Volver</span>
            </Button>
            <div className="flex-1" />
            {status === 'VALIDATED' && !mappingChanged ? (
              <Button variant="outline" onClick={() => setStep(REVIEW)}>
                Ir a la revisión
              </Button>
            ) : null}
            <Button
              loading={saveMapping.isPending}
              disabled={!draft?.columnMapping.name}
              rightIcon={<ArrowRight aria-hidden="true" />}
              onClick={() => draft && saveMapping.mutate(draft)}
            >
              {status === 'VALIDATED' ? 'Volver a validar' : 'Validar filas'}
            </Button>
          </>
        ) : step === REVIEW ? (
          readOnlyReview || finished ? (
            <>
              <span className="text-base text-muted-foreground">Vista de solo lectura.</span>
              <div className="flex-1" />
              <Button variant="outline" leftIcon={<ArrowLeft aria-hidden="true" />} onClick={() => setStep(RESULT)}>
                Volver al resultado
              </Button>
            </>
          ) : (
            <>
              <label
                htmlFor="skip-errors"
                className={cn('flex items-center gap-2 text-base', job?.errorRows === 0 && 'opacity-50')}
              >
                <Checkbox
                  id="skip-errors"
                  checked={skipErrors}
                  disabled={job?.errorRows === 0}
                  onCheckedChange={(checked) => setSkipErrors(checked === true)}
                />
                Omitir las filas con error ({formatNumber(job?.errorRows ?? 0)})
              </label>
              <Button
                variant="ghost"
                size="sm"
                leftIcon={<Download aria-hidden="true" />}
                disabled={!job || job.errorRows + job.warningRows === 0}
                onClick={() =>
                  job && importsApi.downloadErrors(job.id).catch((error) => toast.error(getErrorMessage(error)))
                }
              >
                <span className="hidden sm:inline">Descargar errores (CSV)</span>
                <span className="sm:hidden">Errores (CSV)</span>
              </Button>
              <div className="flex-1" />
              {blocked ? (
                <span id="continue-hint" className="text-sm text-crit-ink">
                  Corregí {job?.errorRows === 1 ? 'el error' : `los ${formatNumber(job?.errorRows ?? 0)} errores`} u
                  omitilos para continuar.
                </span>
              ) : null}
              <Button variant="outline" leftIcon={<ArrowLeft aria-hidden="true" />} onClick={() => setStep(COLUMNS)}>
                <span className="hidden sm:inline">Volver a columnas</span>
                <span className="sm:hidden">Volver</span>
              </Button>
              <Button
                disabled={blocked || importable === 0 || maxStep < CONFIRM}
                aria-describedby={blocked ? 'continue-hint' : undefined}
                rightIcon={<ArrowRight aria-hidden="true" />}
                onClick={() => setStep(CONFIRM)}
              >
                Continuar a confirmar
              </Button>
            </>
          )
        ) : step === CONFIRM ? (
          <>
            <span className="text-base text-muted-foreground">
              {applying ? (
                'Estamos cargando el inventario…'
              ) : (
                <>
                  Se van a importar{' '}
                  <strong className="tabular-nums text-foreground">
                    {formatNumber(importable)}
                  </strong>{' '}
                  filas{skipErrors && job?.errorRows ? ` y se omiten ${formatNumber(job.errorRows)} con error` : ''}.
                </>
              )}
            </span>
            <div className="flex-1" />
            <Button
              variant="outline"
              leftIcon={<ArrowLeft aria-hidden="true" />}
              disabled={applying}
              onClick={() => setStep(REVIEW)}
            >
              Volver a revisión
            </Button>
            <Button loading={applying} disabled={importable === 0 || blocked} onClick={() => apply.mutate()}>
              Importar {formatNumber(importable)} filas
            </Button>
          </>
        ) : null}
      </div>

      <ConfirmDialog
        open={confirmCancel}
        onClose={() => setConfirmCancel(false)}
        onConfirm={() => cancel.mutateAsync()}
        loading={cancel.isPending}
        tone="danger"
        title={status === 'APPLYING' ? '¿Cortar la importación?' : '¿Cancelar la importación?'}
        description={
          status === 'APPLYING'
            ? 'Las filas que ya se aplicaron quedan cargadas; el resto no se importa.'
            : 'No se va a cargar nada de este archivo. Podés volver a subirlo cuando quieras.'
        }
        confirmLabel={status === 'APPLYING' ? 'Cortar importación' : 'Cancelar importación'}
        cancelLabel="Seguir"
      />
    </div>
  );
}
