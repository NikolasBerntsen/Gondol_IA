import { Camera, ExternalLink } from 'lucide-react';
import { getSecureUrl, LOCAL_CA_CERT_PATH, needsSecureContextForCamera } from '@/lib/secureContext';
import { Alert } from './Alert';
import { buttonClasses } from './Button';

export interface SecureContextWarningProps {
  className?: string;
}

/**
 * Aviso para pantallas que usan la cámara (escáner, OCR): si la página se abrió por HTTP desde otro
 * dispositivo el navegador bloquea la cámara (SPEC §9.5). No renderiza nada en contextos seguros o en localhost.
 */
export function SecureContextWarning({ className }: SecureContextWarningProps) {
  if (!needsSecureContextForCamera()) return null;
  const secureUrl = getSecureUrl();
  return (
    <Alert
      tone="warning"
      icon={Camera}
      className={className}
      title="La cámara no está disponible en esta conexión"
      action={
        <a href={secureUrl} className={buttonClasses({ variant: 'primary', size: 'sm' })}>
          Abrir con HTTPS
          <ExternalLink className="h-4 w-4" aria-hidden="true" />
        </a>
      }
    >
      <p>
        Para usar la cámara abrí <span className="break-all font-semibold">{secureUrl}</span>. Si el navegador avisa
        que el sitio no es seguro, instalá el{' '}
        <a href={LOCAL_CA_CERT_PATH} className="font-semibold underline underline-offset-2" download>
          certificado de GondolIA
        </a>{' '}
        o continuá igualmente. Mientras tanto podés cargar el código a mano o subir una foto.
      </p>
    </Alert>
  );
}
