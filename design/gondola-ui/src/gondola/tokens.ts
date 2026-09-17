// Góndola UI · referencia de tokens (documentación viva; los valores reales viven en src/index.css)

export interface ColorToken {
  name: string
  token: string
  light: string
  dark: string
  usage: string
  /** clase de fondo para la muestra */
  swatch: string
  /** clase de texto legible sobre la muestra */
  on?: string
}

export const BASE_COLORS: ColorToken[] = [
  { name: "Fondo", token: "--background", light: "#F3F5F1", dark: "#0D1410", usage: "Lienzo de la app, sesgado a verde.", swatch: "bg-background", on: "text-foreground" },
  { name: "Superficie", token: "--card", light: "#FFFFFF", dark: "#141E18", usage: "Paneles, tablas, barra superior.", swatch: "bg-card", on: "text-foreground" },
  { name: "Tinta", token: "--foreground", light: "#15231A", dark: "#E4EEE7", usage: "Texto principal y línea de ventas.", swatch: "bg-foreground", on: "text-background" },
  { name: "Apagado", token: "--muted", light: "#E9EEE7", dark: "#1B2820", usage: "Hover, segmentados, zonas secundarias.", swatch: "bg-muted", on: "text-foreground" },
  { name: "Texto secundario", token: "--muted-foreground", light: "#5A6A5F", dark: "#93A69A", usage: "Bajadas, rótulos, metadatos.", swatch: "bg-muted-foreground", on: "text-background" },
  { name: "Borde", token: "--border", light: "#D9E1D6", dark: "#26352C", usage: "Separación entre objetos y filas.", swatch: "bg-border", on: "text-foreground" },
  { name: "Verde góndola", token: "--primary", light: "#1E6A42", dark: "#52B883", usage: "Acción principal, foco, stock.", swatch: "bg-primary", on: "text-primary-foreground" },
  { name: "Riel", token: "--rail", light: "#0F3A25", dark: "#09100C", usage: "Navegación lateral. Nada más.", swatch: "bg-rail", on: "text-rail-foreground" },
  { name: "Amarillo etiqueta", token: "--accent", light: "#F4C542", dark: "#F2C542", usage: "Precios, total del POS, ofertas. Uno por pantalla.", swatch: "bg-accent", on: "text-accent-foreground" },
]

export const SEMANTIC_COLORS: { name: string; key: "ok" | "warn" | "crit" | "info"; sample: string; light: [string, string, string]; dark: [string, string, string]; usage: string }[] = [
  { name: "OK", key: "ok", sample: "Stock OK", light: ["#2F8A55", "#E2F1E7", "#23693F"], dark: ["#5BC98C", "#16301F", "#7AD7A3"], usage: "Stock suficiente, operación completada." },
  { name: "Atención", key: "warn", sample: "Por vencer", light: ["#C96A12", "#FBEBDB", "#8F4A0B"], dark: ["#F0A24C", "#35240F", "#F5B872"], usage: "Por vencer, stock bajo, advertencias que no bloquean." },
  { name: "Crítico", key: "crit", sample: "Vencido", light: ["#BF3A2B", "#F9E3E0", "#9C2E22"], dark: ["#F0705F", "#3A1A16", "#F58E80"], usage: "Vencido, sin stock, recall, errores que bloquean." },
  { name: "Info", key: "info", sample: "Próximo", light: ["#2C6E9B", "#E1EDF5", "#225A80"], dark: ["#6AAED9", "#132838", "#8EC3E6"], usage: "Próximos vencimientos, IA, avisos neutrales." },
]

export const TYPE_SCALE: { px: number; cls: string; role: string; face: "display" | "sans" | "mono"; sample: string; weight: string }[] = [
  { px: 48, cls: "text-3xl", role: "Total del POS (etiqueta xl)", face: "display", sample: "$ 16.270", weight: "font-bold" },
  { px: 34, cls: "text-2xl", role: "Título de página · número KPI", face: "display", sample: "Resumen del negocio", weight: "font-semibold" },
  { px: 26, cls: "text-xl", role: "Título en móvil · montos destacados", face: "display", sample: "$ 8.450.000", weight: "font-semibold" },
  { px: 20, cls: "text-lg", role: "Títulos de diálogo · precios en mosaicos", face: "display", sample: "Alerta de seguridad alimentaria", weight: "font-semibold" },
  { px: 16, cls: "text-md", role: "Títulos de panel · inputs en móvil", face: "sans", sample: "Próximos vencimientos", weight: "font-semibold" },
  { px: 15, cls: "text-read", role: "Lectura corrida (explicaciones)", face: "sans", sample: "Quedan 26 u. del lote que vencen mañana.", weight: "font-normal" },
  { px: 14, cls: "text-base", role: "UI base · celdas de tabla", face: "sans", sample: "Yogur bebible frutilla 1 L", weight: "font-normal" },
  { px: 13, cls: "text-sm", role: "Ayudas, bajadas, botones chicos", face: "sans", sample: "Actualizado a las 10:42", weight: "font-normal" },
  { px: 12, cls: "text-xs", role: "Rótulos en mayúscula · chips mono", face: "mono", sample: "VTO 25/09/26 · L2410C", weight: "font-medium" },
]
