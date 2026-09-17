import { cn } from '@/lib/cn';
import { initials } from '@/lib/format';

const SIZE_CLASSES = {
  sm: 'h-7 w-7 text-[11px]',
  md: 'h-8 w-8 text-xs',
  lg: 'h-10 w-10 text-sm',
  xl: 'h-14 w-14 text-md',
} as const;

export interface AvatarProps {
  name: string | null | undefined;
  size?: keyof typeof SIZE_CLASSES;
  className?: string;
}

/** Círculo con las iniciales del usuario (sin foto: el prototipo no usa avatares con imagen). */
export function Avatar({ name, size = 'md', className }: AvatarProps) {
  return (
    <span
      aria-hidden="true"
      className={cn(
        'inline-grid shrink-0 select-none place-items-center rounded-full bg-primary/[0.12] font-bold text-primary',
        SIZE_CLASSES[size],
        className,
      )}
    >
      {initials(name)}
    </span>
  );
}
