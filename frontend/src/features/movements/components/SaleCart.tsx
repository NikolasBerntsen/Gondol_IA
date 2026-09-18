import { Trash2 } from 'lucide-react';
import { BarcodeDigits, StockStatusPill } from '@/components/gondola';
import { Button, Input, QtyStepper } from '@/components/ui';
import { formatMoney } from '@/lib/format';
import type { ProductPick } from '../api';

/** Línea del carrito de la venta manual. */
export interface CartLine {
  product: ProductPick;
  quantity: number;
  /** Precio unitario editado por el usuario; `null` = el de lista del producto. */
  unitPrice: number | null;
  /** Stock vendible en la sucursal elegida en el momento de agregarlo. */
  stock: number;
}

export interface SaleCartProps {
  lines: CartLine[];
  onQuantityChange: (productId: number, quantity: number) => void;
  onPriceChange: (productId: number, unitPrice: number | null) => void;
  onRemove: (productId: number) => void;
  disabled?: boolean;
}

/** Precio efectivo de una línea (el editado o el de lista). */
export function linePrice(line: CartLine): number {
  return line.unitPrice ?? line.product.salePrice;
}

/** Total de la línea sin contar descuentos por lote (los aplica el backend según la rotación). */
export function lineTotal(line: CartLine): number {
  return linePrice(line) * line.quantity;
}

/** Carrito de la venta manual: cantidad, precio unitario editable y aviso de stock insuficiente. */
export function SaleCart({ lines, onQuantityChange, onPriceChange, onRemove, disabled }: SaleCartProps) {
  return (
    <ul className="divide-y divide-border">
      {lines.map((line) => {
        const short = line.quantity > line.stock;
        return (
          <li key={line.product.id} className="flex flex-col gap-2 px-3 py-3 sm:px-4">
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <p className="truncate text-base font-semibold text-foreground">{line.product.name}</p>
                <span className="mt-0.5 flex flex-wrap items-center gap-x-2 gap-y-1">
                  {line.product.barcode ? <BarcodeDigits code={line.product.barcode} digitsOnly /> : null}
                  <span className="text-xs tabular-nums text-muted-foreground">{line.stock} u. vendibles</span>
                  {short ? <StockStatusPill status={line.stock <= 0 ? 'OUT' : 'LOW'} /> : null}
                </span>
              </div>
              <Button
                variant="ghost"
                size="icon-sm"
                aria-label={`Quitar ${line.product.name} de la venta`}
                disabled={disabled}
                onClick={() => onRemove(line.product.id)}
              >
                <Trash2 className="h-4 w-4" aria-hidden="true" />
              </Button>
            </div>

            <div className="flex flex-wrap items-end justify-between gap-3">
              <div className="flex flex-wrap items-center gap-3">
                <QtyStepper
                  value={line.quantity}
                  onChange={(quantity) => onQuantityChange(line.product.id, quantity)}
                  label={`Cantidad de ${line.product.name}`}
                  min={1}
                  max={100000}
                  size="lg"
                  disabled={disabled}
                />
                <label className="flex items-center gap-1.5 text-sm text-muted-foreground">
                  <span className="whitespace-nowrap">Precio</span>
                  <Input
                    type="number"
                    min={0}
                    step="0.01"
                    inputSize="sm"
                    aria-label={`Precio unitario de ${line.product.name}`}
                    value={line.unitPrice ?? ''}
                    placeholder={String(line.product.salePrice)}
                    disabled={disabled}
                    onChange={(event) => {
                      const raw = event.target.value;
                      onPriceChange(line.product.id, raw === '' ? null : Number(raw));
                    }}
                    className="w-28 tabular-nums"
                  />
                </label>
              </div>
              <span className="font-display text-md tabular-nums text-foreground">{formatMoney(lineTotal(line))}</span>
            </div>

            {short ? (
              <p className="text-sm text-warn-ink" role="alert">
                Solo hay {line.stock} u. vendibles: las {line.quantity - line.stock} restantes se van a registrar
                como venta sin stock.
              </p>
            ) : null}
          </li>
        );
      })}
    </ul>
  );
}
