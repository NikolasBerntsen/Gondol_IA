#!/usr/bin/env bash
# =============================================================================
# GondolIA · despliegue en la VM de Oracle Cloud
#
#   bash deploy/deploy.sh update   → build + up conservando la base de datos (lo normal)
#   bash deploy/deploy.sh reset    → ⚠ BORRA la base de datos y vuelve a sembrar la demo
#   bash deploy/deploy.sh status   → estado de los contenedores y salud
#   bash deploy/deploy.sh logs [servicio]
#   bash deploy/deploy.sh help
#
# Lo ejecuta GitHub Actions (.github/workflows/deploy.yml) en cada push a main, y
# también se puede correr a mano dentro de la VM.
#
# Este stack NO publica puertos: la única puerta de entrada es el Caddy del proyecto
# BioTrust (el dueño de 80/443 en esta VM), que llega por la red compartida 'proxy'.
# El script se encarga de que esa red exista y de que el Caddy tenga el bloque de
# gondolia (lo agrega y recarga si falta).
# =============================================================================
set -euo pipefail

cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

COMPOSE_FILE="docker-compose.prod.yml"
ENV_FILE=".env"
CADDY_CONTAINER="${CADDY_CONTAINER:-biotrust-caddy}"
CADDYFILE="${CADDYFILE:-$HOME/Biotrust-sistema-de-asistencas/deploy/Caddyfile}"
PUBLIC_HOST="${PUBLIC_HOST:-gondolia.144-22-138-149.sslip.io}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-900}"

log()  { printf '\n\033[1;32m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m!\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m✗\033[0m %s\n' "$*" >&2; exit 1; }

command -v docker >/dev/null 2>&1 || die "Docker no está instalado en la VM."
docker compose version >/dev/null 2>&1 || die "Falta el plugin 'docker compose' (v2)."

rand() { head -c 48 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c "${1:-48}"; }

# --------------------------------------------------------------------------- .env
# Vive SOLO en la VM: guarda los secretos y sobrevive a los despliegues (rsync lo excluye).
ensure_env() {
  if [ ! -f "$ENV_FILE" ]; then
    log "Creando $ENV_FILE (primer despliegue)"
    cat > "$ENV_FILE" <<EOF
# GondolIA · configuración de la VM (generado por deploy/deploy.sh). No se sube al repo.
POSTGRES_DB=gondolia
POSTGRES_USER=gondolia
POSTGRES_PASSWORD=$(rand 40)
JWT_SECRET=$(rand 72)
JWT_EXPIRATION_HOURS=12
APP_TIMEZONE=America/Argentina/Buenos_Aires
APP_BOOTSTRAP_OWNER_EMAIL=dueno@gondolia.app
APP_BOOTSTRAP_OWNER_PASSWORD=Gondolia2026!
APP_OPENFOODFACTS_ENABLED=true
# Datos de demostración (los controla la variable SEED_DEMO_DATA del repo en GitHub).
APP_SEED_DEMO=true
EOF
    chmod 600 "$ENV_FILE"
  fi

  # SEED_DEMO_DATA (variable del repo) manda sobre APP_SEED_DEMO del .env.
  if [ -n "${SEED_DEMO_DATA:-}" ]; then
    local value="false"
    [ "$SEED_DEMO_DATA" = "true" ] && value="true"
    if grep -q '^APP_SEED_DEMO=' "$ENV_FILE"; then
      sed -i "s/^APP_SEED_DEMO=.*/APP_SEED_DEMO=$value/" "$ENV_FILE"
    else
      echo "APP_SEED_DEMO=$value" >> "$ENV_FILE"
    fi
    log "APP_SEED_DEMO=$value (por la variable SEED_DEMO_DATA del repositorio)"
  fi
}

ensure_proxy_network() {
  if ! docker network inspect proxy >/dev/null 2>&1; then
    log "Creando la red compartida 'proxy'"
    docker network create proxy >/dev/null
  fi
}

# ------------------------------------------------------------------- Caddy (ingreso)
# El Caddy de BioTrust es el único con 80/443. Acá se agrega (si falta) el bloque que
# publica GondolIA, igual que el que ya existe para la tesis.
ensure_caddy_route() {
  if ! docker ps --format '{{.Names}}' | grep -qx "$CADDY_CONTAINER"; then
    warn "No encontré el contenedor '$CADDY_CONTAINER': GondolIA queda levantada pero sin salida a internet."
    return 0
  fi
  # El Caddy tiene que estar en la red 'proxy' para resolver 'gondolia-web'.
  if ! docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}} {{end}}' "$CADDY_CONTAINER" | grep -qw proxy; then
    log "Conectando $CADDY_CONTAINER a la red 'proxy'"
    docker network connect proxy "$CADDY_CONTAINER" || warn "No pude conectar $CADDY_CONTAINER a 'proxy'."
  fi
  if [ ! -f "$CADDYFILE" ]; then
    warn "No encontré el Caddyfile en $CADDYFILE: agregá el bloque de gondolia a mano (ver deploy/README-DESPLIEGUE.md)."
    return 0
  fi
  if grep -q "$PUBLIC_HOST" "$CADDYFILE"; then
    log "El Caddyfile ya publica $PUBLIC_HOST"
  else
    log "Agregando $PUBLIC_HOST al Caddyfile de $CADDY_CONTAINER"
    cp "$CADDYFILE" "$CADDYFILE.bak.$(date +%Y%m%d%H%M%S)"
    cat >> "$CADDYFILE" <<EOF

# -----------------------------------------------------------------------------
# GondolIA (OTRO proyecto de esta misma VM). Este Caddy es la única puerta de
# entrada del servidor, así que también lo publica. 'gondolia-web' se resuelve por
# el DNS interno de Docker gracias a la red compartida 'proxy'.
# Los WebSocket (/ws) pasan solos: reverse_proxy reenvía el Upgrade tal cual.
# -----------------------------------------------------------------------------
$PUBLIC_HOST {
	encode gzip
	reverse_proxy gondolia-web:80
}
EOF
  fi
  log "Recargando Caddy"
  docker exec "$CADDY_CONTAINER" caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile \
    || warn "No pude recargar Caddy (revisá 'docker logs $CADDY_CONTAINER')."
}

compose() { docker compose -f "$COMPOSE_FILE" "$@"; }

wait_healthy() {
  log "Esperando a que los servicios queden saludables (hasta $((HEALTH_TIMEOUT / 60)) min)"
  local deadline=$((SECONDS + HEALTH_TIMEOUT)) pending
  while [ $SECONDS -lt $deadline ]; do
    pending=""
    for svc in db ai backend web; do
      local cid state
      cid="$(compose ps -q "$svc" 2>/dev/null || true)"
      if [ -z "$cid" ]; then pending="$pending $svc(sin contenedor)"; continue; fi
      state="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$cid" 2>/dev/null || echo desconocido)"
      case "$state" in
        healthy|running) ;;
        *) pending="$pending $svc($state)" ;;
      esac
    done
    if [ -z "$pending" ]; then log "Servicios listos ✓"; return 0; fi
    printf '\r  esperando:%s          ' "$pending"
    sleep 5
  done
  printf '\n'
  warn "Se agotó la espera. Últimos registros del backend:"
  compose logs --tail 40 backend || true
  return 1
}

cmd_update() {
  ensure_env
  ensure_proxy_network
  log "Construyendo imágenes (puede tardar varios minutos la primera vez)"
  compose build
  log "Levantando el stack (conservando la base de datos)"
  compose up -d --remove-orphans
  wait_healthy
  ensure_caddy_route
  log "Limpiando imágenes viejas"
  docker image prune -f >/dev/null 2>&1 || true
  log "Listo: https://$PUBLIC_HOST"
}

cmd_reset() {
  ensure_env
  ensure_proxy_network
  warn "RESET: se borra la base de datos y los archivos subidos; se vuelve a sembrar la demo."
  compose down -v --remove-orphans || true
  log "Construyendo imágenes"
  compose build
  compose up -d --remove-orphans
  wait_healthy
  ensure_caddy_route
  docker image prune -f >/dev/null 2>&1 || true
  log "Listo (datos nuevos): https://$PUBLIC_HOST"
}

cmd_status() {
  compose ps
  echo
  for svc in db ai backend web; do
    cid="$(compose ps -q "$svc" 2>/dev/null || true)"
    [ -z "$cid" ] && { echo "$svc: sin contenedor"; continue; }
    echo "$svc: $(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$cid")"
  done
  echo
  echo "URL pública: https://$PUBLIC_HOST"
}

case "${1:-update}" in
  update)  cmd_update ;;
  reset)   cmd_reset ;;
  status)  cmd_status ;;
  logs)    shift; compose logs -f --tail 100 "$@" ;;
  help|-h|--help)
    sed -n '2,18p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    ;;
  *) die "Comando desconocido: $1 (probá: update, reset, status, logs, help)" ;;
esac
