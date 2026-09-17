/** Confirmación física de una lectura: pitido corto + vibración (SPEC §15.3, carga y POS). */

let audioContext: AudioContext | null = null;

type AudioContextCtor = typeof AudioContext;

function getAudioContext(): AudioContext | null {
  if (typeof window === 'undefined') return null;
  const Ctor: AudioContextCtor | undefined =
    window.AudioContext ?? (window as unknown as { webkitAudioContext?: AudioContextCtor }).webkitAudioContext;
  if (!Ctor) return null;
  if (!audioContext) audioContext = new Ctor();
  return audioContext;
}

/** Pitido corto de confirmación. No falla nunca: si el navegador no deja, no suena y listo. */
export function beep(durationMs = 90, frequency = 1080): void {
  try {
    const context = getAudioContext();
    if (!context) return;
    if (context.state === 'suspended') void context.resume();
    const oscillator = context.createOscillator();
    const gain = context.createGain();
    oscillator.type = 'square';
    oscillator.frequency.value = frequency;
    gain.gain.setValueAtTime(0.0001, context.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.12, context.currentTime + 0.01);
    gain.gain.exponentialRampToValueAtTime(0.0001, context.currentTime + durationMs / 1000);
    oscillator.connect(gain).connect(context.destination);
    oscillator.start();
    oscillator.stop(context.currentTime + durationMs / 1000 + 0.02);
  } catch {
    // Sin audio disponible: la lectura igual se confirma en pantalla.
  }
}

/** Vibración corta (celulares). Sin soporte, no hace nada. */
export function vibrate(pattern: number | number[] = 60): void {
  try {
    navigator.vibrate?.(pattern);
  } catch {
    // Algunos navegadores lanzan si la página no está visible.
  }
}

/** Pitido + vibración: la confirmación estándar de un código leído. */
export function scanFeedback(): void {
  beep();
  vibrate();
}
