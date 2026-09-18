import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Eye, EyeOff, Sparkles } from 'lucide-react';
import { useEffect, useMemo, useState, type FormEvent } from 'react';
import { toast } from 'sonner';
import { getErrorMessage, getFieldErrors } from '@/api/client';
import { ROLE_LABELS, type Role } from '@/api/types';
import { Alert, Button, Field, Input, Modal, Select, Toggle } from '@/components/ui';
import { tenantAdminKeys, usersApi } from '../api';
import { ROLE_DESCRIPTIONS, TENANT_USER_ROLES, worksInAssignedBranches, type TenantBranch, type TenantUser } from '../types';
import { BranchCheckboxes } from './BranchCheckboxes';

const PASSWORD_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789';
const MIN_PASSWORD_LENGTH = 8;

function suggestPassword(): string {
  const random = Array.from(crypto.getRandomValues(new Uint32Array(8)))
    .map((value) => PASSWORD_ALPHABET[value % PASSWORD_ALPHABET.length])
    .join('');
  return `Gondolia-${random}`;
}

interface UserFormDialogProps {
  open: boolean;
  onClose: () => void;
  /** `null` = alta. */
  user: TenantUser | null;
  branches: TenantBranch[];
  /** El administrador que está editando: no puede cambiarse el rol ni desactivarse. */
  currentUserId: number;
}

interface FormState {
  fullName: string;
  email: string;
  password: string;
  role: Role;
  active: boolean;
  branchIds: number[];
}

function initialState(user: TenantUser | null): FormState {
  return {
    fullName: user?.fullName ?? '',
    email: user?.email ?? '',
    password: '',
    role: user?.role ?? 'TENANT_EMPLOYEE',
    active: user?.active ?? true,
    branchIds: user?.branches.filter((branch) => branch.active).map((branch) => branch.id) ?? [],
  };
}

/** Alta y edición de un usuario del comercio. El selector de sucursales solo aparece para empleados y cajeros. */
export function UserFormDialog({ open, onClose, user, branches, currentUserId }: UserFormDialogProps) {
  const queryClient = useQueryClient();
  const isEdit = user !== null;
  const isSelf = isEdit && user.id === currentUserId;
  const [form, setForm] = useState<FormState>(() => initialState(user));
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string>();
  const [showPassword, setShowPassword] = useState(false);

  useEffect(() => {
    if (open) {
      setForm(initialState(user));
      setErrors({});
      setFormError(undefined);
      setShowPassword(false);
    }
  }, [open, user]);

  const needsBranches = worksInAssignedBranches(form.role);
  const roleOptions = useMemo(
    () => TENANT_USER_ROLES.map((role) => ({ value: role, label: ROLE_LABELS[role] })),
    [],
  );

  const mutation = useMutation({
    mutationFn: async (values: FormState) => {
      if (isEdit) {
        return usersApi.update(user.id, {
          fullName: values.fullName.trim(),
          role: values.role,
          active: values.active,
          branchIds: worksInAssignedBranches(values.role) ? values.branchIds : [],
        });
      }
      return usersApi.create({
        fullName: values.fullName.trim(),
        email: values.email.trim(),
        password: values.password,
        role: values.role,
        branchIds: worksInAssignedBranches(values.role) ? values.branchIds : [],
      });
    },
    onSuccess: (saved) => {
      toast.success(isEdit ? 'Usuario actualizado.' : `Usuario creado: ${saved.email}`);
      queryClient.invalidateQueries({ queryKey: tenantAdminKeys.users });
      queryClient.invalidateQueries({ queryKey: tenantAdminKeys.branches });
      onClose();
    },
    onError: (error) => {
      setErrors(getFieldErrors(error));
      setFormError(getErrorMessage(error));
    },
  });

  const validate = (): boolean => {
    const next: Record<string, string> = {};
    if (!form.fullName.trim()) next.fullName = 'Escribí el nombre y el apellido.';
    if (!isEdit) {
      if (!form.email.trim()) next.email = 'Escribí el email con el que va a entrar.';
      else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email.trim())) next.email = 'Revisá el email: falta el @ o el dominio.';
      if (form.password.length < MIN_PASSWORD_LENGTH) {
        next.password = `La contraseña tiene que tener al menos ${MIN_PASSWORD_LENGTH} caracteres.`;
      }
    }
    if (needsBranches && form.branchIds.length === 0) {
      next.branchIds = 'Elegí al menos una sucursal donde trabaja.';
    }
    setErrors(next);
    setFormError(undefined);
    return Object.keys(next).length === 0;
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (!validate()) return;
    mutation.mutate(form);
  };

  return (
    <Modal
      open={open}
      onClose={onClose}
      size="lg"
      preventClose={mutation.isPending}
      title={isEdit ? `Editar a ${user.fullName}` : 'Invitar a un usuario'}
      description={
        isEdit
          ? 'Cambiá el rol, el estado y las sucursales donde trabaja.'
          : 'Creá la cuenta con una contraseña inicial: la va a tener que cambiar al entrar.'
      }
      footer={
        <>
          <Button variant="outline" onClick={onClose} disabled={mutation.isPending}>
            Cancelar
          </Button>
          <Button type="submit" form="user-form" loading={mutation.isPending}>
            {isEdit ? 'Guardar cambios' : 'Crear usuario'}
          </Button>
        </>
      }
    >
      <form id="user-form" className="grid gap-4" onSubmit={submit} noValidate>
        {formError && (
          <Alert tone="crit" title="No se pudo guardar">
            {formError}
          </Alert>
        )}

        <Field label="Nombre y apellido" error={errors.fullName} required>
          <Input
            data-autofocus
            value={form.fullName}
            maxLength={150}
            autoComplete="off"
            placeholder="Laura Gómez"
            onChange={(event) => setForm((state) => ({ ...state, fullName: event.target.value }))}
          />
        </Field>

        {isEdit ? (
          <Field label="Email" hint="El email identifica la cuenta y no se puede cambiar.">
            <Input value={form.email} readOnly disabled />
          </Field>
        ) : (
          <div className="grid gap-4 md:grid-cols-2">
            <Field label="Email" error={errors.email} required>
              <Input
                type="email"
                value={form.email}
                maxLength={150}
                autoComplete="off"
                placeholder="laura@micomercio.com"
                onChange={(event) => setForm((state) => ({ ...state, email: event.target.value }))}
              />
            </Field>
            <Field
              label="Contraseña inicial"
              error={errors.password}
              hint="Se la vas a tener que dictar: la cambia al entrar."
              required
              labelAction={
                <button
                  type="button"
                  className="inline-flex items-center gap-1 text-sm font-medium text-primary hover:underline"
                  onClick={() => {
                    setForm((state) => ({ ...state, password: suggestPassword() }));
                    setShowPassword(true);
                  }}
                >
                  <Sparkles className="size-3.5" aria-hidden="true" />
                  Generar
                </button>
              }
            >
              <Input
                type={showPassword ? 'text' : 'password'}
                value={form.password}
                maxLength={72}
                autoComplete="new-password"
                className={showPassword ? 'font-mono' : undefined}
                onChange={(event) => setForm((state) => ({ ...state, password: event.target.value }))}
                rightElement={
                  <button
                    type="button"
                    className="text-muted-foreground transition-colors hover:text-foreground"
                    onClick={() => setShowPassword((visible) => !visible)}
                    aria-label={showPassword ? 'Ocultar la contraseña' : 'Mostrar la contraseña'}
                  >
                    {showPassword ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
                  </button>
                }
              />
            </Field>
          </div>
        )}

        <Field
          label="Rol"
          error={errors.role}
          hint={isSelf ? 'No podés cambiar tu propio rol.' : ROLE_DESCRIPTIONS[form.role]}
          required
        >
          <Select
            options={roleOptions}
            value={form.role}
            disabled={isSelf}
            onChange={(event) => {
              const role = event.target.value as Role;
              setForm((state) => ({ ...state, role, branchIds: worksInAssignedBranches(role) ? state.branchIds : [] }));
            }}
          />
        </Field>

        {needsBranches ? (
          <Field
            label="Sucursales donde trabaja"
            error={errors.branchIds}
            hint="Solo ve el stock, las ventas y las alertas de estas sucursales."
            required
          >
            {() => (
              <BranchCheckboxes
                branches={branches}
                selected={form.branchIds}
                onChange={(branchIds) => setForm((state) => ({ ...state, branchIds }))}
              />
            )}
          </Field>
        ) : (
          <Alert tone="info" title="Acceso a todas las sucursales">
            {ROLE_LABELS[form.role]} ve todas las sucursales activas del comercio, así que no hace falta asignarle
            ninguna.
          </Alert>
        )}

        {isEdit && (
          <Toggle
            checked={form.active}
            disabled={isSelf}
            onChange={(active) => setForm((state) => ({ ...state, active }))}
            label="Usuario activo"
            description={
              isSelf
                ? 'No podés desactivar tu propio usuario.'
                : 'Si lo desactivás, se cierran sus sesiones abiertas y no puede volver a entrar.'
            }
          />
        )}
      </form>
    </Modal>
  );
}
