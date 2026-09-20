/*
 * GondolIA - helpers de nginx (njs) para los errores que genera el propio proxy.
 *
 * nginx no tiene ninguna variable con la hora en UTC: $time_iso8601 usa la zona del contenedor
 * (TZ=America/Argentina/Buenos_Aires) y sale como "2026-09-20T01:47:20-03:00". El envelope de error de la API
 * (SPEC.md §Errores y docs/api-foundation.md §4) es siempre un instante UTC terminado en "Z", así que los errores
 * de nginx en /api/ arman el timestamp acá: Date.toISOString() es UTC por definición y no depende de TZ.
 *
 * Formato: "2026-09-20T04:47:20.727Z", el mismo que serializa Jackson para el Instant del backend.
 */

function utcTimestamp(r) {
    return new Date().toISOString();
}

export default { utcTimestamp };
