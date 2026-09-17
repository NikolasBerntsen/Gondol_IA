#!/usr/bin/env bash
# =============================================================================
# GondolIA - arranque del entorno completo con Docker.
# Uso: ./start.sh [start|stop [--borrar|--purge] [-y]|restart|logs [servicio]|status|reset [-y]|db|help]
# Funciona en Git Bash (Windows), Linux y macOS (bash 3.2+).
# =============================================================================

# Si lo ejecutaron con "sh start.sh", volver a lanzarlo con bash.
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

readonly PROJECT="gondolia"
readonly SERVICES=(db ai backend web)
readonly ENV_FILE=".env"
readonly ENV_EXAMPLE=".env.example"
readonly CERTS_DIR="certs"
readonly DB_IMAGE="postgres:16-alpine"

ASSUME_YES=0
TEMP_FILES=()
BG_PID=""
CURSOR_HIDDEN=0

# -----------------------------------------------------------------------------
# Salida
# -----------------------------------------------------------------------------
detect_os() {
    case "$(uname -s 2>/dev/null || echo desconocido)" in
        MINGW* | MSYS* | CYGWIN*) echo windows ;;
        Darwin) echo macos ;;
        Linux)
            if grep -qi microsoft /proc/version 2>/dev/null; then echo wsl; else echo linux; fi
            ;;
        *) echo otro ;;
    esac
}
readonly OS_KIND="$(detect_os)"

if [[ -t 1 ]]; then IS_TTY=1; else IS_TTY=0; fi

if [[ $IS_TTY -eq 1 && -z "${NO_COLOR:-}" ]]; then
    C_RESET=$'\033[0m'
    C_BOLD=$'\033[1m'
    C_DIM=$'\033[2m'
    C_RED=$'\033[31m'
    C_GREEN=$'\033[32m'
    C_YELLOW=$'\033[33m'
    C_CYAN=$'\033[36m'
else
    C_RESET="" C_BOLD="" C_DIM="" C_RED="" C_GREEN="" C_YELLOW="" C_CYAN=""
fi

supports_unicode() {
    local locale="${LC_ALL:-${LC_CTYPE:-${LANG:-}}}"
    if [[ -n "$locale" ]]; then
        [[ "$locale" =~ [Uu][Tt][Ff]-?8 ]]
        return
    fi
    [[ "$OS_KIND" == windows ]] &&
        [[ -n "${WT_SESSION:-}" || "${TERM_PROGRAM:-}" == mintty || "${TERM_PROGRAM:-}" == vscode ]]
}

if supports_unicode; then
    SYM_OK="✔" SYM_ERR="✖" SYM_WARN="⚠" SYM_STEP="➜" SYM_DOT="•"
    SPIN_FRAMES=("⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏")
else
    SYM_OK="OK" SYM_ERR="X" SYM_WARN="!" SYM_STEP=">" SYM_DOT="-"
    SPIN_FRAMES=("|" "/" "-" "\\")
fi

say() { printf '%s\n' "$*"; }
step() { printf '\n%s%s %s%s\n' "$C_CYAN$C_BOLD" "$SYM_STEP" "$*" "$C_RESET"; }
info() { printf '  %s\n' "$*"; }
ok() { printf '%s%s%s %s\n' "$C_GREEN" "$SYM_OK" "$C_RESET" "$*"; }
warn() { printf '%s%s %s%s\n' "$C_YELLOW" "$SYM_WARN" "$*" "$C_RESET" >&2; }
error() { printf '%s%s %s%s\n' "$C_RED$C_BOLD" "$SYM_ERR" "$*" "$C_RESET" >&2; }
die() {
    error "$1"
    shift
    local line
    for line in "$@"; do printf '  %s\n' "$line" >&2; done
    exit 1
}

show_cursor() {
    if [[ $CURSOR_HIDDEN -eq 1 ]]; then
        printf '\033[?25h'
        CURSOR_HIDDEN=0
    fi
}

hide_cursor() {
    if [[ $IS_TTY -eq 1 ]]; then
        printf '\033[?25l'
        CURSOR_HIDDEN=1
    fi
}

cleanup() {
    show_cursor
    if [[ -n "$BG_PID" ]] && kill -0 "$BG_PID" 2>/dev/null; then
        kill "$BG_PID" 2>/dev/null || true
        wait "$BG_PID" 2>/dev/null || true
    fi
    local f
    for f in ${TEMP_FILES[@]+"${TEMP_FILES[@]}"}; do rm -f "$f" 2>/dev/null || true; done
}
trap cleanup EXIT
trap 'show_cursor; printf "\n"; warn "Interrumpido. Los contenedores pueden seguir iniciándose: revisalo con ./start.sh status"; exit 130' INT TERM

make_temp() {
    local f
    f=$(mktemp "${TMPDIR:-/tmp}/$1.XXXXXX")
    TEMP_FILES+=("$f")
    printf '%s' "$f"
}

fmt_duration() {
    local s=$1
    if ((s >= 60)); then printf '%d min %02d s' $((s / 60)) $((s % 60)); else printf '%d s' "$s"; fi
}

fmt_clock() { printf '%02d:%02d' $(($1 / 60)) $(($1 % 60)); }

# -----------------------------------------------------------------------------
# Docker
# -----------------------------------------------------------------------------
dc() { docker compose -p "$PROJECT" "$@"; }

docker_desktop_path() {
    local candidate
    case "$OS_KIND" in
        windows)
            for candidate in \
                "${PROGRAMFILES:-C:\\Program Files}\\Docker\\Docker\\Docker Desktop.exe" \
                "${LOCALAPPDATA:-}\\Programs\\Docker\\Docker\\Docker Desktop.exe"; do
                candidate=$(cygpath -u "$candidate" 2>/dev/null || true)
                if [[ -n "$candidate" && -f "$candidate" ]]; then
                    printf '%s' "$candidate"
                    return 0
                fi
            done
            ;;
        wsl)
            candidate="/mnt/c/Program Files/Docker/Docker/Docker Desktop.exe"
            if [[ -f "$candidate" ]]; then
                printf '%s' "$candidate"
                return 0
            fi
            ;;
    esac
    return 1
}

try_launch_docker_desktop() {
    local exe
    case "$OS_KIND" in
        windows | wsl)
            exe=$(docker_desktop_path) || return 1
            ("$exe" >/dev/null 2>&1 &)
            ;;
        macos)
            [[ -d "/Applications/Docker.app" ]] || return 1
            open -a Docker >/dev/null 2>&1 || return 1
            ;;
        *) return 1 ;;
    esac
}

# Espera a que un comando tenga éxito mostrando un spinner. Uso: wait_until SEGUNDOS MENSAJE comando...
wait_until() {
    local timeout=$1 message=$2
    shift 2
    local start=$SECONDS frame=0
    hide_cursor
    while ! "$@" >/dev/null 2>&1; do
        if ((SECONDS - start >= timeout)); then
            show_cursor
            [[ $IS_TTY -eq 1 ]] && printf '\r\033[K'
            return 1
        fi
        if [[ $IS_TTY -eq 1 ]]; then
            printf '\r\033[K  %s %s %s(%s)%s' "${SPIN_FRAMES[frame % ${#SPIN_FRAMES[@]}]}" "$message" \
                "$C_DIM" "$(fmt_clock $((SECONDS - start)))" "$C_RESET"
            frame=$((frame + 1))
        fi
        sleep 1
    done
    show_cursor
    [[ $IS_TTY -eq 1 ]] && printf '\r\033[K'
    return 0
}

# check_docker [--no-launch]
check_docker() {
    local allow_launch=1
    [[ "${1:-}" == "--no-launch" ]] && allow_launch=0

    if ! command -v docker >/dev/null 2>&1; then
        case "$OS_KIND" in
            linux) die "No encontré Docker." \
                "Instalá Docker Engine y el plugin de Compose: https://docs.docker.com/engine/install/" ;;
            *) die "No encontré Docker." \
                "Instalá Docker Desktop: https://www.docker.com/products/docker-desktop/" \
                "Después abrilo, esperá a que diga que está corriendo y volvé a ejecutar ./start.sh" ;;
        esac
    fi

    if ! docker compose version >/dev/null 2>&1; then
        die "Necesitás Docker Compose v2 (el comando \"docker compose\", con espacio)." \
            "Actualizá Docker Desktop, o en Linux instalá el paquete docker-compose-plugin."
    fi
    local version major
    version=$(docker compose version --short 2>/dev/null | tr -d '\r' | sed 's/^v//')
    major=${version%%.*}
    if [[ "$major" =~ ^[0-9]+$ ]] && ((major < 2)); then
        die "Tu Docker Compose es la versión $version y se necesita la 2 o superior." \
            "Actualizá Docker Desktop o el plugin docker-compose-plugin."
    fi

    local info_err
    if info_err=$(docker info 2>&1 >/dev/null); then
        return 0
    fi

    if [[ "$info_err" == *"permission denied"* ]]; then
        die "Tu usuario no tiene permiso para usar Docker." \
            "Ejecutá: sudo usermod -aG docker \$USER  (y después cerrá sesión y volvé a entrar)"
    fi
    if [[ $allow_launch -eq 0 ]]; then
        return 2
    fi

    warn "Docker no está corriendo."
    if try_launch_docker_desktop; then
        info "Abrí Docker Desktop por vos; esperando a que termine de iniciar (puede tardar 1-2 minutos)…"
        if wait_until 240 "Esperando a Docker Desktop" docker info; then
            ok "Docker está listo"
            return 0
        fi
        die "Docker Desktop no terminó de iniciar." \
            "Abrilo a mano, esperá a que diga \"Engine running\" y volvé a ejecutar ./start.sh"
    fi
    case "$OS_KIND" in
        linux) die "El servicio de Docker no está corriendo." "Inicialo con: sudo systemctl start docker" ;;
        *) die "Abrí Docker Desktop y esperá a que diga \"Engine running\"." "Después volvé a ejecutar ./start.sh" ;;
    esac
}

require_project_files() {
    local dir missing=()
    for dir in ai-service backend frontend; do
        [[ -f "$dir/Dockerfile" ]] || missing+=("$dir/Dockerfile")
    done
    [[ -f docker-compose.yml ]] || missing+=("docker-compose.yml")
    if [[ ${#missing[@]} -gt 0 ]]; then
        die "Faltan archivos del proyecto: ${missing[*]}" "¿Clonaste el repositorio completo?"
    fi
}

# -----------------------------------------------------------------------------
# .env
# -----------------------------------------------------------------------------
# Valor de CLAVE en .env (última definición, sin comillas ni CR). Vacío si no está.
env_get() {
    local key=$1 line value
    [[ -f "$ENV_FILE" ]] || return 0
    line=$(grep -E "^[[:space:]]*(export[[:space:]]+)?${key}[[:space:]]*=" "$ENV_FILE" | tail -n 1 | tr -d '\r' || true)
    [[ -n "$line" ]] || return 0
    value=${line#*=}
    value="${value#"${value%%[![:space:]]*}"}"
    value="${value%"${value##*[![:space:]]}"}"
    if [[ "$value" =~ ^\"(.*)\"$ || "$value" =~ ^\'(.*)\'$ ]]; then
        value=${BASH_REMATCH[1]}
    fi
    printf '%s' "$value"
}

# Valor efectivo como lo ve docker compose: variable de entorno > .env > valor por defecto.
config_value() {
    local key=$1 default=${2:-} value
    value=${!key:-}
    [[ -n "$value" ]] || value=$(env_get "$key")
    [[ -n "$value" ]] || value=$default
    printf '%s' "$value"
}

env_set() {
    local key=$1 value=$2 tmp="$ENV_FILE.tmp.$$"
    awk -v key="$key" -v value="$value" '
        { sub(/\r$/, "") }
        !done && $0 ~ "^[[:space:]]*" key "[[:space:]]*=" { print key "=" value; done = 1; next }
        { print }
        END { if (!done) print key "=" value }
    ' "$ENV_FILE" >"$tmp"
    mv -f "$tmp" "$ENV_FILE"
}

generate_secret() {
    local secret=""
    if command -v openssl >/dev/null 2>&1; then
        secret=$(openssl rand -hex 48 2>/dev/null | tr -d '\r\n' || true)
    fi
    if [[ ${#secret} -lt 64 && -r /dev/urandom ]]; then
        secret=$(head -c 48 /dev/urandom | od -An -tx1 | tr -d ' \r\n' || true)
    fi
    [[ ${#secret} -ge 64 ]] || return 1
    printf '%s' "$secret"
}

# ensure_env [--quiet]
ensure_env() {
    local quiet=0
    [[ "${1:-}" == "--quiet" ]] && quiet=1
    if [[ ! -f "$ENV_FILE" ]]; then
        [[ -f "$ENV_EXAMPLE" ]] || die "No encontré $ENV_EXAMPLE." "¿Clonaste el repositorio completo?"
        tr -d '\r' <"$ENV_EXAMPLE" >"$ENV_FILE"
        [[ $quiet -eq 1 ]] || ok "Creé .env a partir de .env.example"
    fi
    if [[ -z "$(env_get JWT_SECRET)" ]]; then
        local secret
        secret=$(generate_secret) || die "No pude generar un JWT_SECRET aleatorio." \
            "Completá JWT_SECRET en .env con 64 o más caracteres aleatorios."
        env_set JWT_SECRET "$secret"
        [[ $quiet -eq 1 ]] || ok "Generé un JWT_SECRET aleatorio en .env"
    fi
}

# -----------------------------------------------------------------------------
# IPs de la red local (para el certificado HTTPS y las URLs del celular)
# -----------------------------------------------------------------------------
is_lan_ipv4() {
    local ip=$1 octet
    [[ "$ip" =~ ^([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})$ ]] || return 1
    for octet in "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}" "${BASH_REMATCH[3]}" "${BASH_REMATCH[4]}"; do
        ((10#$octet <= 255)) || return 1
    done
    case "$ip" in
        127.* | 169.254.* | 0.*) return 1 ;;
    esac
    return 0
}

# Cada línea: "<prioridad> <ip>". 1 = interfaz con puerta de enlace (la red real), 2 = otras.
candidate_ips_windows() {
    local cmd=ipconfig
    [[ "$OS_KIND" == wsl ]] && cmd=ipconfig.exe
    command -v "$cmd" >/dev/null 2>&1 || return 0
    # La salida de ipconfig depende del idioma de Windows y puede venir con acentos mal codificados:
    # solo se usan "IPv4", las palabras de la puerta de enlace y los números.
    "$cmd" 2>/dev/null | tr -d '\r' | LC_ALL=C awk '
        function first_ipv4(s) {
            if (match(s, /[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+/)) return substr(s, RSTART, RLENGTH)
            return ""
        }
        function flush(   i) {
            if (!virtual) for (i = 1; i <= n; i++) print (gateway ? "1 " : "2 ") ips[i]
            n = 0; gateway = 0; in_gateway = 0
        }
        function is_gateway(s,   ip) { ip = first_ipv4(s); return ip != "" && ip != "0.0.0.0" }
        /^[^ \t]/ {
            flush()
            virtual = ($0 ~ /vEthernet|VirtualBox|VMware|Hyper-V|WSL|Loopback|Docker|Bluetooth|Teredo|isatap|6to4|TAP-|OpenVPN|WireGuard|Wintun|Tailscale|ZeroTier|Hamachi|Radmin|NordLynx|Windscribe|ProtonVPN|Cisco AnyConnect|FortiClient/)
            next
        }
        /IPv4/ { ip = first_ipv4($0); if (ip != "") ips[++n] = ip; in_gateway = 0; next }
        /[Gg]ateway|[Pp]uerta de enlace|[Pp]asserelle/ {
            in_gateway = 1
            if (is_gateway($0)) gateway = 1
            next
        }
        /\. :|: *$/ { in_gateway = 0; next }
        { if (in_gateway && is_gateway($0)) gateway = 1 }
        END { flush() }
    '
}

candidate_ips_linux() {
    if command -v ip >/dev/null 2>&1; then
        ip -4 route get 1.1.1.1 2>/dev/null |
            awk '{ for (i = 1; i < NF; i++) if ($i == "src") { print "1 " $(i + 1); exit } }'
        ip -4 -o addr show up scope global 2>/dev/null | awk '
            $2 !~ /^(docker|br-|veth|virbr|vboxnet|vmnet|cni|flannel|cali|lxc|lxd|podman|kube|tun|tap|wg|tailscale|zt)/ {
                split($4, a, "/"); print "2 " a[1]
            }'
    elif hostname -I >/dev/null 2>&1; then
        local addr
        for addr in $(hostname -I); do
            case "$addr" in 172.17.*) ;; *) echo "2 $addr" ;; esac
        done
    fi
}

candidate_ips_macos() {
    local iface addr
    iface=$(route -n get default 2>/dev/null | awk '/interface:/ { print $2; exit }')
    if [[ -n "$iface" ]]; then
        addr=$(ipconfig getifaddr "$iface" 2>/dev/null || true)
        [[ -z "$addr" ]] || echo "1 $addr"
    fi
    for iface in $(ifconfig -l 2>/dev/null); do
        case "$iface" in
            en* | bridge*)
                addr=$(ipconfig getifaddr "$iface" 2>/dev/null || true)
                [[ -z "$addr" ]] || echo "2 $addr"
                ;;
        esac
    done
}

# Imprime las IPv4 de la LAN separadas por coma (la principal primero).
detect_lan_ips() {
    local raw="" has_primary=0 priority addr result="" seen=","
    case "$OS_KIND" in
        windows) raw=$(candidate_ips_windows || true) ;;
        wsl) raw=$(candidate_ips_windows || true)
            [[ -n "$raw" ]] || raw=$(candidate_ips_linux || true) ;;
        macos) raw=$(candidate_ips_macos || true) ;;
        *) raw=$(candidate_ips_linux || true) ;;
    esac
    [[ "$raw" == *"1 "* ]] && has_primary=1

    while read -r priority addr; do
        [[ -n "${addr:-}" ]] || continue
        is_lan_ipv4 "$addr" || continue
        # En Windows hay muchos adaptadores virtuales/VPN: si hay una red con puerta de enlace se usa
        # solo esa, más el hotspot de Windows (192.168.137.x) para celulares conectados a la PC.
        if [[ "$OS_KIND" == windows || "$OS_KIND" == wsl ]] && [[ $has_primary -eq 1 && "$priority" == 2 ]]; then
            [[ "$addr" == 192.168.137.* ]] || continue
        fi
        [[ "$seen" == *",$addr,"* ]] && continue
        seen="$seen$addr,"
        result="${result:+$result,}$addr"
    done < <(printf '%s\n' "$raw" | sort -s -k1,1)
    printf '%s' "$result"
}

normalize_ip_list() {
    printf '%s' "$1" | tr ' ;\t\r' ',,,,' | tr -s ',' | sed 's/^,//; s/,$//'
}

# Define y exporta LAN_IPS: variable de entorno > .env > detección automática.
resolve_lan_ips() {
    local configured source
    if [[ -n "${LAN_IPS:-}" ]]; then
        source="variable de entorno LAN_IPS"
    else
        configured=$(env_get LAN_IPS)
        if [[ -n "$configured" ]]; then
            LAN_IPS=$configured
            source="LAN_IPS en .env"
        else
            LAN_IPS=$(detect_lan_ips)
            source="detección automática"
        fi
    fi
    LAN_IPS=$(normalize_ip_list "$LAN_IPS")
    export LAN_IPS
    if [[ -n "$LAN_IPS" ]]; then
        ok "IP(s) de esta PC en la red: ${C_BOLD}${LAN_IPS//,/, }${C_RESET} ${C_DIM}($source)${C_RESET}"
    else
        warn "No detecté la IP de esta PC en la red: el celular no va a poder conectarse."
        info "Si la conocés, completá LAN_IPS en .env (ej.: LAN_IPS=192.168.0.10) y ejecutá ./start.sh restart"
    fi
}

# -----------------------------------------------------------------------------
# Estado de los servicios
# -----------------------------------------------------------------------------
# Una línea por contenedor: "<servicio> <estado> <salud> <reinicios>".
service_states() {
    local ids
    ids=$(dc ps -aq 2>/dev/null | tr -d '\r' || true)
    [[ -n "$ids" ]] || return 0
    # shellcheck disable=SC2086
    docker inspect --format \
        '{{index .Config.Labels "com.docker.compose.service"}} {{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} {{.RestartCount}}' \
        $ids 2>/dev/null | tr -d '\r' || true
}

service_label() {
    case "$1/$2" in
        running/healthy | running/none) printf '%slisto%s' "$C_GREEN" "$C_RESET" ;;
        running/starting) printf '%siniciando%s' "$C_YELLOW" "$C_RESET" ;;
        running/unhealthy) printf '%scon problemas%s' "$C_RED" "$C_RESET" ;;
        restarting/*) printf '%sreiniciando%s' "$C_YELLOW" "$C_RESET" ;;
        exited/* | dead/*) printf '%sdetenido%s' "$C_RED" "$C_RESET" ;;
        created/* | missing/*) printf '%sen espera%s' "$C_DIM" "$C_RESET" ;;
        *) printf '%s' "$1" ;;
    esac
}

service_line() {
    local states=$1 svc=$2 line
    line=$(printf '%s\n' "$states" | awk -v s="$svc" '$1 == s { print; exit }')
    [[ -n "$line" ]] || line="$svc missing none 0"
    printf '%s' "$line"
}

port_conflict_hint() {
    local log_file=$1
    if grep -Eqi 'port is already allocated|address already in use|ports are not available|only one usage of each socket|forbidden by its access permissions' "$log_file"; then
        warn "Hay un puerto ocupado por otro programa."
        info "Cambiá HTTP_PORT, HTTPS_PORT o DB_PORT en .env (por ejemplo 8081, 8444, 5434) y volvé a ejecutar ./start.sh"
        case "$OS_KIND" in
            windows | wsl) info "Para ver qué lo usa: netstat -ano | findstr :$(config_value HTTP_PORT 8080)" ;;
            *) info "Para ver qué lo usa: lsof -i :$(config_value HTTP_PORT 8080)" ;;
        esac
    fi
}

show_service_logs() {
    local svc
    for svc in "$@"; do
        printf '\n%s--- Últimas líneas de log de "%s" ---%s\n' "$C_BOLD" "$svc" "$C_RESET"
        dc logs --no-color --tail=60 "$svc" 2>&1 | sed 's/^/  /' || true
    done
}

# Levanta servicios (todos si no se indican) y espera a que estén sanos.
# Uso: up_and_wait TIMEOUT_SEGUNDOS [servicio...]
up_and_wait() {
    local timeout=$1
    shift
    local wait_list
    if [[ $# -gt 0 ]]; then wait_list="$*"; else wait_list="${SERVICES[*]}"; fi

    local up_log up_rc="" start=$SECONDS frame=0 last_plain=""
    up_log=$(make_temp gondolia-up)
    if [[ $# -gt 0 ]]; then
        docker compose -p "$PROJECT" up -d --no-build "$@" >"$up_log" 2>&1 &
    else
        docker compose -p "$PROJECT" up -d --no-build --remove-orphans >"$up_log" 2>&1 &
    fi
    BG_PID=$!
    hide_cursor

    local states svc line status health restarts summary all_ready failed failed_reason tick
    while :; do
        if [[ -z "$up_rc" ]] && ! kill -0 "$BG_PID" 2>/dev/null; then
            if wait "$BG_PID"; then up_rc=0; else up_rc=$?; fi
            BG_PID=""
        fi

        states=$(service_states)
        summary="" all_ready=1 failed="" failed_reason=""
        for svc in $wait_list; do
            line=$(service_line "$states" "$svc")
            read -r _ status health restarts <<<"$line"
            summary="${summary:+$summary  }$svc $(service_label "$status" "$health")"
            case "$status/$health" in
                running/healthy | running/none) ;;
                running/unhealthy)
                    all_ready=0
                    [[ -n "$failed" ]] || { failed=$svc; failed_reason="no pasa el control de salud"; }
                    ;;
                restarting/* | exited/* | dead/*)
                    all_ready=0
                    if [[ "${restarts:-0}" -ge 3 || -n "$up_rc" && "$status" != restarting ]]; then
                        [[ -n "$failed" ]] || { failed=$svc; failed_reason="se detuvo o se reinicia una y otra vez"; }
                    fi
                    ;;
                *) all_ready=0 ;;
            esac
        done

        if [[ $IS_TTY -eq 0 && "$summary" != "$last_plain" ]]; then
            printf '  [%s] %s\n' "$(fmt_clock $((SECONDS - start)))" "$summary"
            last_plain=$summary
        fi

        if [[ $all_ready -eq 1 && "$up_rc" == 0 ]]; then
            show_cursor
            [[ $IS_TTY -eq 0 ]] || printf '\r\033[K'
            ok "Servicios listos: $summary"
            return 0
        fi

        if [[ -n "$failed" || (-n "$up_rc" && "$up_rc" != 0) || $((SECONDS - start)) -ge $timeout ]]; then
            show_cursor
            [[ $IS_TTY -eq 0 ]] || printf '\r\033[K'
            say "  $summary"
            if [[ -n "$failed" ]]; then
                error "El servicio \"$failed\" $failed_reason."
            elif [[ -n "$up_rc" && "$up_rc" != 0 ]]; then
                error "docker compose no pudo levantar los servicios:"
                sed 's/^/  /' "$up_log" >&2
                port_conflict_hint "$up_log"
            else
                error "Se agotó el tiempo de espera ($(fmt_duration "$timeout"))."
            fi
            local to_show=""
            for svc in $wait_list; do
                line=$(service_line "$states" "$svc")
                read -r _ status health restarts <<<"$line"
                case "$status/$health" in
                    running/healthy | running/none | missing/* | created/*) ;;
                    *) to_show="$to_show $svc" ;;
                esac
            done
            # shellcheck disable=SC2086
            [[ -z "$to_show" ]] || show_service_logs $to_show
            printf '\n'
            info "Ver todos los logs: ./start.sh logs    Estado: ./start.sh status"
            info "Si el problema sigue, probá ./start.sh restart o empezá de cero con ./start.sh reset"
            return 1
        fi

        for tick in 1 2 3 4 5; do
            if [[ $IS_TTY -eq 1 ]]; then
                printf '\r\033[K  %s %s%s%s  %s' "${SPIN_FRAMES[frame % ${#SPIN_FRAMES[@]}]}" \
                    "$C_DIM" "$(fmt_clock $((SECONDS - start)))" "$C_RESET" "$summary"
                frame=$((frame + 1))
            fi
            sleep 0.2
        done
    done
}

# -----------------------------------------------------------------------------
# Resumen final
# -----------------------------------------------------------------------------
print_urls() {
    local ips=$1 http_port https_port ip
    http_port=$(config_value HTTP_PORT 8080)
    https_port=$(config_value HTTPS_PORT 8443)

    printf '\n%sEn esta PC%s\n' "$C_BOLD" "$C_RESET"
    info "${C_CYAN}http://localhost:${http_port}${C_RESET}"
    info "${C_CYAN}https://localhost:${https_port}${C_RESET}  ${C_DIM}(certificado local: el navegador puede pedir confirmación)${C_RESET}"

    printf '\n%sDesde el celular u otra PC (misma red WiFi)%s\n' "$C_BOLD" "$C_RESET"
    if [[ -n "$ips" ]]; then
        for ip in ${ips//,/ }; do
            info "${C_CYAN}https://${ip}:${https_port}${C_RESET}"
        done
        info "${C_DIM}La cámara del celular necesita HTTPS: aceptá el aviso de seguridad del navegador o,${C_RESET}"
        info "${C_DIM}para no verlo más, descargá e instalá la CA local (pasos en README.md):${C_RESET}"
        for ip in ${ips//,/ }; do
            info "${C_CYAN}http://${ip}:${http_port}/gondolia-ca.crt${C_RESET}"
        done
    else
        info "${C_YELLOW}No hay IPs de red configuradas: completá LAN_IPS en .env y ejecutá ./start.sh restart${C_RESET}"
    fi
}

account_group() { printf '\n  %s%s%s %s%s%s\n' "$C_BOLD" "$1" "$C_RESET" "$C_DIM" "${2:-}" "$C_RESET"; }
account_row() { printf '    %-30s %-15s %-14s %s\n' "$1" "$2" "$3" "${4:-}"; }

print_accounts() {
    local owner_email owner_password seed fixture
    owner_email=$(config_value APP_BOOTSTRAP_OWNER_EMAIL dueno@gondolia.app)
    owner_password=$(config_value APP_BOOTSTRAP_OWNER_PASSWORD 'Gondolia2026!')
    seed=$(config_value APP_SEED_DEMO true)
    fixture=$(config_value APP_DEV_FIXTURE false)

    printf '\n%sCuentas%s %s(se crean al iniciar con la base vacía)%s\n' "$C_BOLD" "$C_RESET" "$C_DIM" "$C_RESET"
    printf '    %s%-30s %-15s %-14s %s%s\n' "$C_DIM" "Email" "Clave" "Rol" "Sucursales" "$C_RESET"

    account_group "GondolIA (plataforma)" "· no ven datos de los comercios"
    account_row "$owner_email" "$owner_password" "Dueño"
    if [[ "$seed" == true ]]; then
        account_row "socia@gondolia.app" "Gondolia2026!" "Dueña"
        account_row "soporte@gondolia.app" "Gondolia2026!" "Soporte"
        account_row "soporte2@gondolia.app" "Gondolia2026!" "Soporte"

        account_group "Almacén Don Pepe" "· CABA · 1 sucursal · FIFO · tiene el lote del recall"
        account_row "jefe@donpepe.com" "Demo2026!" "Jefe" "todas"
        account_row "admin@donpepe.com" "Demo2026!" "Administrador" "todas"
        account_row "empleado@donpepe.com" "Demo2026!" "Empleado" "Sucursal Principal"

        account_group "Dietética Vida Sana" "· Córdoba · 2 sucursales · FEFO"
        account_row "jefe@vidasana.com" "Demo2026!" "Jefe" "todas"
        account_row "admin@vidasana.com" "Demo2026!" "Administrador" "todas"
        account_row "empleado@vidasana.com" "Demo2026!" "Empleado" "Nueva Córdoba"

        account_group "Minimercado El Sol" "· Rosario · 3 sucursales · FIFO · recall en Fisherton"
        account_row "jefe@elsol.com" "Demo2026!" "Jefe" "todas"
        account_row "admin@elsol.com" "Demo2026!" "Administrador" "todas"
        account_row "empleado@elsol.com" "Demo2026!" "Empleado" "Centro y Fisherton"
        account_row "empleado.echesortu@elsol.com" "Demo2026!" "Empleado" "Echesortu"

        account_group "Kiosco La Esquina" "· deshabilitado (para mostrar el bloqueo)"
        account_row "admin@laesquina.com" "Demo2026!" "Administrador" "todas"
    fi
    if [[ "$fixture" == true ]]; then
        account_group "Fixture de desarrollo" "(APP_DEV_FIXTURE=true)"
        account_row "soporte@gondolia.app" "Demo2026!" "Soporte"
        account_row "jefe@prueba.com" "Demo2026!" "Jefe" "Comercio de Prueba: todas"
        account_row "admin@prueba.com" "Demo2026!" "Administrador" "Comercio de Prueba: todas"
        account_row "empleado@prueba.com" "Demo2026!" "Empleado" "Comercio de Prueba: Sucursal Centro"
        account_row "admin@otro.com" "Demo2026!" "Administrador" "Otro Comercio: todas"
        if [[ "$seed" == true ]]; then
            printf '\n  %s%s Con APP_DEV_FIXTURE=true una base nueva no recibe los datos demo (el fixture crea comercios antes).%s\n' \
                "$C_YELLOW" "$SYM_WARN" "$C_RESET"
        fi
    fi
}

print_network_hints() {
    printf '\n%sSi el celular no abre la página%s\n' "$C_BOLD" "$C_RESET"
    info "$SYM_DOT Tiene que estar en la misma red WiFi que esta PC (las redes públicas o de la facultad"
    info "  suelen aislar dispositivos: en ese caso usá el hotspot del celular)."
    case "$OS_KIND" in
        windows | wsl)
            local http_port https_port
            http_port=$(config_value HTTP_PORT 8080)
            https_port=$(config_value HTTPS_PORT 8443)
            info "$SYM_DOT Windows: marcá la red como \"Privada\" y permití Docker Desktop en el Firewall."
            info "  O abrí los puertos desde una terminal como administrador:"
            info "  ${C_DIM}netsh advfirewall firewall add rule name=\"GondolIA\" dir=in action=allow protocol=TCP localport=${http_port},${https_port} profile=private${C_RESET}"
            ;;
        macos)
            info "$SYM_DOT macOS: si el Firewall está activo, permití conexiones entrantes para Docker."
            ;;
        linux)
            info "$SYM_DOT Linux: si usás ufw, abrí los puertos: sudo ufw allow $(config_value HTTP_PORT 8080),$(config_value HTTPS_PORT 8443)/tcp"
            ;;
    esac
}

# -----------------------------------------------------------------------------
# Comandos
# -----------------------------------------------------------------------------
print_banner() {
    printf '\n%s  GondolIA%s %s· Tu negocio siempre a tiempo%s\n' "$C_GREEN$C_BOLD" "$C_RESET" "$C_DIM" "$C_RESET"
}

confirm() {
    local prompt=$1 answer
    [[ $ASSUME_YES -eq 0 ]] || return 0
    if [[ ! -t 0 ]]; then
        die "Hace falta confirmar esta acción." "Volvé a ejecutar el comando agregando -y para confirmar sin preguntar."
    fi
    printf '%s%s [s/N]%s ' "$C_BOLD" "$prompt" "$C_RESET"
    read -r answer || return 1
    [[ "$answer" =~ ^([sS]|[sS][iI]|[sS]í|[sS]Í|[yY]|[yY][eE][sS])$ ]]
}

cmd_start() {
    local total_start=$SECONDS
    print_banner
    check_docker
    require_project_files
    ensure_env
    mkdir -p "$CERTS_DIR"
    resolve_lan_ips

    local first_run=0 timeout
    docker volume inspect "${PROJECT}_pgdata" >/dev/null 2>&1 || first_run=1
    if [[ -n "${GONDOLIA_WAIT_TIMEOUT:-}" ]]; then
        timeout=$GONDOLIA_WAIT_TIMEOUT
    elif [[ $first_run -eq 1 ]]; then
        timeout=900
    else
        timeout=600
    fi

    step "Construyendo imágenes"
    if [[ $first_run -eq 1 ]]; then
        info "Primer arranque: se descargan dependencias y se cargan los datos demo. Puede tardar 10-15 minutos."
    fi
    if ! dc build; then
        die "Falló la construcción de las imágenes (el error está arriba)." \
            "Si es un problema de red, revisá la conexión a internet y volvé a intentar."
    fi

    if ! docker image inspect "$DB_IMAGE" >/dev/null 2>&1; then
        step "Descargando $DB_IMAGE"
        dc pull db || die "No pude descargar $DB_IMAGE. Revisá la conexión a internet."
    fi

    step "Iniciando servicios"
    up_and_wait "$timeout" || exit 1

    printf '\n%s%s GondolIA está lista%s %s(%s)%s\n' "$C_GREEN$C_BOLD" "$SYM_OK" "$C_RESET" \
        "$C_DIM" "$(fmt_duration $((SECONDS - total_start)))" "$C_RESET"
    print_urls "$LAN_IPS"
    print_accounts
    print_network_hints
    printf '\n%sComandos útiles:%s ./start.sh status · ./start.sh logs [servicio] · ./start.sh stop · ./start.sh help\n\n' \
        "$C_BOLD" "$C_RESET"
}

remove_certs() {
    [[ -e "$CERTS_DIR" ]] || return 0
    rm -rf "$CERTS_DIR" 2>/dev/null || true
    if [[ -e "$CERTS_DIR" ]]; then
        # Linux: archivos creados como root por el contenedor. Se borran desde un contenedor.
        local mount_dir="$ROOT_DIR/$CERTS_DIR"
        [[ "$OS_KIND" != windows ]] || mount_dir=$(cd "$CERTS_DIR" && pwd -W)
        MSYS_NO_PATHCONV=1 docker run --rm -v "$mount_dir:/certs" "$DB_IMAGE" \
            sh -c 'rm -rf /certs/* /certs/.[!.]* 2>/dev/null; true' >/dev/null 2>&1 || true
        rm -rf "$CERTS_DIR" 2>/dev/null || true
    fi
    if [[ -e "$CERTS_DIR" ]]; then
        warn "No pude borrar la carpeta $CERTS_DIR/ (permisos). Borrala a mano."
    else
        ok "Certificados locales borrados"
    fi
}

cmd_stop() {
    local purge=0 arg
    for arg in "$@"; do
        case "$arg" in
            --borrar | --purge | --purgar) purge=1 ;;
            -y | --yes | --si) ASSUME_YES=1 ;;
            *) die "Opción desconocida para stop: $arg" "Uso: ./start.sh stop [--borrar|--purge] [-y]" ;;
        esac
    done

    local docker_status=0
    check_docker --no-launch || docker_status=$?
    if [[ $docker_status -ne 0 ]]; then
        if [[ $purge -eq 0 ]]; then
            ok "Docker no está corriendo: GondolIA ya está detenida."
            return 0
        fi
        die "Docker no está corriendo." "Abrí Docker Desktop para poder borrar contenedores, volúmenes e imágenes."
    fi
    ensure_env --quiet

    if [[ $purge -eq 1 ]]; then
        warn "Vas a borrar TODO lo de GondolIA en Docker: contenedores, base de datos, adjuntos, imágenes y certificados."
        info "Los celulares que tengan instalada la CA local van a tener que instalar la nueva."
        if ! confirm "¿Querés continuar?"; then
            info "Cancelado. No se borró nada."
            return 0
        fi
        step "Borrando contenedores, volúmenes e imágenes"
        dc down -v --rmi local --remove-orphans
        remove_certs
        ok "Listo. Para empezar de cero: ./start.sh"
    else
        step "Deteniendo GondolIA"
        dc down --remove-orphans
        ok "GondolIA detenida. Los datos se conservan; para volver a iniciar: ./start.sh"
    fi
}

cmd_restart() {
    [[ $# -eq 0 ]] || die "restart no acepta opciones." "Uso: ./start.sh restart"
    check_docker
    ensure_env --quiet
    step "Deteniendo GondolIA"
    dc down --remove-orphans
    cmd_start
}

cmd_reset() {
    local arg
    for arg in "$@"; do
        case "$arg" in
            -y | --yes | --si) ASSUME_YES=1 ;;
            *) die "Opción desconocida para reset: $arg" "Uso: ./start.sh reset [-y]" ;;
        esac
    done
    check_docker
    ensure_env --quiet
    warn "Vas a borrar la base de datos y los archivos subidos. Al iniciar se vuelven a cargar los datos demo."
    if ! confirm "¿Querés continuar?"; then
        info "Cancelado. No se borró nada."
        return 0
    fi
    step "Borrando datos"
    dc down -v --remove-orphans
    ok "Datos borrados"
    cmd_start
}

normalize_service() {
    case "$1" in
        db | base | postgres | postgresql | database) echo db ;;
        ai | ia | ai-service) echo ai ;;
        backend | api | spring) echo backend ;;
        web | frontend | nginx) echo web ;;
        *) return 1 ;;
    esac
}

cmd_logs() {
    [[ $# -le 1 ]] || die "Uso: ./start.sh logs [db|ai|backend|web]"
    local svc=""
    if [[ $# -eq 1 ]]; then
        svc=$(normalize_service "$1") || die "Servicio desconocido: \"$1\"." "Opciones: db, ai, backend, web"
    fi
    check_docker --no-launch || die "Docker no está corriendo." "Abrí Docker Desktop y probá de nuevo."
    ensure_env --quiet
    info "${C_DIM}Mostrando logs${svc:+ de $svc} (Ctrl+C para salir)…${C_RESET}"
    trap 'exit 0' INT
    if [[ -n "$svc" ]]; then
        dc logs -f --tail=200 "$svc" || true
    else
        dc logs -f --tail=100 || true
    fi
}

cmd_status() {
    [[ $# -eq 0 ]] || die "status no acepta opciones." "Uso: ./start.sh status"
    print_banner
    if ! check_docker --no-launch; then
        warn "Docker no está corriendo, así que GondolIA está detenida."
        return 0
    fi
    ensure_env --quiet

    local states svc line status health restarts all_ready=1 any=0
    states=$(service_states)
    printf '\n%sServicios%s\n' "$C_BOLD" "$C_RESET"
    for svc in "${SERVICES[@]}"; do
        line=$(service_line "$states" "$svc")
        read -r _ status health restarts <<<"$line"
        [[ "$status" == missing ]] || any=1
        case "$status/$health" in
            running/healthy | running/none) ;;
            *) all_ready=0 ;;
        esac
        if [[ "$status" == missing ]]; then
            printf '  %-8s %sno creado%s\n' "$svc" "$C_DIM" "$C_RESET"
        else
            printf '  %-8s %s %s(%s, reinicios: %s)%s\n' "$svc" "$(service_label "$status" "$health")" \
                "$C_DIM" "$status${health:+/$health}" "${restarts:-0}" "$C_RESET"
        fi
    done

    if [[ $any -eq 0 ]]; then
        printf '\n'
        info "GondolIA no está iniciada. Iniciala con: ./start.sh"
        return 0
    fi

    if [[ $all_ready -eq 1 ]]; then
        local ips
        ips=$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$(dc ps -q web | head -n 1)" 2>/dev/null |
            tr -d '\r' | sed -n 's/^LAN_IPS=//p' | head -n 1 || true)
        print_urls "$ips"
    else
        printf '\n'
        info "Algunos servicios no están listos. Ver logs: ./start.sh logs [servicio]"
    fi
    printf '\n'
}

cmd_db() {
    [[ $# -eq 0 ]] || die "db no acepta opciones." "Uso: ./start.sh db"
    print_banner
    check_docker
    ensure_env
    step "Iniciando solo la base de datos"
    up_and_wait 180 db || exit 1
    local port name user
    port=$(config_value DB_PORT 5433)
    name=$(config_value POSTGRES_DB gondolia)
    user=$(config_value POSTGRES_USER gondolia)
    printf '\n'
    info "JDBC:     ${C_CYAN}jdbc:postgresql://localhost:${port}/${name}${C_RESET}  (usuario ${user})"
    info "Backend:  DB_PORT_INTERNAL=${port} mvn spring-boot:run   (desde backend/)"
    info "Detener:  ./start.sh stop"
    printf '\n'
}

cmd_ips() {
    say "Sistema detectado: $OS_KIND"
    say "IPs detectadas: $(detect_lan_ips)"
}

cmd_help() {
    cat <<EOF

${C_GREEN}${C_BOLD}GondolIA${C_RESET} - entorno completo con Docker

${C_BOLD}Uso:${C_RESET} ./start.sh [comando] [opciones]

${C_BOLD}Comandos:${C_RESET}
  ${C_CYAN}start${C_RESET}                     Construye e inicia todo, espera a que esté sano y muestra
                            URLs y cuentas demo. Es el comando por defecto.
  ${C_CYAN}stop${C_RESET}                      Detiene GondolIA. Conserva la base de datos y los adjuntos.
  ${C_CYAN}stop --borrar${C_RESET} [-y]        Detiene y borra contenedores, volúmenes (datos), imágenes del
                            proyecto y certificados. Pide confirmación salvo con -y.
                            (--purge es sinónimo de --borrar)
  ${C_CYAN}restart${C_RESET}                   Detiene y vuelve a iniciar (reconstruye si hubo cambios de código
                            y vuelve a detectar las IPs de la red).
  ${C_CYAN}logs${C_RESET} [servicio]           Muestra los logs en vivo. Servicios: db, ai, backend, web.
  ${C_CYAN}status${C_RESET}                    Estado de cada servicio y URLs de acceso.
  ${C_CYAN}reset${C_RESET} [-y]                Borra la base de datos y los adjuntos, e inicia de nuevo
                            (se vuelven a cargar los datos demo).
  ${C_CYAN}db${C_RESET}                        Inicia solo PostgreSQL (para desarrollo local sin Docker).
  ${C_CYAN}help${C_RESET}                      Muestra esta ayuda.

${C_BOLD}Variables útiles:${C_RESET}
  LAN_IPS=192.168.0.10 ./start.sh        Fuerza las IPs del certificado y de las URLs del celular.
  GONDOLIA_WAIT_TIMEOUT=1200 ./start.sh  Segundos máximos de espera (por defecto 900 el primer
                                         arranque y 600 los siguientes).
  NO_COLOR=1 ./start.sh                  Salida sin colores.

La configuración está en .env (se crea desde .env.example). Más info en README.md.

EOF
}

main() {
    local command=${1:-start}
    [[ $# -eq 0 ]] || shift
    case "$command" in
        start | up | iniciar) cmd_start "$@" ;;
        stop | down | detener) cmd_stop "$@" ;;
        restart | reiniciar) cmd_restart "$@" ;;
        logs | log) cmd_logs "$@" ;;
        status | ps | estado) cmd_status "$@" ;;
        reset) cmd_reset "$@" ;;
        db) cmd_db "$@" ;;
        ips) cmd_ips ;;
        help | -h | --help | ayuda) cmd_help ;;
        *)
            error "Comando desconocido: $command"
            cmd_help
            exit 1
            ;;
    esac
}

main "$@"
