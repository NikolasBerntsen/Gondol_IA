let activeLocks = 0;
let previousOverflow = '';

/** Bloquea el scroll del body (modales, drawer). Soporta bloqueos anidados. Devuelve la función que lo libera. */
export function lockBodyScroll(): () => void {
  if (activeLocks === 0) {
    previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
  }
  activeLocks += 1;
  let released = false;
  return () => {
    if (released) return;
    released = true;
    activeLocks -= 1;
    if (activeLocks === 0) document.body.style.overflow = previousOverflow;
  };
}
