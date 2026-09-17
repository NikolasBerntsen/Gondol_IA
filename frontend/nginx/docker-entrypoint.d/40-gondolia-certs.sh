#!/bin/sh
# GondolIA - certificados HTTPS para uso local (celulares en la misma red WiFi).
#
# - CA local estable (gondolia-ca.crt/.key, 10 años): se crea una sola vez y se conserva en certs/,
#   así quien ya la instaló en el celular no tiene que volver a hacerlo.
# - Certificado de servidor (server.crt/.key, 825 días) firmado por esa CA, con SAN:
#   localhost, 127.0.0.1, ::1, LAN_IPS y CERT_HOSTNAMES (separados por coma o espacio).
#   Se regenera si cambia el conjunto de SAN, si cambió la CA, si falta, si no valida
#   contra la CA o si vence en menos de 30 días.
set -eu

CERT_DIR="${GONDOLIA_CERT_DIR:-/etc/nginx/certs}"
CA_DAYS=3650
SERVER_DAYS=825
RENEW_BEFORE_SECONDS=2592000

CA_KEY="$CERT_DIR/gondolia-ca.key"
CA_CRT="$CERT_DIR/gondolia-ca.crt"
SERVER_KEY="$CERT_DIR/server.key"
SERVER_CRT="$CERT_DIR/server.crt"
STATE_FILE="$CERT_DIR/server.san"

log() { echo "gondolia-certs: $*"; }

is_ipv4() {
    printf '%s\n' "$1" | awk -F. '
        NF != 4 { exit 1 }
        { for (i = 1; i <= 4; i++) if ($i !~ /^[0-9]+$/ || length($i) > 3 || $i + 0 > 255) exit 1 }
    '
}

is_ipv6() {
    case "$1" in
        *:*) printf '%s\n' "$1" | grep -Eq '^[0-9A-Fa-f:.]+$' ;;
        *) return 1 ;;
    esac
}

is_hostname() {
    # Todo numérico (p. ej. "999.1.1.1") no es un hostname: sería una IPv4 inválida.
    printf '%s\n' "$1" | grep -Eq '^[0-9.]+$' && return 1
    [ "${#1}" -le 253 ] && printf '%s\n' "$1" | grep -Eq '^(\*\.)?[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*$'
}

same_public_key() {
    # $1 = certificado, $2 = clave privada
    cert_pub=$(openssl x509 -in "$1" -noout -pubkey 2>/dev/null) || return 1
    key_pub=$(openssl pkey -in "$2" -pubout 2>/dev/null) || return 1
    [ -n "$cert_pub" ] && [ "$cert_pub" = "$key_pub" ]
}

# Lista canónica de SAN (ordenada y sin duplicados) para detectar cambios.
build_san_list() {
    set -f
    {
        echo "DNS:localhost"
        echo "IP:127.0.0.1"
        echo "IP:::1"
        for token in $(printf '%s %s' "${LAN_IPS:-}" "${CERT_HOSTNAMES:-}" | tr ',;\r\t' '    '); do
            [ -n "$token" ] || continue
            if is_ipv4 "$token"; then
                echo "IP:$token"
            elif is_ipv6 "$token"; then
                echo "IP:$(printf '%s' "$token" | tr 'A-F' 'a-f')"
            elif is_hostname "$token"; then
                echo "DNS:$(printf '%s' "$token" | tr 'A-Z' 'a-z')"
            else
                log "aviso: se ignora \"$token\" (no es una IP ni un hostname válido)" >&2
            fi
        done
    } | sort -u | awk 'NF { printf "%s%s", (n++ ? "," : ""), $0 }'
    set +f
}

run_openssl() {
    if ! openssl "$@" 2>"$WORK_DIR/openssl.err"; then
        log "error: falló 'openssl $1':" >&2
        cat "$WORK_DIR/openssl.err" >&2
        exit 1
    fi
}

mkdir -p "$CERT_DIR"
WORK_DIR=$(mktemp -d "$CERT_DIR/.tmp-gondolia-certs.XXXXXX")
trap 'rm -rf "$WORK_DIR"' EXIT
trap 'exit 1' INT TERM

# ---------------------------------------------------------------------------
# CA local
# ---------------------------------------------------------------------------
ca_valid=1
if [ ! -s "$CA_KEY" ] || [ ! -s "$CA_CRT" ]; then
    ca_valid=0
elif ! same_public_key "$CA_CRT" "$CA_KEY"; then
    log "aviso: la clave de la CA no corresponde al certificado; se genera una CA nueva"
    ca_valid=0
elif ! openssl x509 -in "$CA_CRT" -noout -checkend "$RENEW_BEFORE_SECONDS" >/dev/null 2>&1; then
    log "aviso: la CA local vence en menos de 30 días; se genera una CA nueva"
    ca_valid=0
fi

if [ "$ca_valid" -eq 0 ]; then
    ca_id=$(openssl rand -hex 3 | tr 'a-f' 'A-F')
    cat > "$WORK_DIR/ca.cnf" <<EOF
[req]
distinguished_name = dn
x509_extensions    = v3_ca
prompt             = no
utf8               = yes

[dn]
O  = GondolIA
OU = Desarrollo local
CN = GondolIA CA local $ca_id

[v3_ca]
basicConstraints       = critical, CA:TRUE, pathlen:0
keyUsage               = critical, keyCertSign, cRLSign
subjectKeyIdentifier   = hash
authorityKeyIdentifier = keyid:always
EOF
    run_openssl req -x509 -new -newkey rsa:2048 -nodes -sha256 -days "$CA_DAYS" \
        -config "$WORK_DIR/ca.cnf" -keyout "$WORK_DIR/ca.key" -out "$WORK_DIR/ca.crt"
    chmod 600 "$WORK_DIR/ca.key"
    chmod 644 "$WORK_DIR/ca.crt"
    mv -f "$WORK_DIR/ca.key" "$CA_KEY"
    mv -f "$WORK_DIR/ca.crt" "$CA_CRT"
    rm -f "$STATE_FILE"
    log "CA local creada (GondolIA CA local $ca_id). Si ya habías instalado otra CA de GondolIA en el celular, instalá esta nueva."
fi

# ---------------------------------------------------------------------------
# Certificado del servidor
# ---------------------------------------------------------------------------
SAN_LIST=$(build_san_list)
CA_FINGERPRINT=$(openssl x509 -in "$CA_CRT" -noout -fingerprint -sha256 | cut -d= -f2)
DESIRED_STATE="ca=$CA_FINGERPRINT;san=$SAN_LIST"

reason=""
if [ ! -s "$SERVER_KEY" ] || [ ! -s "$SERVER_CRT" ]; then
    reason="no existe"
elif [ ! -s "$STATE_FILE" ] || [ "$(cat "$STATE_FILE")" != "$DESIRED_STATE" ]; then
    reason="cambiaron los nombres/IPs o la CA"
elif ! openssl verify -CAfile "$CA_CRT" "$SERVER_CRT" >/dev/null 2>&1; then
    reason="no valida contra la CA local"
elif ! same_public_key "$SERVER_CRT" "$SERVER_KEY"; then
    reason="la clave no corresponde al certificado"
elif ! openssl x509 -in "$SERVER_CRT" -noout -checkend "$RENEW_BEFORE_SECONDS" >/dev/null 2>&1; then
    reason="vence en menos de 30 días"
fi

if [ -n "$reason" ]; then
    log "generando certificado del servidor ($reason)"
    cat > "$WORK_DIR/server.cnf" <<EOF
[req]
distinguished_name = dn
prompt             = no
utf8               = yes

[dn]
O  = GondolIA
CN = GondolIA servidor local

[v3_server]
basicConstraints       = critical, CA:FALSE
keyUsage               = critical, digitalSignature, keyEncipherment
extendedKeyUsage       = serverAuth
subjectKeyIdentifier   = hash
authorityKeyIdentifier = keyid, issuer
subjectAltName         = $SAN_LIST
EOF
    run_openssl req -new -newkey rsa:2048 -nodes -sha256 -config "$WORK_DIR/server.cnf" \
        -keyout "$WORK_DIR/server.key" -out "$WORK_DIR/server.csr"
    run_openssl x509 -req -sha256 -days "$SERVER_DAYS" -in "$WORK_DIR/server.csr" \
        -CA "$CA_CRT" -CAkey "$CA_KEY" -set_serial "0x$(openssl rand -hex 16)" \
        -extfile "$WORK_DIR/server.cnf" -extensions v3_server -out "$WORK_DIR/server.crt"
    run_openssl verify -CAfile "$CA_CRT" "$WORK_DIR/server.crt" >/dev/null
    chmod 600 "$WORK_DIR/server.key"
    chmod 644 "$WORK_DIR/server.crt"
    mv -f "$WORK_DIR/server.key" "$SERVER_KEY"
    mv -f "$WORK_DIR/server.crt" "$SERVER_CRT"
    printf '%s' "$DESIRED_STATE" > "$STATE_FILE"
    log "certificado listo para: $SAN_LIST"
else
    log "certificado vigente para: $SAN_LIST"
fi

# En Linux, que los archivos queden a nombre del dueño de certs/ (así ./start.sh stop --borrar puede borrarlos).
owner=$(stat -c '%u:%g' "$CERT_DIR" 2>/dev/null || echo "")
if [ -n "$owner" ] && [ "$owner" != "0:0" ]; then
    chown "$owner" "$CA_KEY" "$CA_CRT" "$SERVER_KEY" "$SERVER_CRT" "$STATE_FILE" 2>/dev/null || true
fi
