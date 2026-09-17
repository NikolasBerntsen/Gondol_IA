/** @type {import('tailwindcss').Config} */
// Góndola UI · Tailwind theme
// Los colores salen SIEMPRE de tokens CSS (src/index.css) en formato HSL "h s% l%",
// así funcionan los modificadores de opacidad (bg-primary/10) y los tres estados de tema
// (sistema / data-theme="light" / data-theme="dark"). No se usa la estrategia .dark de Tailwind.
const token = (name) => `hsl(var(--${name}) / <alpha-value>)`

module.exports = {
  darkMode: ["class"], // sin uso: el tema lo resuelven los tokens + data-theme
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    // Escala tipográfica cerrada: 12 / 13 / 14 / 15 (lectura) / 16 / 20 / 26 / 34 / 48
    fontSize: {
      xs: ["12px", { lineHeight: "16px" }],
      sm: ["13px", { lineHeight: "18px" }],
      base: ["14px", { lineHeight: "20px" }],
      read: ["15px", { lineHeight: "24px" }],
      md: ["16px", { lineHeight: "24px" }],
      lg: ["20px", { lineHeight: "28px" }],
      xl: ["26px", { lineHeight: "32px" }],
      "2xl": ["34px", { lineHeight: "40px" }],
      "3xl": ["48px", { lineHeight: "52px" }],
    },
    fontFamily: {
      display: ['"Bricolage Grotesque"', '"Segoe UI"', "system-ui", "-apple-system", "Roboto", "sans-serif"],
      sans: ["Figtree", '"Segoe UI"', "system-ui", "-apple-system", "Roboto", '"Helvetica Neue"', "Arial", "sans-serif"],
      mono: ['"JetBrains Mono"', "ui-monospace", '"Cascadia Mono"', "Consolas", '"SFMono-Regular"', "Menlo", "monospace"],
    },
    extend: {
      colors: {
        border: token("border"),
        input: token("input"),
        ring: token("ring"),
        background: token("background"),
        foreground: token("foreground"),
        scrim: token("scrim"),
        primary: { DEFAULT: token("primary"), foreground: token("primary-foreground") },
        secondary: { DEFAULT: token("muted"), foreground: token("foreground") },
        destructive: { DEFAULT: token("crit"), foreground: token("on-solid") },
        muted: { DEFAULT: token("muted"), foreground: token("muted-foreground") },
        accent: { DEFAULT: token("accent"), foreground: token("accent-foreground") },
        popover: { DEFAULT: token("card"), foreground: token("foreground") },
        card: { DEFAULT: token("card"), foreground: token("foreground") },
        rail: {
          DEFAULT: token("rail"),
          foreground: token("rail-foreground"),
          active: token("rail-active"),
          hover: token("rail-hover"),
          muted: token("rail-muted"),
          strong: token("rail-strong"),
        },
        ok: { DEFAULT: token("ok"), soft: token("ok-soft"), ink: token("ok-ink"), foreground: token("on-solid") },
        warn: { DEFAULT: token("warn"), soft: token("warn-soft"), ink: token("warn-ink"), foreground: token("on-solid") },
        crit: { DEFAULT: token("crit"), soft: token("crit-soft"), ink: token("crit-ink"), foreground: token("on-solid") },
        info: { DEFAULT: token("info"), soft: token("info-soft"), ink: token("info-ink"), foreground: token("on-solid") },
        paper: { DEFAULT: token("paper"), ink: token("paper-ink") },
        device: token("device"),
        camera: { DEFAULT: token("camera"), foreground: token("camera-foreground") },
      },
      // Radios por rol (no uniformes)
      borderRadius: {
        control: "var(--r-control)", // botones, inputs, selects: 8px
        panel: "var(--r-panel)", // paneles: 12px
        dialog: "var(--r-dialog)", // diálogos y hojas: 16px
        tag: "var(--r-tag)", // etiqueta de precio y chips de datos: 4px
        lg: "var(--r-control)",
        md: "6px",
        sm: "4px",
      },
      boxShadow: {
        pop: "var(--shadow-pop)",
        sheet: "var(--shadow-sheet)",
      },
      keyframes: {
        "accordion-down": { from: { height: "0" }, to: { height: "var(--radix-accordion-content-height)" } },
        "accordion-up": { from: { height: "var(--radix-accordion-content-height)" }, to: { height: "0" } },
        scanline: { "0%": { top: "0%" }, "100%": { top: "calc(100% - 2px)" } },
      },
      animation: {
        "accordion-down": "accordion-down 0.2s ease-out",
        "accordion-up": "accordion-up 0.2s ease-out",
        scanline: "scanline 1.6s ease-in-out infinite alternate",
      },
    },
  },
  plugins: [require("tailwindcss-animate")],
}
