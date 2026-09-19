import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Check, Copy, KeyRound } from 'lucide-react';
import { useEffect, useState } from 'react';
import { toast } from 'sonner';
import { getErrorMessage } from '@/api/client';
import { Alert, Button, Field, Input, Modal, Toggle } from '@/components/ui';
import { tenantAdminKeys, usersApi } from '../api';
import type { ResetPasswordResponse, TenantUser } from '../types';

const MIN_PASSWORD_LENGTH = 8;

interface ResetPasswordDialogProps {
  open: boolean;
  onClose: () => void;
  user: TenantUser | null;
}

/**
 * Reseteo de contraseña: por defecto la genera el sistema. La contraseña se muestra **una sola vez** y cierra las
 * sesiones abiertas del usuario.
 */
export function ResetPasswordDialog({ open, onClose, user }: ResetPasswordDialogProps) {
  const queryClient = useQueryClient();
  const [manual, setManual] = useState(false);
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string>();
  const [result, setResult] = useState<ResetPasswordResponse | null>(null);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (open) {
      setManual(false);
      setPassword('');
      setError(undefined);
      setResult(null);
      setCopied(false);
    }
  }, [open, user]);

  const mutation = useMutation({
    mutationFn: () => usersApi.resetPassword(user!.id, manual ? password : undefined),
    onSuccess: (response) => {
      setResult(response);
      queryClient.invalidateQueries({ queryKey: tenantAdminKeys.users });
      toast.success('Contraseña restablecida.');
    },
    onError: (mutationError) => setError(getErrorMessage(mutationError)),
  });

  const copy = async () => {
    if (!result) return;
    try {
      await navigator.clipboard.writeText(result.temporaryPassword);
      setCopied(true);
      toast.success('Contraseña copiada.');
    } catch {
      toast.error('No pudimos copiarla. Seleccionala y copiala a mano.');
    }
  };

  const submit = () => {
    setError(undefined);
    if (manual && password.length < MIN_PASSWORD_LENGTH) {
      setError(`La contraseña tiene que tener al menos ${MIN_PASSWORD_LENGTH} caracteres.`);
      return;
    }
    mutation.mutate();
  };

  return (
    <Modal
      open={open}
      onClose={onClose}
      size="md"
      preventClose={mutation.isPending}
      title={result ? 'Contraseña restablecida' : 'Restablecer la contraseña'}
      description={
        result
          ? 'Dictásela ahora: por seguridad no la vas a poder volver a ver.'
          : user
            ? `${user.fullName} va a tener que cambiarla la próxima vez que entre.`
            : undefined
      }
      footer={
        result ? (
          <Button onClick={onClose}>Listo</Button>
        ) : (
          <>
            <Button variant="outline" onClick={onClose} disabled={mutation.isPending}>
              Cancelar
            </Button>
            <Button onClick={submit} loading={mutation.isPending} leftIcon={<KeyRound aria-hidden="true" />}>
              Restablecer contraseña
            </Button>
          </>
        )
      }
    >
      {result ? (
        <div className="grid gap-3">
          <div className="rounded-panel border border-border bg-muted p-4">
            <p className="gd-eyebrow text-muted-foreground">Contraseña temporal de {result.email}</p>
            <div className="mt-2 flex items-center gap-2">
              <code className="min-w-0 flex-1 break-all rounded-control bg-card px-3 py-2 font-mono text-md text-foreground">
                {result.temporaryPassword}
              </code>
              <Button
                variant="outline"
                size="icon"
                onClick={copy}
                aria-label="Copiar la contraseña"
                title="Copiar la contraseña"
              >
                {copied ? <Check className="size-4" aria-hidden="true" /> : <Copy className="size-4" aria-hidden="true" />}
              </Button>
            </div>
          </div>
          <Alert tone="warn" title="Se cerraron sus sesiones abiertas">
            Si estaba usando GondolIA en otro dispositivo, va a tener que iniciar sesión de nuevo con esta contraseña.
          </Alert>
        </div>
      ) : (
        <div className="grid gap-4">
          {error && (
            <Alert tone="crit" title="No se pudo restablecer">
              {error}
            </Alert>
          )}
          <Toggle
            checked={manual}
            onChange={setManual}
            label="Elegir la contraseña yo"
            description="Si lo dejás apagado, GondolIA genera una contraseña temporal fácil de dictar."
          />
          {manual && (
            <Field label="Contraseña nueva" hint="Mínimo 8 caracteres." required>
              <Input
                data-autofocus
                type="text"
                className="font-mono"
                value={password}
                maxLength={72}
                autoComplete="new-password"
                onChange={(event) => setPassword(event.target.value)}
              />
            </Field>
          )}
        </div>
      )}
    </Modal>
  );
}
