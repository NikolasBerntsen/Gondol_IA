#!/usr/bin/env bash
# =============================================================================
# GondolIA · comprobación de la configuración de nginx
#
#   bash frontend/nginx/test-config.sh
#
# Levanta un nginx de prueba con la MISMA plantilla y los MISMOS snippets que la imagen del
# frontend, con un "backend" falso que devuelve los headers que recibió, y verifica:
#
#   1. la configuración compila (`nginx -t`) en los dos modos (borde y detrás de un proxy TLS);
#   2. detrás de Caddy (BEHIND_PROXY=1) se borra el header Origin de las requests del propio
#      frontend. Si no se borrara, el backend las tomaría por CORS y respondería
#      403 "Invalid CORS request": es el error que dejó el sitio publicado sin poder iniciar sesión;
#   3. un origen externo sí llega al backend, que aplica su política de CORS;
#   4. X-Forwarded-Proto y X-Forwarded-Port llevan lo que ve el navegador (https/443), no el salto
#      interno por HTTP;
#   5. como borde (BEHIND_PROXY=0) no se confía en el X-Forwarded-Proto que mande un cliente.
#
# Necesita nginx con el módulo njs, openssl y curl. En Ubuntu/Debian:
#   sudo apt-get install -y nginx-light libnginx-mod-http-js
#
# Para no pisar puertos privilegiados ni un nginx que ya esté corriendo, la copia de prueba
# escucha en 18080/18443 y el backend falso en 18081; el resto de la configuración es literal.
# =============================================================================
set -euo pipefail

HTTP_PORT=18080
HTTPS_PORT=18443
BACKEND_PORT=18081

NGINX_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="$(mktemp -d)"
NGINX_BIN="${NGINX_BIN:-$(command -v nginx || true)}"
JS_MODULE="${JS_MODULE:-}"
FAILURES=0

cleanup() {
  [ -f "$WORK/nginx.pid" ] && "$NGINX_BIN" -p "$WORK" -c "$WORK/nginx.conf" -s quit 2>/dev/null || true
  sleep 0.2
  rm -rf "$WORK"
}
trap cleanup EXIT

die()  { printf '\033[1;31m✗\033[0m %s\n' "$*" >&2; exit 1; }
ok()   { printf '\033[1;32m✓\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m✗\033[0m %s\n' "$*" >&2; FAILURES=$((FAILURES + 1)); }

[ -n "$NGINX_BIN" ] || die "No encontré nginx. Instalalo con: sudo apt-get install -y nginx-light libnginx-mod-http-js"

if [ -z "$JS_MODULE" ]; then
  for candidate in /usr/lib/nginx/modules/ngx_http_js_module.so /usr/local/nginx/modules/ngx_http_js_module.so; do
    [ -f "$candidate" ] && JS_MODULE="$candidate" && break
  done
fi
[ -n "$JS_MODULE" ] || die "Falta el módulo njs de nginx (paquete libnginx-mod-http-js)."

# La imagen usa nginx 1.27, donde HTTP/2 se activa con `http2 on;`. Los nginx anteriores a 1.25.1
# (el de Ubuntu 24.04, por ejemplo) no conocen esa directiva: se quita solo para la copia de prueba,
# porque nada de lo que se comprueba acá depende de HTTP/2.
NGINX_VERSION="$("$NGINX_BIN" -v 2>&1 | sed -n 's#.*nginx/\([0-9.]*\).*#\1#p')"
DROP_HTTP2=0
if [ -n "$NGINX_VERSION" ] && [ "$(printf '%s\n1.25.1\n' "$NGINX_VERSION" | sort -V | head -1)" = "$NGINX_VERSION" ] \
   && [ "$NGINX_VERSION" != "1.25.1" ]; then
  DROP_HTTP2=1
  printf '\033[1;33m!\033[0m nginx %s: se omite "http2 on;" (la imagen usa 1.27).\n' "$NGINX_VERSION"
fi

# --------------------------------------------------------------------------- árbol de prueba
mkdir -p "$WORK/snippets" "$WORK/njs" "$WORK/certs" "$WORK/html" "$WORK/logs" "$WORK/tmp"
cp "$NGINX_DIR"/snippets/*.conf "$WORK/snippets/"
cp "$NGINX_DIR"/njs/*.js "$WORK/njs/"
echo '<!doctype html><title>test</title>' > "$WORK/html/index.html"
echo 'CA de prueba' > "$WORK/certs/gondolia-ca.crt"
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj '/CN=localhost' \
  -keyout "$WORK/certs/server.key" -out "$WORK/certs/server.crt" 2>/dev/null

# Los snippets se copian tal cual; solo se reapunta la ruta base y la dirección del backend
# (en la imagen es el contenedor 'backend', acá el servidor falso de este mismo nginx).
sed -i "s#/etc/nginx/#$WORK/#g; s#http://backend:8080#http://127.0.0.1:$BACKEND_PORT#g" "$WORK"/snippets/*.conf

cat > "$WORK/backend.conf" <<EOF
# Backend falso: devuelve los headers que le llegaron para poder comprobarlos.
server {
    listen 127.0.0.1:$BACKEND_PORT;
    location / {
        default_type text/plain;
        return 200 "origin=[\$http_origin] proto=[\$http_x_forwarded_proto] port=[\$http_x_forwarded_port] host=[\$http_host]\n";
    }
}
EOF

# Genera conf.d/gondolia.conf como lo hace el entrypoint de la imagen (envsubst de esas tres
# variables) y deja la copia escuchando en puertos altos.
generate_conf() {
  local behind_proxy="$1"
  sed -e "s#\${HTTPS_PORT}#$HTTPS_PORT#g" \
      -e "s#\${BEHIND_PROXY}#$behind_proxy#g" \
      -e "s#\${NGINX_LOCAL_RESOLVERS}#127.0.0.11#g" \
      -e "s#/etc/nginx/#$WORK/#g" \
      -e "s#^\( *\)listen 80 default_server;#\1listen 127.0.0.1:$HTTP_PORT default_server;#" \
      -e "s#^\( *\)listen 443 ssl default_server;#\1listen 127.0.0.1:$HTTPS_PORT ssl default_server;#" \
      "$NGINX_DIR/templates/gondolia.conf.template" > "$WORK/gondolia.conf"
  [ "$DROP_HTTP2" = "1" ] && sed -i '/^ *http2 on;$/d' "$WORK/gondolia.conf"

  cat > "$WORK/nginx.conf" <<EOF
load_module $JS_MODULE;
worker_processes 1;
pid $WORK/nginx.pid;
error_log $WORK/logs/error.log warn;
events { worker_connections 64; }
http {
    include /etc/nginx/mime.types;
    access_log off;
    client_body_temp_path $WORK/tmp;
    proxy_temp_path $WORK/tmp;
    fastcgi_temp_path $WORK/tmp;
    uwsgi_temp_path $WORK/tmp;
    scgi_temp_path $WORK/tmp;
    include $WORK/backend.conf;
    include $WORK/gondolia.conf;
}
EOF
}

start_nginx() {
  "$NGINX_BIN" -p "$WORK" -c "$WORK/nginx.conf" -t >/dev/null 2>"$WORK/logs/test.log" \
    || { cat "$WORK/logs/test.log" >&2; die "La configuración de nginx no compila."; }
  "$NGINX_BIN" -p "$WORK" -c "$WORK/nginx.conf"
  for _ in $(seq 1 40); do
    curl -s -o /dev/null --max-time 2 "http://127.0.0.1:$HTTP_PORT/nginx-health" && return 0
    sleep 0.25
  done
  die "El nginx de prueba no llegó a responder."
}

stop_nginx() {
  "$NGINX_BIN" -p "$WORK" -c "$WORK/nginx.conf" -s quit 2>/dev/null || true
  for _ in $(seq 1 40); do
    [ -f "$WORK/nginx.pid" ] || return 0
    sleep 0.25
  done
}

# expect <descripción> <esperado> <recibido>
expect() {
  if [ "$3" = "$2" ]; then
    ok "$1"
  else
    fail "$1"
    printf '    esperaba: %s\n    recibió : %s\n' "$2" "$3" >&2
  fi
}

field() { # field <clave> <respuesta>
  printf '%s' "$2" | sed -n "s/.*$1=\[\([^]]*\)\].*/\1/p"
}

ask() { # ask <host> [headers...]
  local host="$1"; shift
  curl -s --max-time 5 -H "Host: $host" "$@" "http://127.0.0.1:$HTTP_PORT/api/ping"
}

# --------------------------------------------------------------------------- detrás de Caddy
echo "── BEHIND_PROXY=1 (producción: Caddy termina el TLS) ────────────────────"
generate_conf 1
start_nginx

respuesta="$(ask gondolia.ejemplo -H 'Origin: https://gondolia.ejemplo' -H 'X-Forwarded-Proto: https')"
expect "el Origin del propio frontend no llega al backend (si llegara: 403 Invalid CORS request)" \
  "" "$(field origin "$respuesta")"
expect "X-Forwarded-Proto lleva el esquema del navegador" "https" "$(field proto "$respuesta")"
expect "X-Forwarded-Port lleva el puerto del navegador" "443" "$(field port "$respuesta")"
expect "el Host público llega tal cual" "gondolia.ejemplo" "$(field host "$respuesta")"

externo="$(ask gondolia.ejemplo -H 'Origin: https://evil.ejemplo' -H 'X-Forwarded-Proto: https')"
expect "un origen externo sí llega al backend, que aplica su política de CORS" \
  "https://evil.ejemplo" "$(field origin "$externo")"

stop_nginx

# --------------------------------------------------------------------------- nginx como borde
echo
echo "── BEHIND_PROXY=0 (local: nginx es el borde) ────────────────────────────"
generate_conf 0
start_nginx

local_resp="$(ask "localhost:$HTTP_PORT" -H "Origin: http://localhost:$HTTP_PORT")"
expect "el Origin del propio frontend tampoco llega al backend" "" "$(field origin "$local_resp")"
expect "X-Forwarded-Proto es el esquema real" "http" "$(field proto "$local_resp")"
expect "X-Forwarded-Port sale del Host" "$HTTP_PORT" "$(field port "$local_resp")"

spoof="$(ask "localhost:$HTTP_PORT" -H "Origin: https://localhost:$HTTP_PORT" -H 'X-Forwarded-Proto: https')"
expect "como borde no se confía en el X-Forwarded-Proto del cliente" \
  "https://localhost:$HTTP_PORT" "$(field origin "$spoof")"
expect "y X-Forwarded-Proto se reescribe con el esquema real" "http" "$(field proto "$spoof")"

echo
if [ "$FAILURES" -gt 0 ]; then
  die "$FAILURES comprobación(es) fallaron."
fi
ok "La configuración de nginx pasa todas las comprobaciones."
