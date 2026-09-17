/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Puerto HTTPS publicado por nginx (para el aviso de cámara en contextos no seguros). Defecto: 8443. */
  readonly VITE_HTTPS_PORT?: string;
  /** `'false'` oculta el cuadro "Cuentas de demostración" del login. */
  readonly VITE_SHOW_DEMO_ACCOUNTS?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
