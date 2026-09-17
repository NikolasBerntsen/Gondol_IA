import { forwardRef, type TextareaHTMLAttributes } from 'react';
import { cn } from '@/lib/cn';
import { controlBaseClasses, controlInvalidClasses, isAriaInvalid } from './Input';

export interface TextareaProps extends TextareaHTMLAttributes<HTMLTextAreaElement> {
  invalid?: boolean;
}

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaProps>(function Textarea(
  { invalid, className, rows = 3, ...props },
  ref,
) {
  const hasError = invalid || isAriaInvalid(props['aria-invalid']);
  return (
    <textarea
      ref={ref}
      rows={rows}
      aria-invalid={hasError || undefined}
      className={cn(
        controlBaseClasses,
        'min-h-[72px] px-3 py-2 text-md sm:text-base',
        hasError && controlInvalidClasses,
        className,
      )}
      {...props}
    />
  );
});
