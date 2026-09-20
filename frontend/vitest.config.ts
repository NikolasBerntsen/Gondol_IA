/**
 * Configuración de las pruebas unitarias del frontend (Vitest + jsdom).
 * Aparte de vite.config.ts para no cargar el dev server ni el proxy al correr los tests.
 *
 *   npm test                 → una pasada
 *   npm run test:watch       → se queda mirando los archivos
 *   npm run test:coverage    → informe de cobertura (coverage/)
 */
import { fileURLToPath, URL } from 'node:url';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    restoreMocks: true,
    clearMocks: true,
    coverage: {
      provider: 'v8',
      reportsDirectory: './coverage',
      reporter: ['text-summary', 'lcov', 'html'],
      include: ['src/**/*.{ts,tsx}'],
      exclude: [
        'src/**/*.test.{ts,tsx}',
        'src/test/**',
        'src/main.tsx',
        'src/vite-env.d.ts',
        // Tipos y listas de constantes: no hay lógica que ejecutar.
        'src/**/types.ts',
        'src/components/ui/index.ts',
      ],
      // Piso de cobertura: si baja, el build falla. Los números son los que hay hoy
      // (`npm run test:coverage` los muestra): la lógica de negocio del frontend
      // —`src/lib`, `src/config`, `src/auth`, `src/api/client.ts`, `src/theme`,
      // `src/branches` y los helpers de `src/features`— está entre el 93 % y el 100 %,
      // mientras que las pantallas (`*.tsx`) todavía no tienen pruebas y bajan el total.
      // A medida que se cubran pantallas, subí estos valores.
      thresholds: {
        lines: 5,
        statements: 5,
        functions: 40,
        branches: 70,
      },
    },
  },
});
