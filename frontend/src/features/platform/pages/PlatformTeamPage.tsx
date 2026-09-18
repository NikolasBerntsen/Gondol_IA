import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { KeyRound, Pencil, Plus, UserPlus, Users } from 'lucide-react';
import { toast } from 'sonner';
import { getErrorMessage, getFieldErrors } from '@/api/client';
import { PLATFORM_ROLES, ROLE_LABELS, type Role } from '@/api/types';
import { useAuth } from '@/auth/AuthContext';
import { StatusPill } from '@/components/gondola';
import {
  Alert,
  Button,
  Card,
  Field,
  Input,
  Modal,
  PageHeader,
  Select,
  Table,
  Toggle,
  type TableColumn,
} from '@/components/ui';
import { formatDate, formatDateTime, formatRelative } from '@/lib/format';
import { platformApi, platformKeys } from '../api';
import type { PlatformUserDto } from '../types';

type DialogState =
  | { kind: 'create' }
  | { kind: 'edit'; user: PlatformUserDto }
  | { kind: 'reset'; user: PlatformUserDto }
  | null;

/** Equipo de GondolIA: dueños y agentes de soporte (SPEC §6.6). */
export default function PlatformTeamPage() {
  const { me } = useAuth();
  const [dialog, setDialog] = useState<DialogState>(null);

  const team = useQuery({ queryKey: platformKeys.team, queryFn: platformApi.team.list });

  const columns: Array<TableColumn<PlatformUserDto>> = [
    {
      id: 'user',
      header: 'Integrante',
      mobile: 'title',
      cell: (row) => (
        <div className="min-w-0">
          <div className="font-semibold text-foreground">
            {row.fullName}
            {me?.id === row.id ? <span className="text-muted-foreground"> · vos</span> : null}
          </div>
          <div className="truncate text-xs text-muted-foreground">{row.email}</div>
        </div>
      ),
    },
    { id: 'role', header: 'Rol', mobile: 'field', cell: (row) => ROLE_LABELS[row.role] },
    {
      id: 'active',
      header: 'Cuenta',
      mobile: 'aside',
      cell: (row) => (
        <StatusPill tone={row.active ? 'ok' : 'crit'} solid={!row.active}>
          {row.active ? 'Activa' : 'Inactiva'}
        </StatusPill>
      ),
    },
    {
      id: 'lastLogin',
      header: 'Último ingreso',
      mobile: 'field',
      mobileLabel: 'Último ingreso',
      cell: (row) =>
        row.lastLoginAt ? (
          <span title={formatDateTime(row.lastLoginAt)}>{formatRelative(row.lastLoginAt)}</span>
        ) : (
          <span className="text-muted-foreground">Nunca entró</span>
        ),
    },
    {
      id: 'createdAt',
      header: 'Alta',
      hideBelow: 'lg',
      mobile: 'field',
      mobileLabel: 'Alta',
      align: 'right',
      className: 'text-right tabular-nums',
      cell: (row) => formatDate(row.createdAt),
    },
    {
      id: 'actions',
      header: <span className="sr-only">Acciones</span>,
      align: 'right',
      mobile: 'actions',
      cell: (row) => (
        <div className="flex justify-end gap-1">
          <Button
            variant="ghost"
            size="icon-sm"
            aria-label={`Editar a ${row.fullName}`}
            onClick={() => setDialog({ kind: 'edit', user: row })}
          >
            <Pencil className="h-4 w-4" aria-hidden="true" />
          </Button>
          <Button
            variant="ghost"
            size="icon-sm"
            aria-label={`Restablecer la contraseña de ${row.fullName}`}
            onClick={() => setDialog({ kind: 'reset', user: row })}
          >
            <KeyRound className="h-4 w-4" aria-hidden="true" />
          </Button>
        </div>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        eyebrow="Consola de dueños"
        title="Equipo de GondolIA"
        icon={Users}
        description="Las cuentas de dueños y de soporte que atienden a los clientes."
        actions={
          <Button leftIcon={<Plus />} onClick={() => setDialog({ kind: 'create' })}>
            Sumar a alguien
          </Button>
        }
      />

      <Card padding="none">
        <Table
          columns={columns}
          data={team.data}
          rowKey={(row) => row.id}
          loading={team.isPending}
          error={team.isError ? team.error : undefined}
          onRetry={() => void team.refetch()}
          rowSeverity={(row) => (row.active ? 'none' : 'warn')}
          caption="Equipo de GondolIA"
          empty={{
            icon: UserPlus,
            title: 'Todavía no hay nadie más en el equipo',
            description: 'Sumá a quien atienda soporte o administre la plataforma.',
            action: (
              <Button leftIcon={<Plus />} onClick={() => setDialog({ kind: 'create' })}>
                Sumar a alguien
              </Button>
            ),
          }}
        />
      </Card>

      {dialog?.kind === 'create' ? <CreateMemberModal onClose={() => setDialog(null)} /> : null}
      {dialog?.kind === 'edit' ? (
        <EditMemberModal user={dialog.user} isSelf={me?.id === dialog.user.id} onClose={() => setDialog(null)} />
      ) : null}
      {dialog?.kind === 'reset' ? <ResetMemberModal user={dialog.user} onClose={() => setDialog(null)} /> : null}
    </>
  );
}

function useTeamInvalidate() {
  const queryClient = useQueryClient();
  return () => void queryClient.invalidateQueries({ queryKey: platformKeys.team });
}

function CreateMemberModal({ onClose }: { onClose: () => void }) {
  const invalidate = useTeamInvalidate();
  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [role, setRole] = useState<Role>('SUPPORT_AGENT');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string>();

  const create = useMutation({
    mutationFn: () => platformApi.team.create({ fullName: fullName.trim(), email: email.trim(), password, role }),
    onSuccess: (user) => {
      toast.success(`Sumaste a ${user.fullName} al equipo.`);
      invalidate();
      onClose();
    },
    onError: (error) => {
      const fieldErrors = getFieldErrors(error);
      setErrors(fieldErrors);
      setFormError(Object.keys(fieldErrors).length ? undefined : getErrorMessage(error, 'No pudimos crear la cuenta.'));
    },
    meta: { errorToast: false },
  });

  return (
    <Modal
      open
      onClose={onClose}
      size="sm"
      title="Sumar a alguien al equipo"
      description="Va a poder entrar con este email y cambiar la contraseña desde su perfil."
      footer={
        <>
          <Button variant="outline" onClick={onClose} disabled={create.isPending}>
            Cancelar
          </Button>
          <Button
            onClick={() => {
              setErrors({});
              setFormError(undefined);
              create.mutate();
            }}
            loading={create.isPending}
          >
            Crear la cuenta
          </Button>
        </>
      }
    >
      <div className="space-y-3">
        <Field label="Nombre y apellido" error={errors.fullName}>
          <Input value={fullName} onChange={(event) => setFullName(event.target.value)} maxLength={150} />
        </Field>
        <Field label="Email" error={errors.email}>
          <Input type="email" value={email} onChange={(event) => setEmail(event.target.value)} maxLength={150} />
        </Field>
        <Field label="Contraseña inicial" error={errors.password} hint="Mínimo 8 caracteres.">
          <Input
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            autoComplete="new-password"
            className="font-mono"
            maxLength={72}
          />
        </Field>
        <Field label="Rol" error={errors.role}>
          <Select
            value={role}
            onChange={(event) => setRole(event.target.value as Role)}
            options={PLATFORM_ROLES.map((value) => ({ value, label: ROLE_LABELS[value] }))}
          />
        </Field>
        {formError ? <Alert tone="crit">{formError}</Alert> : null}
      </div>
    </Modal>
  );
}

function EditMemberModal({
  user,
  isSelf,
  onClose,
}: {
  user: PlatformUserDto;
  isSelf: boolean;
  onClose: () => void;
}) {
  const invalidate = useTeamInvalidate();
  const [fullName, setFullName] = useState(user.fullName);
  const [active, setActive] = useState(user.active);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string>();

  const update = useMutation({
    mutationFn: () => platformApi.team.update(user.id, { fullName: fullName.trim(), active }),
    onSuccess: () => {
      toast.success('Guardaste los cambios.');
      invalidate();
      onClose();
    },
    onError: (error) => {
      const fieldErrors = getFieldErrors(error);
      setErrors(fieldErrors);
      setFormError(Object.keys(fieldErrors).length ? undefined : getErrorMessage(error, 'No pudimos guardar.'));
    },
    meta: { errorToast: false },
  });

  return (
    <Modal
      open
      onClose={onClose}
      size="sm"
      title={`Editar a ${user.fullName}`}
      description={`${ROLE_LABELS[user.role]} · ${user.email}`}
      footer={
        <>
          <Button variant="outline" onClick={onClose} disabled={update.isPending}>
            Cancelar
          </Button>
          <Button
            onClick={() => {
              setErrors({});
              setFormError(undefined);
              update.mutate();
            }}
            loading={update.isPending}
          >
            Guardar los cambios
          </Button>
        </>
      }
    >
      <div className="space-y-3">
        <Field label="Nombre y apellido" error={errors.fullName}>
          <Input value={fullName} onChange={(event) => setFullName(event.target.value)} maxLength={150} />
        </Field>
        <Toggle
          checked={active}
          onChange={setActive}
          disabled={isSelf}
          label="Cuenta activa"
          description={
            isSelf
              ? 'No podés desactivar tu propia cuenta.'
              : 'Si la desactivás no va a poder entrar y se cierran sus sesiones.'
          }
        />
        {formError ? <Alert tone="crit">{formError}</Alert> : null}
      </div>
    </Modal>
  );
}

function ResetMemberModal({ user, onClose }: { user: PlatformUserDto; onClose: () => void }) {
  const invalidate = useTeamInvalidate();
  const [newPassword, setNewPassword] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string>();

  const reset = useMutation({
    mutationFn: () => platformApi.team.resetPassword(user.id, newPassword),
    onSuccess: () => {
      toast.success('Restableciste la contraseña.');
      invalidate();
      onClose();
    },
    onError: (error) => {
      const fieldErrors = getFieldErrors(error);
      setErrors(fieldErrors);
      setFormError(
        Object.keys(fieldErrors).length ? undefined : getErrorMessage(error, 'No pudimos restablecer la contraseña.'),
      );
    },
    meta: { errorToast: false },
  });

  return (
    <Modal
      open
      onClose={onClose}
      size="sm"
      title={`Restablecer la contraseña de ${user.fullName}`}
      description="Se cierran sus sesiones abiertas y tiene que cambiarla al entrar."
      footer={
        <>
          <Button variant="outline" onClick={onClose} disabled={reset.isPending}>
            Cancelar
          </Button>
          <Button
            onClick={() => {
              setErrors({});
              setFormError(undefined);
              reset.mutate();
            }}
            loading={reset.isPending}
            leftIcon={<KeyRound />}
          >
            Restablecer
          </Button>
        </>
      }
    >
      <div className="space-y-3">
        <Field label="Nueva contraseña" error={errors.newPassword} hint="Entre 8 y 72 caracteres.">
          <Input
            value={newPassword}
            onChange={(event) => setNewPassword(event.target.value)}
            autoComplete="new-password"
            className="font-mono"
            maxLength={72}
          />
        </Field>
        {formError ? <Alert tone="crit">{formError}</Alert> : null}
      </div>
    </Modal>
  );
}
