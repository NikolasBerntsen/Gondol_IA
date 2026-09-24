# 🚀 Despliegue de GondolIA en Oracle Cloud

GondolIA vive en la **misma VM de Oracle** que los otros dos proyectos (BioTrust Asistencias y el
Comando Central de la tesis) y se publica en:

**https://gondolia.144-22-138-149.sslip.io**

| Proyecto | URL |
|---|---|
| BioTrust Asistencias | https://panel.144-22-138-149.sslip.io |
| Comando Central (tesis) | https://tesis.144-22-138-149.sslip.io |
| **GondolIA** | **https://gondolia.144-22-138-149.sslip.io** |

## Cómo está armado

```
Internet ──HTTPS──> Caddy (del proyecto BioTrust: es el único dueño de los puertos 80 y 443)
                      ├── panel.…      → BioTrust
                      ├── tesis.…      → Comando Central
                      └── gondolia.…   → gondolia-web (nginx) ─┬─> backend (Spring) ─┬─> db (PostgreSQL)
                                                                │                     └─> ai (FastAPI)
                                                                └─ /ws (WebSocket)
```

- **GondolIA no publica ningún puerto.** Se llega solo a través de ese Caddy, que lo alcanza por la
  red Docker compartida `proxy` y resuelve el nombre `gondolia-web` por DNS interno.
- **El certificado HTTPS es real** (Let's Encrypt, lo saca Caddy solo). Por eso la cámara del celular
  funciona sin avisos de seguridad, a diferencia del certificado local que usa `./start.sh`.
- La VM es **ARM (Ampere)**: las imágenes se construyen ahí mismo, igual que en los otros proyectos.

## Despliegue automático (GitHub Actions)

Cada **push a `main`** dispara `.github/workflows/deploy.yml`, que primero corre las pruebas de los
tres servicios y de la configuración de nginx (`.github/workflows/ci.yml`) y, solo si pasan, copia el
código por rsync a `/home/ubuntu/Gondol_IA` y ejecuta `deploy/deploy.sh` en la VM. Al final comprueba
que el sitio responda y que el login acepte el `Origin` del dominio público.

Para volver a desplegar sin cambiar código (por ejemplo, para borrar la base con `DEV_WIPE_DB=true`)
no hace falta un commit: *Actions → Deploy a Oracle Cloud → Run workflow* sobre `main`.

El inventario completo de secrets, variables y pisos de cobertura está en
[`docs/ci-y-variables.md`](../docs/ci-y-variables.md).

### Lo que hay que configurar una sola vez en GitHub

**Secreto** (repo → Settings → Secrets and variables → Actions → *New repository secret*):

| Nombre | Contenido |
|---|---|
| `VM_SSH_KEY` | La clave **privada** SSH de la VM (el archivo `.key` completo, incluidas las líneas `BEGIN`/`END`) |

Desde tu PC, sin que la clave pase por ningún lado:

```bash
gh secret set VM_SSH_KEY --repo NikolasBerntsen/Gondol_IA < /ruta/a/tu-clave.key
```

**Variables** (misma pantalla, pestaña *Variables*):

| Variable | Efecto |
|---|---|
| `DEV_WIPE_DB` | `true` → **cada despliegue borra la base de datos** y vuelve a sembrar (desarrollo). `false` o sin definir → conserva los datos. |
| `SEED_DEMO_DATA` | `true` → siembra el mundo demo (comercios, 180 días de historia, cuentas de prueba). `false` → base vacía, solo el usuario dueño. |

```bash
gh variable set DEV_WIPE_DB   --body true --repo NikolasBerntsen/Gondol_IA
gh variable set SEED_DEMO_DATA --body true --repo NikolasBerntsen/Gondol_IA
```

> ⚠ **Antes de usarlo con datos reales de un cliente**: poné `DEV_WIPE_DB=false` y `SEED_DEMO_DATA=false`,
> y cambiá `APP_BOOTSTRAP_OWNER_PASSWORD` en el `.env` de la VM. Mientras `SEED_DEMO_DATA=true`, las
> cuentas de demostración (con contraseñas públicas en `docs/datos-demo.md`) existen en una URL pública.

## Despliegue a mano (dentro de la VM)

```bash
ssh -i tu-clave.key ubuntu@144.22.138.149
cd ~/Gondol_IA
bash deploy/deploy.sh update    # build + up conservando la base
bash deploy/deploy.sh reset     # ⚠ borra la base y vuelve a sembrar la demo
bash deploy/deploy.sh status    # estado y salud de los 4 servicios
bash deploy/deploy.sh logs backend
```

## El `.env` de la VM

Lo crea `deploy.sh` en el primer despliegue, vive **solo en la VM** (`/home/ubuntu/Gondol_IA/.env`),
nunca se sube al repositorio y **rsync no lo pisa**. Contiene la contraseña de la base, el secreto
JWT (ambos aleatorios), la zona horaria, `APP_CORS_ALLOWED_ORIGINS` (el origen público del sitio) y
`APP_SEED_DEMO`. En cada despliegue `deploy.sh` agrega las claves nuevas que falten sin tocar las que
ya están; el detalle de cada una está en [`docs/ci-y-variables.md`](../docs/ci-y-variables.md) §2.

## Entrada por Caddy

El bloque que publica GondolIA vive en el Caddyfile del proyecto BioTrust
(`~/Biotrust-sistema-de-asistencas/deploy/Caddyfile`), igual que el de la tesis:

```caddy
gondolia.144-22-138-149.sslip.io {
	encode gzip
	reverse_proxy gondolia-web:80
}
```

`deploy/deploy.sh` lo agrega y recarga Caddy si falta, así que un despliegue de GondolIA siempre deja
la URL funcionando. **Conviene además tener ese bloque commiteado en el repositorio de BioTrust**: si
no, el próximo despliegue de BioTrust sobrescribe el Caddyfile y GondolIA queda sin salida hasta el
siguiente despliegue propio.

## Si algo falla

| Síntoma | Qué mirar |
|---|---|
| 502 en la URL | `bash deploy/deploy.sh status` y `logs`: el stack está caído o todavía arrancando (la primera vez tarda: compila Java y el frontend en ARM). |
| El sitio no carga y los otros tampoco | El Caddy de BioTrust está caído: `docker logs biotrust-caddy`. |
| El deploy falla en GitHub | Faltó el secreto `VM_SSH_KEY`, cambió la IP de la VM (`VM_HOST` en el workflow) o las pruebas quedaron en rojo (mirá el job que falló). |
| La pantalla de login se ve pero "Ingresar" dice "No tenés permisos" | El `POST /api/auth/login` volvió 403 por CORS. Revisá `APP_CORS_ALLOWED_ORIGINS` en el `.env` de la VM y corré `bash frontend/nginx/test-config.sh` (docs/ci-y-variables.md §2.1). |
| Login demo no anda | Revisá `SEED_DEMO_DATA`; con `false` solo existe `dueno@gondolia.app`. |
| Se llenó el disco | `docker system prune -af` en la VM (ojo: borra imágenes de los tres proyectos). |
