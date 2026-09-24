# Variables, secrets y pruebas automáticas

Todo lo que hay que tener cargado para que GondolIA se despliegue solo en la VM de Oracle, y cómo
corren las pruebas que lo habilitan.

Índice:

1. [GitHub: lo único que hay que cargar en el repositorio](#1-github-lo-único-que-hay-que-cargar-en-el-repositorio)
2. [La VM de Oracle: el archivo `.env`](#2-la-vm-de-oracle-el-archivo-env)
3. [Pruebas automáticas y cómo bloquear un merge](#3-pruebas-automáticas-y-cómo-bloquear-un-merge)
4. [Variables de las imágenes (no hace falta tocarlas)](#4-variables-de-las-imágenes-no-hace-falta-tocarlas)
5. [Desarrollo en tu PC](#5-desarrollo-en-tu-pc)

---

## 1. GitHub: lo único que hay que cargar en el repositorio

### 1.1 Secrets (Settings → Secrets and variables → Actions → *Secrets*)

| Secret | ¿Obligatorio? | Qué es | Si falta |
| --- | --- | --- | --- |
| `VM_SSH_KEY` | **Sí** | Clave privada SSH (contenido completo del archivo, incluidas las líneas `-----BEGIN…` y `-----END…`) del usuario `ubuntu` de la VM `144.22.138.149`. | El workflow *Deploy a Oracle Cloud* falla en el primer paso con un mensaje explícito y no se despliega nada. |

No hay ningún otro secret. **Las contraseñas de la base, el `JWT_SECRET` y la contraseña del dueño
NO van en GitHub**: viven solo en el `.env` de la VM, que `deploy/deploy.sh` genera la primera vez
(§2) y que `rsync` nunca pisa ni transfiere.

### 1.2 Variables (Settings → Secrets and variables → Actions → *Variables*)

Las dos son opcionales; sin ellas el despliegue funciona con los valores por defecto.

| Variable | Valor por defecto | Para qué sirve |
| --- | --- | --- |
| `DEV_WIPE_DB` | *(sin definir)* = `false` | `true` hace que **cada** despliegue **borre la base de datos** y vuelva a sembrar (`deploy.sh reset`). Sirve mientras el modelo de datos cambia todos los días. ⚠ Antes de tener datos reales hay que ponerla en `false` o borrarla. |
| `SEED_DEMO_DATA` | *(sin definir)* = deja el `.env` como está | `true` siembra el mundo demo (comercios, 180 días de ventas, cuentas de prueba) en una base vacía; `false` no siembra nada, que es lo que correspondería en una instalación real. Se escribe en `APP_SEED_DEMO` del `.env` de la VM. |

### 1.3 Permisos del repositorio

El despliegue no publica paquetes ni escribe en el repositorio: los dos workflows corren con
`permissions: contents: read`. No hace falta habilitar nada más en *Settings → Actions*.

---

## 2. La VM de Oracle: el archivo `.env`

Vive en `/home/ubuntu/Gondol_IA/.env`, con permisos `600`, y **solo en la VM**: está excluido del
`rsync` del despliegue y del repositorio. Lo crea `deploy/deploy.sh` en el primer despliegue con
contraseñas aleatorias; en los despliegues siguientes lo respeta y solo agrega las claves nuevas que
falten.

| Variable | La genera / valor | Qué pasa si falta |
| --- | --- | --- |
| `POSTGRES_DB` | `gondolia` | Se usa el valor por defecto. |
| `POSTGRES_USER` | `gondolia` | Se usa el valor por defecto. |
| `POSTGRES_PASSWORD` | 40 caracteres al azar (`deploy.sh`) | **El stack no arranca**: `docker compose` corta con “Falta POSTGRES_PASSWORD en el .env de la VM”. |
| `JWT_SECRET` | 72 caracteres al azar (`deploy.sh`) | **El backend no arranca.** Si se cambia, se cierran todas las sesiones abiertas. |
| `JWT_EXPIRATION_HOURS` | `12` | Se usa `12`. |
| `APP_TIMEZONE` | `America/Argentina/Buenos_Aires` | Se usa esa zona. Define qué es “hoy” para vencimientos y ventas. |
| `APP_BOOTSTRAP_OWNER_EMAIL` | `dueno@gondolia.app` | Se usa ese email. Es el dueño de GondolIA que se crea si todavía no hay ninguno. |
| `APP_BOOTSTRAP_OWNER_PASSWORD` | `Gondolia2026!` | **El backend no arranca.** ⚠ Cambiala apenas el sitio deje de ser una demo. |
| `APP_OPENFOODFACTS_ENABLED` | `true` | Se usa `true` (autocompletar por código de barras los productos que no están en el catálogo de referencia incluido; necesita internet). |
| `APP_CORS_ALLOWED_ORIGINS` | `https://gondolia.144-22-138-149.sslip.io` | Se usa la URL pública. Es la lista de orígenes que el backend acepta; si no incluye el dominio del sitio y el header `Origin` llega al backend, **el login responde 403 y no se puede entrar** (ver §2.1). |
| `APP_SEED_DEMO` | `true` | Se usa `true`. La variable `SEED_DEMO_DATA` del repositorio la pisa en cada despliegue. |
| `JAVA_OPTS` | `-XX:MaxRAMPercentage=40.0 -XX:+ExitOnOutOfMemoryError` | Se usa ese valor. |

Para ver o editar el archivo:

```bash
ssh ubuntu@144.22.138.149
cd ~/Gondol_IA
sudo cat .env                       # ver
nano .env                           # editar
bash deploy/deploy.sh update        # aplicar los cambios
```

### 2.1 Por qué existe `APP_CORS_ALLOWED_ORIGINS`

El navegador manda `Origin: https://gondolia.144-22-138-149.sslip.io` en cada `POST`. Como el
frontend y la API se sirven del mismo origen, esas requests **no son CORS** y nginx les borra el
header antes de pasárselas al backend. Si por algún cambio en el proxy el header llegara igual, el
backend tiene que reconocer ese origen como propio; si no, Spring Security corta la request con
`403 Invalid CORS request` antes de llegar al controlador: la pantalla de login se ve, pero
“Ingresar” siempre falla con “No tenés permisos para realizar esta acción”.

Hay tres cosas que cuidan ese circuito:

- `frontend/nginx/templates/gondolia.conf.template` borra el `Origin` del mismo origen comparándolo
  con el esquema que ve el navegador (`X-Forwarded-Proto` detrás de Caddy), no con el del salto
  interno por HTTP;
- `APP_CORS_ALLOWED_ORIGINS` deja el origen público en la lista del backend, por las dudas;
- el despliegue termina comprobando que `POST /api/auth/login` **con** `Origin` responda 401 (y no
  403), y `frontend/nginx/test-config.sh` lo verifica en cada pull request.

---

## 3. Pruebas automáticas y cómo bloquear un merge

`.github/workflows/ci.yml` corre cuatro jobs en paralelo:

| Job | Qué hace | Piso de cobertura |
| --- | --- | --- |
| `backend` | `mvn verify -Dgondolia.it=true` con un PostgreSQL 16 de verdad como servicio: unitarias + integración (Flyway, consultas nativas, seguridad) e informe JaCoCo. | 78 % de líneas (`jacoco.line.coverage`, perfil `cobertura-completa` de `backend/pom.xml`). |
| `frontend` | `npm run typecheck`, `npm run test:coverage` (Vitest + jsdom) y `npm run build`. | Los valores de `thresholds` en `frontend/vitest.config.ts`. |
| `ai-service` | `pytest` con Tesseract (`spa`+`eng`) instalado, igual que la imagen. | 90 % (`--cov-fail-under` en `ai-service/pytest.ini`). |
| `nginx` | `frontend/nginx/test-config.sh`: la configuración compila y, detrás de un proxy TLS, borra el `Origin` del mismo origen (el error que dejó el login sin poder entrar). | — |

Cuándo corre:

- **en cada pull request** contra `main` (y en cada push a una rama que ya tiene uno abierto);
- **antes de cada despliegue**: `deploy.yml` lo llama con `uses:` y el job `deploy` depende de él,
  así que un merge a `main` con las pruebas en rojo no llega a la VM;
- **a mano** sobre cualquier rama, sin abrir un pull request: *Actions → Pruebas → Run workflow*.

No corre en cada push a una rama: con el pull request abierto, eso disparaba dos corridas completas
del mismo commit (una por el push y otra por el pull request).

### 3.1 Exigir las pruebas para poder mergear (una sola vez)

GitHub no bloquea un merge por sí solo: hay que marcar los checks como obligatorios.

1. Repo → **Settings** → **Branches** → **Add branch protection rule** (o *Add ruleset*).
2. Branch name pattern: `main`.
3. Tildar **Require a pull request before merging**.
4. Tildar **Require status checks to pass before merging** y, en el buscador, elegir los cuatro:
   `Backend (Java 21)`, `Frontend (Node 22)`, `Servicio de IA (Python 3.12)` y
   `nginx (configuración y CORS)`.
   *(Los nombres aparecen recién después de que el workflow corrió al menos una vez.)*
5. Recomendado: **Require branches to be up to date before merging**, para que las pruebas se corran
   contra el `main` actual.

### 3.2 Correr lo mismo en tu PC

```bash
# Backend (unitarias)
cd backend && mvn verify

# Backend (unitarias + integración): necesita un PostgreSQL en localhost:55432
docker run -d --name gondolia-it -p 55432:5432 \
  -e POSTGRES_PASSWORD=dev -e POSTGRES_DB=gondolia_it postgres:16-alpine
cd backend && mvn verify -Dgondolia.it=true
# El informe navegable queda en backend/target/site/jacoco/index.html

# Frontend
cd frontend && npm ci && npm run test:coverage      # informe en frontend/coverage/index.html

# Servicio de IA
cd ai-service && pip install -r requirements.txt && pytest

# nginx (necesita: sudo apt-get install -y nginx-light libnginx-mod-http-js)
bash frontend/nginx/test-config.sh
```

---

## 4. Variables de las imágenes (no hace falta tocarlas)

Las fijan `docker-compose.yml` / `docker-compose.prod.yml` y los `Dockerfile`. Están acá para que se
entienda de dónde sale cada valor.

**Backend** (`backend/Dockerfile`, `application.yml`)

| Variable | Valor en producción | Para qué |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | Puerto interno; no se publica. |
| `SERVER_FORWARD_HEADERS_STRATEGY` | `native` | Confía en `X-Forwarded-*` para armar las URLs públicas (Caddy → nginx → backend). |
| `DB_HOST` / `DB_PORT_INTERNAL` | `db` / `5432` | Nombre del contenedor de la base en la red interna. |
| `DB_POOL_SIZE` | `20` | Conexiones máximas del pool. |
| `AI_SERVICE_URL` | `http://ai:8000` | Servicio de IA en la red interna. |
| `APP_STORAGE_DIR` | `/data/uploads` | Volumen de los adjuntos. |
| `APP_DEV_FIXTURE` | `false` | Comercio mínimo de prueba: solo para desarrollo. |

**Frontend / nginx** (`frontend/Dockerfile`)

| Variable | Valor en producción | Para qué |
| --- | --- | --- |
| `BEHIND_PROXY` | `1` | Caddy termina el TLS: nginx sirve HTTP y no redirige (si no, haría un bucle) y toma el esquema del navegador de `X-Forwarded-Proto`. En local vale `0`. |
| `HTTPS_PORT` | `8443` (solo local) | Puerto HTTPS publicado; se usa para las redirecciones. |
| `LAN_IPS`, `CERT_HOSTNAMES` | vacías (solo local) | Nombres extra del certificado de la CA local. |

**Servicio de IA** (`ai-service/Dockerfile`)

| Variable | Valor | Para qué |
| --- | --- | --- |
| `LOG_LEVEL` | `INFO` | Nivel de log. |
| `OMP_NUM_THREADS`, `OPENBLAS_NUM_THREADS`, `MKL_NUM_THREADS`, `OMP_THREAD_LIMIT` | `1` | Un hilo por biblioteca numérica: la VM tiene poca CPU. |
| `TZ` | `America/Argentina/Buenos_Aires` | Zona horaria del contenedor. |

---

## 5. Desarrollo en tu PC

`./start.sh` copia `.env.example` a `.env` la primera vez y genera un `JWT_SECRET` aleatorio; el
archivo tiene comentado qué hace cada variable. Nada de eso se sube al repositorio.

```bash
./start.sh            # levanta todo en http://localhost:8080
./start.sh restart    # aplicar cambios del .env
./start.sh reset      # ⚠ borra la base y vuelve a sembrar
```
