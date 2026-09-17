import { Button, Field, Input } from '@/components/ui';
import type { NewUserRequest } from '../types';

export interface NewUserFieldsProps {
  /** Prefijo de los campos del backend: `boss`, `admin` o `employee`. */
  name: 'boss' | 'admin' | 'employee';
  title: string;
  description: string;
  value: NewUserRequest;
  onChange: (value: NewUserRequest) => void;
  errors: Record<string, string>;
}

/** Contraseña fácil de dictar por teléfono: sin caracteres que se confunden. */
function suggestPassword(): string {
  const letters = 'ABCDEFGHJKLMNPQRSTUVWXYZ';
  const digits = '23456789';
  const pick = (source: string, count: number) =>
    Array.from({ length: count }, () => source[Math.floor(Math.random() * source.length)]).join('');
  return `Gnd-${pick(letters, 4)}-${pick(digits, 4)}`;
}

/** Los tres usuarios iniciales del comercio (jefe, administrador y empleado), SPEC §6.6. */
export function NewUserFields({ name, title, description, value, onChange, errors }: NewUserFieldsProps) {
  const set = (patch: Partial<NewUserRequest>) => onChange({ ...value, ...patch });

  return (
    <fieldset className="space-y-3 rounded-panel border border-border p-4">
      <legend className="px-1 text-base font-semibold text-foreground">{title}</legend>
      <p className="text-sm text-muted-foreground">{description}</p>
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label="Nombre y apellido" error={errors[`${name}.fullName`]}>
          <Input
            value={value.fullName}
            onChange={(event) => set({ fullName: event.target.value })}
            autoComplete="off"
            maxLength={150}
          />
        </Field>
        <Field label="Email" error={errors[`${name}.email`]}>
          <Input
            type="email"
            value={value.email}
            onChange={(event) => set({ email: event.target.value })}
            autoComplete="off"
            maxLength={150}
          />
        </Field>
      </div>
      <Field
        label="Contraseña inicial"
        hint="Se la dictás al cliente; la va a poder cambiar desde su perfil."
        error={errors[`${name}.password`]}
        labelAction={
          <Button variant="link" size="sm" onClick={() => set({ password: suggestPassword() })}>
            Generar una
          </Button>
        }
      >
        <Input
          value={value.password}
          onChange={(event) => set({ password: event.target.value })}
          autoComplete="new-password"
          className="font-mono"
          maxLength={72}
        />
      </Field>
    </fieldset>
  );
}
