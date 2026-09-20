import { useEffect, useState } from 'react';

function matches(query: string): boolean {
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    ? window.matchMedia(query).matches
    : false;
}

/**
 * `true` mientras la consulta de medios se cumple (`useMediaQuery('(min-width: 1024px)')`).
 * Para diferencias que **no** se pueden expresar con clases de Tailwind (p. ej. poner o no un atributo).
 */
export function useMediaQuery(query: string): boolean {
  const [active, setActive] = useState(() => matches(query));

  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return undefined;
    const media = window.matchMedia(query);
    const update = () => setActive(media.matches);
    update();
    media.addEventListener('change', update);
    return () => media.removeEventListener('change', update);
  }, [query]);

  return active;
}
