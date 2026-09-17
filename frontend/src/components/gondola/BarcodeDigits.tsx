import { cn } from '@/lib/cn';

const L = ['0001101', '0011001', '0010011', '0111101', '0100011', '0110001', '0101111', '0111011', '0110111', '0001011'];
const G = ['0100111', '0110011', '0011011', '0100001', '0011101', '0111001', '0000101', '0010001', '0001001', '0010111'];
const R = ['1110010', '1100110', '1101100', '1000010', '1011100', '1001110', '1010000', '1000100', '1001000', '1110100'];
const PARITY = ['LLLLLL', 'LLGLGG', 'LLGGLG', 'LLGGGL', 'LGLLGG', 'LGGLLG', 'LGGGLL', 'LGLGLG', 'LGLGGL', 'LGGLGL'];

/** Módulos EAN-13 reales (95) + marcas de guarda más largas. */
function ean13Modules(code: string): { bits: string; guard: boolean[] } {
  const digits = code.split('').map(Number);
  const parity = PARITY[digits[0]];
  let bits = '101';
  const guard: boolean[] = [true, true, true];
  for (let i = 1; i <= 6; i += 1) {
    bits += (parity[i - 1] === 'L' ? L : G)[digits[i]];
    guard.push(...Array<boolean>(7).fill(false));
  }
  bits += '01010';
  guard.push(...Array<boolean>(5).fill(true));
  for (let i = 7; i <= 12; i += 1) {
    bits += R[digits[i]];
    guard.push(...Array<boolean>(7).fill(false));
  }
  bits += '101';
  guard.push(true, true, true);
  return { bits, guard };
}

export interface BarcodeDigitsProps {
  code: string;
  /** Ancho en px del gráfico. */
  width?: number;
  /** Solo los dígitos en mono (listados largos). */
  digitsOnly?: boolean;
  /** Sin la superficie de papel (cuando ya está sobre una etiqueta, p. ej. el visor de la cámara). */
  bare?: boolean;
  className?: string;
}

/**
 * Código de barras: dibujo EAN-13 real (paridad L/G/R) con los dígitos en mono agrupados 1-6-6,
 * siempre sobre **papel** (tinta oscura sobre claro en los dos temas), como el envase.
 * Otros formatos (Code128, alfanuméricos) muestran solo los dígitos.
 *
 * Usar en: producto detectado por el escáner, alerta de recall, ficha de producto.
 */
export function BarcodeDigits({ code, width = 150, digitsOnly, bare, className }: BarcodeDigitsProps) {
  const isEan = /^\d{13}$/.test(code);
  if (digitsOnly || !isEan) {
    return <span className={cn('font-mono text-base tabular-nums tracking-[0.04em]', className)}>{code}</span>;
  }
  const { bits, guard } = ean13Modules(code);
  const quiet = 9;
  const totalWidth = quiet + 95 + 3;
  const barHeight = 34;
  return (
    <span
      className={cn(
        'inline-flex max-w-full flex-col',
        bare ? 'text-paper-ink' : 'rounded-tag border border-border bg-paper px-2 pb-1 pt-1.5 text-paper-ink',
        className,
      )}
      role="img"
      aria-label={`Código de barras ${code}`}
    >
      <svg
        viewBox={`0 0 ${totalWidth} ${barHeight + 13}`}
        width={width}
        height={(width * (barHeight + 13)) / totalWidth}
        aria-hidden="true"
        className="block"
      >
        {bits.split('').map((bit, i) =>
          bit === '1' ? (
            <rect
              key={i}
              x={quiet + i}
              y={0}
              width={1.02}
              height={guard[i] ? barHeight + 5 : barHeight}
              fill="currentColor"
            />
          ) : null,
        )}
        <g fill="currentColor" style={{ fontFamily: 'var(--font-mono)', fontSize: 8.5 }} textAnchor="middle">
          <text x={quiet / 2} y={barHeight + 11}>
            {code[0]}
          </text>
          {code
            .slice(1, 7)
            .split('')
            .map((char, i) => (
              <text key={`l${i}`} x={quiet + 3 + 3.5 + i * 7} y={barHeight + 11}>
                {char}
              </text>
            ))}
          {code
            .slice(7)
            .split('')
            .map((char, i) => (
              <text key={`r${i}`} x={quiet + 50 + 3.5 + i * 7} y={barHeight + 11}>
                {char}
              </text>
            ))}
        </g>
      </svg>
    </span>
  );
}
