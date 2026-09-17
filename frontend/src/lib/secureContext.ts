const DEFAULT_HTTPS_PORT = '8443';

const LOCAL_HOSTNAMES = new Set(['localhost', '127.0.0.1', '::1', '[::1]']);

export function isLocalhost(hostname: string = window.location.hostname): boolean {
  return LOCAL_HOSTNAMES.has(hostname) || hostname.endsWith('.localhost');
}

/** `true` si el navegador permite pedir la cámara en esta página (contexto seguro + API disponible). */
export function isCameraSupported(): boolean {
  return window.isSecureContext && typeof navigator.mediaDevices?.getUserMedia === 'function';
}

/**
 * `true` cuando la página se abrió por HTTP desde otra máquina (p. ej. el celular en la red local):
 * el navegador bloquea la cámara y hay que entrar por HTTPS.
 */
export function needsSecureContextForCamera(): boolean {
  return !window.isSecureContext && !isLocalhost();
}

/** URL HTTPS equivalente a la actual (mismo host, puerto HTTPS publicado por nginx). */
export function getSecureUrl(): string {
  const { hostname, pathname, search, hash } = window.location;
  const port = import.meta.env.VITE_HTTPS_PORT || DEFAULT_HTTPS_PORT;
  const portSuffix = port === '443' ? '' : `:${port}`;
  const host = hostname.includes(':') && !hostname.startsWith('[') ? `[${hostname}]` : hostname;
  return `https://${host}${portSuffix}${pathname}${search}${hash}`;
}

/** Ruta del certificado de la CA local que sirve nginx (para instalar en el celular). */
export const LOCAL_CA_CERT_PATH = '/gondolia-ca.crt';
