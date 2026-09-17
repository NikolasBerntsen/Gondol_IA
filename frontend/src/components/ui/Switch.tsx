import * as SwitchPrimitives from '@radix-ui/react-switch';
import { forwardRef, type ComponentPropsWithoutRef, type ElementRef } from 'react';
import { cn } from '@/lib/cn';

export interface SwitchProps extends ComponentPropsWithoutRef<typeof SwitchPrimitives.Root> {
  /** `sm` 20×36 (defecto) · `md` 24×44 (táctil). */
  size?: 'sm' | 'md';
}

/**
 * Góndola UI · Switch (Radix). Encendido: riel `primary` con pulgar `primary-foreground`.
 * Apagado: riel `muted` con borde y pulgar `muted-foreground` (visible también en tema oscuro).
 */
export const Switch = forwardRef<ElementRef<typeof SwitchPrimitives.Root>, SwitchProps>(function Switch(
  { className, size = 'sm', ...props },
  ref,
) {
  const md = size === 'md';
  return (
    <SwitchPrimitives.Root
      ref={ref}
      className={cn(
        'peer inline-flex shrink-0 cursor-pointer items-center rounded-full border-2 transition-colors',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background',
        'disabled:cursor-not-allowed disabled:opacity-50',
        'data-[state=checked]:border-primary data-[state=checked]:bg-primary',
        'data-[state=unchecked]:border-muted-foreground data-[state=unchecked]:bg-muted',
        md ? 'h-6 w-11' : 'h-5 w-9',
        className,
      )}
      {...props}
    >
      <SwitchPrimitives.Thumb
        className={cn(
          'pointer-events-none block rounded-full ring-0 transition-transform',
          'data-[state=checked]:bg-primary-foreground data-[state=unchecked]:bg-muted-foreground',
          'data-[state=unchecked]:translate-x-0',
          md ? 'h-5 w-5 data-[state=checked]:translate-x-5' : 'h-4 w-4 data-[state=checked]:translate-x-4',
        )}
      />
    </SwitchPrimitives.Root>
  );
});
