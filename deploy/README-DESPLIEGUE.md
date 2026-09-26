# 🚀 Despliegue de GondolIA en Oracle Cloud

GondolIA vive en la **misma VM de Oracle** que otros proyectos (BioTrust Asistencias y el Comando
Central de la tesis) y se publica en:

**https://gondolia.144-22-138-149.sslip.io**

| Proyecto | URL |
|---|---|
| BioTrust Asistencias | https://panel.144-22-138-149.sslip.io |
| Comando Central (tesis) | https://tesis.144-22-138-149.sslip.io |
| **GondolIA** | **https://gondolia.144-22-138-149.sslip.io** |

## Cómo está armado

```
push a main ──> GitHub Actions: pruebas (ci.yml) ──> rsync + ssh ──> VM (/home/ubuntu/Gondol_IA)
                                                                      └─ bash deploy/deploy.sh update

Internet ──HTTPS──> Caddy (del proyecto BioTrust: el único dueño de los puertos 80 y 443)
                      ├── panel.…      → BioTrust
                      ├── tesis.…      → Comando Central
                      └── gondolia.…   → gondolia-web (nginx) ─┬─> backend (Spring) ─┬─> db (PostgreSQL)
                                                                │                     └─> ai (FastAPI)
                                                                └─ /ws (WebSocket)
```

- **GondolIA no publica ningún puerto.** Se llega solo a través de ese Caddy, que la alcanza por la
  red Docker compartida `proxy` y resuelve `gondolia-web` por DNS interno.
- **La ruta en Caddy es un archivo propio.** `deploy/deploy.sh` escribe `gondolia.caddy` en la
  carpeta de sitios de la VM (`/home/ubuntu/caddy/conf/sites`), comprueba que Caddy la lee, la valida
  y recarga Caddy. El Caddyfile de BioTrust importa esa carpeta, así que un deploy de BioTrust ya no
  puede dejar a GondolIA sin URL.
- **El certificado HTTPS es real** (Let's Encrypt, con ZeroSSL de respaldo) y lo renueva Caddy solo.
  Por eso la cámara del celular funciona sin avisos, a diferencia del certificado local de `./start.sh`.
- La VM es **ARM (Ampere)**: las imágenes se construyen ahí mismo.

## Despliegue automático (GitHub Actions)

Cada **push a `main`** dispara `.github/workflows/deploy.yml`:

1. Corre las pruebas de los tres servicios y de nginx (`.github/workflows/ci.yml`). Si fallan, no se
   despliega.
2. Copia el código por rsync a la VM y ejecuta `deploy/deploy.sh`.
3. Comprueba que el sitio responda, que `/api/auth/me` sin token dé 401 y que el login acepte el
   `Origin` del dominio público.

**A mano:** *Actions → Deploy a Oracle Cloud → Run workflow* sobre `main`. Con el campo `ref` (un
commit, tag o rama) despliega una versión anterior: es el **rollback**.

- En un rollback no se corren las pruebas, porque probarían `main` y no esa versión.
- El próximo push vuelve a desplegar lo último.
- Vuelve el código, no la base: las migraciones de Flyway que ya se aplicaron quedan.

## Configuración en GitHub

Repo → **Settings → Secrets and variables → Actions**. El detalle está en
[`docs/ci-y-variables.md`](../docs/ci-y-variables.md) §1.

| Tipo | Nombre | Para qué |
|---|---|---|
| Secret | `VM_SSH_KEY` | Clave privada SSH con la que se entra a la VM |
| Variable | `VM_HOST` | IP pública de la VM |
| Variable | `VM_USER` | Usuario SSH (`ubuntu`) |
| Variable | `DEPLOY_PATH` | Carpeta del proyecto en la VM (`/home/ubuntu/Gondol_IA`) |
| Variable | `APP_DOMAIN` | Dominio público (`gondolia.144-22-138-149.sslip.io`) |
| Variable | `CADDY_CONTAINER` | Contenedor del Caddy compartido (`biotrust-caddy`) |
| Variable | `CADDY_SITES_DIR` | Carpeta de sitios de Caddy (`/home/ubuntu/caddy/conf/sites`) |
| Variable | `DEV_WIPE_DB` | ⚠ `true` = **cada deploy borra la base** y vuelve a sembrar (desarrollo). `false` = la conserva |
| Variable | `SEED_DEMO_DATA` | `true` = siembra el mundo demo en una base vacía · `false` = base vacía, solo el dueño |

Por consola (la clave se lee del archivo y no pasa por ningún otro lado):

```bash
tr -d '\r' < /ruta/a/tu-clave.key | gh secret set VM_SSH_KEY --repo NikolasBerntsen/Gondol_IA
gh variable set DEV_WIPE_DB --body false --repo NikolasBerntsen/Gondol_IA
gh variable list --repo NikolasBerntsen/Gondol_IA
```

> ⚠ **Antes de usarlo con datos reales de un cliente:**
> - `DEV_WIPE_DB=false` y `SEED_DEMO_DATA=false`.
> - Cambiá la contraseña del dueño **desde la app**: `APP_BOOTSTRAP_OWNER_PASSWORD` se usa solo al
>   crear el dueño la primera vez.
> - Mientras `SEED_DEMO_DATA=true`, las cuentas de demostración (con contraseñas públicas en
>   `docs/datos-demo.md`) existen en una URL pública.

## Despliegue a mano (dentro de la VM)

```bash
ssh -i tu-clave.key ubuntu@<IP de la VM>     # la IP está en la variable VM_HOST del repo
cd ~/Gondol_IA
bash deploy/deploy.sh update            # build + up conservando la base
bash deploy/deploy.sh status            # salud de los servicios, último deploy y URL
bash deploy/deploy.sh logs backend      # logs en vivo
bash deploy/deploy.sh reset             # ⚠ borra la base y vuelve a sembrar la demo
bash deploy/deploy.sh set-env NOMBRE    # guarda un valor en el .env sin mostrarlo
```

## El `.env` de la VM

Vive **solo en la VM** (`/home/ubuntu/Gondol_IA/.env`), nunca se sube al repositorio y el rsync no lo
pisa. `deploy.sh` lo crea en el primer despliegue:

- contraseña de la base y secreto JWT, aleatorios;
- zona horaria, dueño inicial, Open Food Facts.

En los siguientes despliegues agrega las claves nuevas que falten sin tocar las existentes, con dos
excepciones que se reescriben en cada deploy:

- `APP_CORS_ALLOWED_ORIGINS`, que sale de `APP_DOMAIN` (así un cambio de dominio no deja el login en
  403);
- `APP_SEED_DEMO`, que sale de `SEED_DEMO_DATA`.

El detalle de cada clave está en [`docs/ci-y-variables.md`](../docs/ci-y-variables.md) §2.

## Si algo falla

| Síntoma | Qué mirar |
|---|---|
| El workflow falla en "Verificar la configuración" | Falta el secret o alguna variable (el error dice cuál) |
| Falla el job `pruebas` | Las pruebas quedaron en rojo: mirá el job que falló. No se despliega nada |
| `Permission denied (publickey)` o timeout al conectar | `VM_SSH_KEY` incompleta, cambió la IP (`VM_HOST`) o el puerto 22 está cerrado en la Security List |
| "Caddy no la publica" / "no lee …/gondolia.caddy" | El Caddy de BioTrust no importa la carpeta de sitios o no la monta: `docker exec biotrust-caddy ls /etc/caddy/sites` |
| 502 en la URL | `bash deploy/deploy.sh status` y `logs`: el stack está caído o todavía arrancando (la primera vez tarda: compila Java y el frontend en ARM) |
| El sitio no carga y los otros tampoco | El Caddy de BioTrust está caído: `docker logs biotrust-caddy` |
| La pantalla de login se ve pero "Ingresar" dice "No tenés permisos" | El `POST /api/auth/login` volvió 403 por CORS. Revisá `APP_CORS_ALLOWED_ORIGINS` en el `.env` de la VM y corré `bash frontend/nginx/test-config.sh` (docs/ci-y-variables.md §2.1) |
| Login demo no anda | Revisá `SEED_DEMO_DATA`; con `false` solo existe `dueno@gondolia.app` |
| Se llenó el disco | `docker system df`; `docker builder prune -f`; `docker image prune -f`. Nunca `--volumes` ni `docker system prune -a`: la VM es compartida |
