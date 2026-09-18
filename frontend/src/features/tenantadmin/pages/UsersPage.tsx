import { useQuery } from '@tanstack/react-query';
import { Building2, KeyRound, MoreVertical, PencilLine, ShieldCheck, UserCog, UserPlus, Users } from 'lucide-react';
import { useMemo, useState } from 'react';
import { ROLE_LABELS, type Role } from '@/api/types';
import { useCurrentUser } from '@/auth/AuthContext';
import { StatusPill } from '@/components/gondola';
import {
  Badge,
  Button,
  Card,
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
  PageHeader,
  SearchInput,
  Segmented,
  Table,
  type BadgeTone,
  type TableColumn,
} from '@/components/ui';
import { formatRelative } from '@/lib/format';
import { branchesApi, tenantAdminKeys, usersApi } from '../api';
import { ResetPasswordDialog } from '../components/ResetPasswordDialog';
import { UserFormDialog } from '../components/UserFormDialog';
import { worksInAssignedBranches, type TenantUser } from '../types';

type StatusFilter = 'ALL' | 'ACTIVE' | 'INACTIVE';

const ROLE_TONES: Record<Role, BadgeTone> = {
  TENANT_ADMIN: 'primary',
  TENANT_BOSS: 'info',
  TENANT_EMPLOYEE: 'neutral',
  TENANT_CASHIER: 'neutral',
  PLATFORM_OWNER: 'neutral',
  SUPPORT_AGENT: 'neutral',
};

export default function UsersPage() {
  const me = useCurrentUser();
  const [status, setStatus] = useState<StatusFilter>('ALL');
  const [search, setSearch] = useState('');
  const [editing, setEditing] = useState<TenantUser | null>(null);
  const [formOpen, setFormOpen] = useState(false);
  const [resetting, setResetting] = useState<TenantUser | null>(null);

  const usersQuery = useQuery({ queryKey: tenantAdminKeys.usersList(), queryFn: usersApi.list });
  const branchesQuery = useQuery({
    queryKey: tenantAdminKeys.branchesList(false),
    queryFn: () => branchesApi.list(false),
  });

  const users = useMemo(() => usersQuery.data ?? [], [usersQuery.data]);
  const counts = useMemo(
    () => ({
      ALL: users.length,
      ACTIVE: users.filter((user) => user.active).length,
      INACTIVE: users.filter((user) => !user.active).length,
    }),
    [users],
  );

  const filtered = useMemo(() => {
    const needle = search.trim().toLowerCase();
    return users.filter((user) => {
      if (status === 'ACTIVE' && !user.active) return false;
      if (status === 'INACTIVE' && user.active) return false;
      if (!needle) return true;
      return (
        user.fullName.toLowerCase().includes(needle) ||
        user.email.toLowerCase().includes(needle) ||
        ROLE_LABELS[user.role].toLowerCase().includes(needle)
      );
    });
  }, [users, status, search]);

  const openCreate = () => {
    setEditing(null);
    setFormOpen(true);
  };

  const openEdit = (user: TenantUser) => {
    setEditing(user);
    setFormOpen(true);
  };

  const columns: Array<TableColumn<TenantUser> | null> = [
    {
      id: 'user',
      header: 'Usuario',
      mobile: 'title',
      cell: (user) => (
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <span className="truncate font-medium text-foreground">{user.fullName}</span>
            {user.id === me.id && (
              <Badge tone="primary" size="sm">
                Vos
              </Badge>
            )}
          </div>
          <span className="block truncate text-sm text-muted-foreground">{user.email}</span>
        </div>
      ),
    },
    {
      id: 'role',
      header: 'Rol',
      mobile: 'aside',
      cell: (user) => <Badge tone={ROLE_TONES[user.role]}>{ROLE_LABELS[user.role]}</Badge>,
    },
    {
      id: 'branches',
      header: 'Sucursales',
      mobile: 'field',
      cell: (user) =>
        worksInAssignedBranches(user.role) ? (
          user.branches.length === 0 ? (
            <span className="text-sm text-crit-ink">Sin sucursal asignada</span>
          ) : (
            <div className="flex flex-wrap gap-1">
              {user.branches.map((branch) => (
                <Badge key={branch.id} tone={branch.active ? 'neutral' : 'warn'} icon={Building2}>
                  {branch.name}
                </Badge>
              ))}
            </div>
          )
        ) : (
          <span className="inline-flex items-center gap-1.5 text-sm text-muted-foreground">
            <ShieldCheck className="size-3.5" aria-hidden="true" />
            Todas las sucursales
          </span>
        ),
    },
    {
      id: 'lastLogin',
      header: 'Último ingreso',
      hideBelow: 'lg',
      mobile: 'field',
      mobileLabel: 'Último ingreso',
      cell: (user) => (
        <span className="text-sm text-muted-foreground">
          {user.lastLoginAt ? formatRelative(user.lastLoginAt) : 'Nunca entró'}
        </span>
      ),
    },
    {
      id: 'status',
      header: 'Estado',
      mobile: 'aside',
      cell: (user) =>
        user.active ? (
          <StatusPill tone={user.mustChangePassword ? 'warn' : 'ok'}>
            {user.mustChangePassword ? 'Cambia la clave' : 'Activo'}
          </StatusPill>
        ) : (
          <StatusPill tone="neutral">Desactivado</StatusPill>
        ),
    },
    {
      id: 'actions',
      header: <span className="sr-only">Acciones</span>,
      align: 'right',
      mobile: 'actions',
      headerClassName: 'w-28',
      cell: (user) => (
        <div className="flex items-center justify-end gap-1">
          <Button variant="ghost" size="sm" leftIcon={<PencilLine aria-hidden="true" />} onClick={() => openEdit(user)}>
            Editar
          </Button>
          {user.id !== me.id && (
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="ghost" size="icon-sm" aria-label={`Más acciones para ${user.fullName}`}>
                  <MoreVertical className="size-4" aria-hidden="true" />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end">
                <DropdownMenuItem onSelect={() => setResetting(user)}>
                  <KeyRound className="size-4" aria-hidden="true" />
                  Restablecer contraseña
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          )}
        </div>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        title="Usuarios"
        description="Quién entra a GondolIA, con qué rol y en qué sucursales trabaja."
        icon={UserCog}
        actions={
          <Button onClick={openCreate} leftIcon={<UserPlus aria-hidden="true" />}>
            Invitar usuario
          </Button>
        }
      >
        <div className="flex flex-col gap-3 md:flex-row md:flex-wrap md:items-center md:justify-between">
          <Segmented<StatusFilter>
            className="shrink-0"
            value={status}
            onChange={setStatus}
            label="Filtrar usuarios por estado"
            options={[
              { value: 'ALL', label: 'Todos', count: counts.ALL },
              { value: 'ACTIVE', label: 'Activos', count: counts.ACTIVE, tone: 'ok' },
              { value: 'INACTIVE', label: 'Desactivados', count: counts.INACTIVE },
            ]}
          />
          <SearchInput
            value={search}
            onValueChange={setSearch}
            placeholder="Buscar por nombre, email o rol…"
            className="md:w-72 lg:w-80"
          />
        </div>
      </PageHeader>

      <Card padding="none">
        <Table<TenantUser>
          columns={columns}
          data={filtered}
          rowKey={(user) => user.id}
          loading={usersQuery.isPending}
          error={usersQuery.error}
          onRetry={() => void usersQuery.refetch()}
          caption="Usuarios del comercio con su rol, sus sucursales y su estado"
          rowClassName={(user) => (user.active ? undefined : 'opacity-70')}
          empty={{
            icon: Users,
            title: search || status !== 'ALL' ? 'Ningún usuario coincide' : 'Todavía no invitaste a nadie',
            description:
              search || status !== 'ALL'
                ? 'Probá con otro nombre o limpiá los filtros.'
                : 'Sumá a tu equipo: cada persona entra con su email y ve solo lo que le toca.',
            action:
              search || status !== 'ALL' ? (
                <Button
                  variant="outline"
                  onClick={() => {
                    setSearch('');
                    setStatus('ALL');
                  }}
                >
                  Limpiar filtros
                </Button>
              ) : (
                <Button onClick={openCreate} leftIcon={<UserPlus aria-hidden="true" />}>
                  Invitar usuario
                </Button>
              ),
          }}
        />
      </Card>

      <UserFormDialog
        open={formOpen}
        onClose={() => setFormOpen(false)}
        user={editing}
        branches={branchesQuery.data ?? []}
        currentUserId={me.id}
      />
      <ResetPasswordDialog open={resetting !== null} onClose={() => setResetting(null)} user={resetting} />
    </>
  );
}
