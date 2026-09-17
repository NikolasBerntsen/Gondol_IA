import type { CSSProperties } from 'react';
import { cn } from '@/lib/cn';
import { formatMoney, splitMoney } from '@/lib/format';

export interface PriceTagProps {
  price: number;
  /** `sm` mosaico · `md` cobro · `lg` total del POS · `xl` pantalla completa. */
  size?: 'sm' | 'md' | 'lg' | 'xl';
  /** Rótulo superior, p. ej. "TOTAL". */
  label?: string;
  /** "c/u", "x 12 u."… */
  unit?: string;
  /** Precio por unidad de medida (exhibición de precios): "$ 2.150,00 x litro". */
  perUnit?: string;
  /** Precio de lista tachado (ofertas por vencimiento). */
  listPrice?: number;
  /** Franja superior de oferta: "-20% VTO CERCANO". */
  offer?: string;
  className?: string;
}

const SIZE = {
  sm: { font: 22, padL: 22, padR: 10, padY: 6, holeX: 10, holeR: 3 },
  md: { font: 34, padL: 28, padR: 14, padY: 8, holeX: 12, holeR: 4 },
  lg: { font: 48, padL: 34, padR: 18, padY: 10, holeX: 15, holeR: 5 },
  xl: { font: 56, padL: 38, padR: 20, padY: 12, holeX: 16, holeR: 5.5 },
} as const;

/**
 * Etiqueta de góndola: amarillo de precio, número en Bricolage con los centavos en superíndice
 * y agujero perforado a la izquierda. Es la **única superficie amarilla grande** de una pantalla.
 *
 * Usar en: total del POS, precio del producto en la ficha, ofertas por vencimiento.
 * No usar para montos contables (valor de inventario, MRR, ventas del día).
 */
export function PriceTag({ price, size = 'md', label, unit, perUnit, listPrice, offer, className }: PriceTagProps) {
  const s = SIZE[size];
  const { int, cents } = splitMoney(price);
  return (
    <div
      className={cn(
        'gd-pricetag relative inline-flex max-w-full flex-col overflow-hidden rounded-tag bg-accent text-accent-foreground',
        className,
      )}
      style={
        {
          '--hole-x': `${s.holeX}px`,
          '--hole-r': `${s.holeR}px`,
          paddingLeft: s.padL,
          paddingRight: s.padR,
          paddingTop: offer ? 0 : s.padY,
          paddingBottom: s.padY,
        } as CSSProperties
      }
      role="group"
      aria-label={`${label ? `${label}: ` : ''}${formatMoney(price, { decimals: 2 })}${
        listPrice ? `, antes ${formatMoney(listPrice, { decimals: 2 })}` : ''
      }${offer ? `, ${offer}` : ''}`}
    >
      {offer && (
        <div
          className="mb-1.5 bg-crit px-2 py-0.5 font-mono text-[11px] font-semibold uppercase leading-4 tracking-[0.04em] text-crit-foreground"
          style={{ marginLeft: -s.padL, marginRight: -s.padR, paddingLeft: s.padL }}
          aria-hidden="true"
        >
          {offer}
        </div>
      )}
      {label && (
        <div aria-hidden="true" className="text-xs font-bold uppercase leading-4 tracking-[0.08em] opacity-80">
          {label}
        </div>
      )}
      <div
        aria-hidden="true"
        className="flex items-start font-display font-bold leading-none tracking-[-0.02em] tabular-nums"
        style={{ fontSize: s.font }}
      >
        <span style={{ fontSize: '0.46em', marginTop: '0.14em', marginRight: '0.08em' }}>$</span>
        <span>{int}</span>
        <span
          style={{ fontSize: '0.42em', marginTop: '0.1em', marginLeft: '0.06em' }}
          className="underline decoration-[0.06em] underline-offset-[0.12em]"
        >
          {cents}
        </span>
        {unit && (
          <span
            className="self-end font-sans font-semibold tracking-normal"
            style={{ fontSize: Math.max(11, s.font * 0.26), marginLeft: '0.3em', marginBottom: '0.08em' }}
          >
            {unit}
          </span>
        )}
      </div>
      {listPrice !== undefined && (
        <div aria-hidden="true" className="mt-1 text-xs font-medium leading-4 opacity-75">
          Antes <span className="line-through">{formatMoney(listPrice, { decimals: 2 })}</span>
        </div>
      )}
      {perUnit && (
        <div aria-hidden="true" className="mt-1 font-mono text-[11px] leading-4 opacity-80">
          {perUnit}
        </div>
      )}
    </div>
  );
}
