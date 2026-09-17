import { clsx, type ClassValue } from 'clsx';
import { extendTailwindMerge } from 'tailwind-merge';

/**
 * `tailwind-merge` con la escala de Góndola UI: radios por rol (`rounded-control|panel|dialog|tag`),
 * tamaños de texto propios (`text-read`, `text-md`) y sombras (`shadow-pop|sheet`).
 * Sin esto, pasar `className="rounded-tag"` a un componente con `rounded-panel` dejaría las dos clases.
 */
const RADII = ['control', 'panel', 'dialog', 'tag'];

/** `rounded-*` y también sus variantes por lado/esquina (`rounded-t-dialog`, `rounded-bl-tag`…). */
const roundedGroups = Object.fromEntries(
  ['', '-s', '-e', '-t', '-r', '-b', '-l', '-ss', '-se', '-ee', '-es', '-tl', '-tr', '-br', '-bl'].map((side) => [
    `rounded${side}`,
    [{ [`rounded${side}`]: RADII }],
  ]),
);

const twMerge = extendTailwindMerge({
  extend: {
    classGroups: {
      'font-size': [{ text: ['xs', 'sm', 'base', 'read', 'md', 'lg', 'xl', '2xl', '3xl'] }],
      shadow: [{ shadow: ['pop', 'sheet'] }],
      ...roundedGroups,
    },
  },
});

/** Combina clases condicionales y resuelve conflictos de Tailwind (la última gana). */
export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs));
}
