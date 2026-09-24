# GondolIA

**Tu negocio siempre a tiempo.** Gestión inteligente de inventario para comercios pequeños: kioscos, almacenes,
dietéticas, minimercados y farmacias.

Prototipo académico (UADE, Argentina) de un SaaS multi-tenant: cada comercio (tenant) controla productos, lotes,
vencimientos, stock mínimo y ventas en una o varias sucursales, recibe alertas y **recomendaciones con IA**, y carga
mercadería **con la cámara del celular** (código de barras y lectura de etiquetas). Los dueños de GondolIA administran
los comercios sin ver sus datos, publican avisos y **alertas de recall**, y un equipo de soporte atiende tickets y chat en vivo.

> El contrato técnico completo (roles, API, modelo de datos, reglas de stock) está en [`SPEC.md`](SPEC.md).
> Esta guía explica cómo levantarlo, usarlo y demostrarlo.

## Índice

1. [Qué incluye](#qué-incluye)
2. [Requisitos](#requisitos)
3. [Inicio rápido](#inicio-rápido)
4. [Comandos de start.sh](#comandos-de-startsh)
5. [URLs y puertos](#urls-y-puertos)
6. [Cuentas demo](#cuentas-demo)
7. [Roles y permisos](#roles-y-permisos)
8. [Multi-sucursal](#multi-sucursal)
9. [Lotes, vencimientos y rotación FIFO/FEFO](#lotes-vencimientos-y-rotación-fifofefo)
10. [Usar la cámara desde el celular](#usar-la-cámara-desde-el-celular)
11. [Arquitectura](#arquitectura)
12. [Guion de demo](#guion-de-demo)
13. [Desarrollo local sin Docker](#desarrollo-local-sin-docker)
14. [Configuración (.env)](#configuración-env)
15. [Pruebas y despliegue](#pruebas-y-despliegue)
16. [Solución de problemas](#solución-de-problemas)
17. [Estructura del repositorio](#estructura-del-repositorio)

---

## Qué incluye

- **Inventario y catálogo**: productos con código de barras, categorías, proveedores, precios y stock mínimo.
  Autocompletado por código de barras: un catálogo incluido de productos argentinos reales (funciona sin internet) y
  Open Food Facts para el resto.
- **Carga de mercadería desde el celular**: escáner de códigos, lectura de vencimiento y número de lote por foto (OCR).
- **Varios lotes por producto**: cada ingreso es un lote con su vencimiento; las ventas descuentan por **FIFO** o **FEFO**.
- **Multi-sucursal**: catálogo compartido, stock por sucursal, vista consolidada y transferencias entre sucursales.
- **Ventas**: carga manual, importación CSV e integración con punto de venta (webhook con API key por sucursal y simulador).
- **Vencimientos y alertas**: por vencer, vencidos, stock bajo, sin stock, quiebre previsto, anomalías y ventas sin stock.
- **Inteligencia artificial**: patrones de venta, pronóstico de demanda, punto de pedido, riesgo por lote, descuentos
  sugeridos según elasticidad (que aprende de los resultados) y detección de anomalías.
- **Consola de dueños**: alta, baja y habilitación de comercios, métricas (MRR, churn, crecimiento), avisos y recalls.
- **Seguridad alimentaria**: los recalls detectan automáticamente los lotes afectados y los ponen en cuarentena, con alerta en tiempo real.
- **Soporte**: tickets y chat en vivo con adjuntos de imagen, indicador de escritura y agentes en línea.

## Requisitos

| Qué | Detalle |
|---|---|
| **Docker** | [Docker Desktop](https://www.docker.com/products/docker-desktop/) en Windows o macOS; en Linux, Docker Engine + plugin Compose v2 (`docker compose`). Asignale **al menos 4 GB de RAM** (recomendado 6 GB) y dejá ~8 GB de disco libres. |
| **Windows** | [Git para Windows](https://git-scm.com/download/win), que incluye **Git Bash**. Los comandos se ejecutan desde Git Bash (o con `.\start.ps1` desde PowerShell). |
| **Internet** | En el primer arranque (descarga imágenes y dependencias) y para autocompletar con Open Food Facts los productos que no están en el catálogo incluido. |
| **Celular** (opcional) | En la misma red WiFi que la PC, para usar la cámara. |

No hace falta instalar Java, Node ni Python: todo se compila dentro de Docker. Solo los necesitás para
[desarrollar sin Docker](#desarrollo-local-sin-docker).

## Inicio rápido

```bash
git clone <url-del-repositorio> gondolia
cd gondolia
./start.sh
```

En Windows abrí **Git Bash** en la carpeta del proyecto (clic derecho → *Open Git Bash here*). Desde PowerShell podés usar
`.\start.ps1`, que hace lo mismo a través de Git Bash.

`./start.sh` hace todo solo:

1. Verifica que Docker y Docker Compose v2 estén instalados y corriendo (si Docker Desktop está cerrado, intenta abrirlo).
2. Crea `.env` a partir de `.env.example` con un `JWT_SECRET` aleatorio.
3. Detecta las IPs de la PC en la red (para el certificado HTTPS y las URLs del celular).
4. Construye las imágenes y levanta los servicios (`db`, `ai`, `backend`, `web`).
5. Espera a que todos estén sanos y muestra las URLs, las cuentas demo y consejos de red.

El **primer arranque tarda 10-15 minutos** (descarga dependencias de Maven, npm y Python, y carga 180 días de datos demo).
Los siguientes tardan alrededor de un minuto.

## Comandos de start.sh

| Comando | Qué hace |
|---|---|
| `./start.sh` o `./start.sh start` | Construye e inicia todo, espera a que esté sano y muestra URLs y cuentas. Si ya estaba corriendo, aplica los cambios de código o de `.env`. |
| `./start.sh stop` | Detiene GondolIA. **Conserva** la base de datos, los adjuntos y los certificados. |
| `./start.sh stop --borrar [-y]` | Detiene y **borra todo**: contenedores, volúmenes (base de datos y adjuntos), imágenes del proyecto y certificados (`certs/`). Pide confirmación salvo con `-y`. `--purge` es sinónimo. |
| `./start.sh restart` | Detiene y vuelve a iniciar: reconstruye si hubo cambios y vuelve a detectar las IPs (usalo si cambiaste de red WiFi). |
| `./start.sh logs [servicio]` | Logs en vivo de todos los servicios o de uno: `db`, `ai`, `backend`, `web`. Salís con Ctrl+C. |
| `./start.sh status` | Estado de cada servicio y URLs de acceso. |
| `./start.sh reset [-y]` | Borra la base de datos y los adjuntos e inicia de nuevo: se vuelven a cargar los datos demo. Pide confirmación salvo con `-y`. |
| `./start.sh db` | Inicia solo PostgreSQL (para desarrollar sin Docker). |
| `./start.sh help` | Muestra la ayuda. |

Variables útiles al ejecutarlo:

```bash
LAN_IPS=192.168.0.10 ./start.sh        # fuerza la IP del certificado y de las URLs del celular
GONDOLIA_WAIT_TIMEOUT=1500 ./start.sh  # segundos máximos de espera (por defecto 900 el primer arranque, 600 después)
NO_COLOR=1 ./start.sh                  # salida sin colores
```

## URLs y puertos

| Qué | URL |
|---|---|
| App en esta PC | <http://localhost:8080> |
| App por HTTPS en esta PC | <https://localhost:8443> |
| App desde el celular u otra PC | `https://<IP-de-la-PC>:8443` (start.sh imprime la URL exacta) |
| CA local para instalar en el celular | `http://<IP-de-la-PC>:8080/gondolia-ca.crt` |
| Documentación de la API (Swagger) | <http://localhost:8080/api/swagger-ui.html> |
| OpenAPI (JSON) | <http://localhost:8080/api/docs> |
| PostgreSQL (solo desde esta PC) | `localhost:5433` · base, usuario y clave `gondolia` |

Los puertos se cambian en `.env` (`HTTP_PORT`, `HTTPS_PORT`, `DB_PORT`). Desde la red, cualquier acceso por HTTP se
redirige a HTTPS (salvo la descarga de la CA); en la propia PC podés usar HTTP sin problemas.

## Cuentas demo

Se crean automáticamente la primera vez que arranca con la base vacía (y otra vez después de `./start.sh reset`).
Contraseñas: **plataforma `Gondolia2026!`** · **comercios `Demo2026!`**.

**Plataforma GondolIA**

| Email | Rol |
|---|---|
| `dueno@gondolia.app` | Dueño GondolIA |
| `socia@gondolia.app` | Dueña GondolIA |
| `soporte@gondolia.app` | Soporte |
| `soporte2@gondolia.app` | Soporte |

**Comercios**

| Comercio | Email | Rol | Sucursales a las que accede |
|---|---|---|---|
| **Almacén Don Pepe**<br>Almacén · Básico · CABA<br>1 sucursal · FIFO | `jefe@donpepe.com` | Jefe | Todas |
| | `admin@donpepe.com` | Administrador | Todas |
| | `empleado@donpepe.com` | Empleado | Sucursal Principal |
| **Dietética Vida Sana**<br>Dietética · Profesional · Córdoba<br>2 sucursales · FEFO | `jefe@vidasana.com` | Jefe | Todas (Nueva Córdoba y Cerro de las Rosas) |
| | `admin@vidasana.com` | Administrador | Todas |
| | `empleado@vidasana.com` | Empleado | Nueva Córdoba |
| **Minimercado El Sol**<br>Minimercado · Profesional · Rosario<br>3 sucursales · FIFO | `jefe@elsol.com` | Jefe | Todas (Centro, Fisherton y Echesortu) |
| | `admin@elsol.com` | Administrador | Todas |
| | `empleado@elsol.com` | Empleado | Centro y Fisherton |
| | `empleado.echesortu@elsol.com` | Empleado | Echesortu |
| **Kiosco La Esquina**<br>Kiosco · Freemium · **deshabilitado** | `admin@laesquina.com` | Administrador | — (no puede entrar: sirve para mostrar el bloqueo) |

Además hay ~12 comercios "livianos" (distintos planes y estados, 2 dados de baja) repartidos en 12 meses para que las
métricas de la consola de dueños tengan historia.

Datos preparados para la demo:

- **Recall listo**: "Sopa de tomate en lata La Huerta 340 g", EAN `7791234500012`, lote `L2409A`, en stock de
  **Almacén Don Pepe** y de **Minimercado El Sol solo en la sucursal Fisherton** (Vida Sana no lo tiene).
- **Varios lotes por producto** en cada sucursal, incluido al menos un caso en el que un lote más nuevo vence antes que uno más viejo.
- Patrones de venta variados (fin de semana fuerte, estable, intermitente, creciente, en declive, sin movimiento, picos
  anómalos), lotes por vencer con sobrestock, productos bajo mínimo, vencidos pendientes, tickets de soporte con
  conversación y un recall pendiente (Dulce de leche, lote `DV2603B`) para mostrar y resolver en la demo.

> Con `APP_DEV_FIXTURE=true` (solo para desarrollo) se crea en cambio un comercio mínimo: `jefe@prueba.com`,
> `admin@prueba.com`, `empleado@prueba.com` (solo Sucursal Centro), `admin@otro.com` y `soporte@gondolia.app`, todos con `Demo2026!`.

## Roles y permisos

**Roles de la plataforma**

- **Dueño GondolIA** (`PLATFORM_OWNER`): da de alta, deshabilita, da de baja y reactiva comercios; ve métricas
  agregadas (comercios, sucursales, usuarios, MRR, churn, crecimiento, soporte); publica avisos y recalls; gestiona el
  equipo interno. **Nunca ve datos de negocio** de un comercio (productos, stock, ventas, alertas, chats): de un recall
  solo ve **cuántos** comercios están afectados, no cuáles.
- **Soporte** (`SUPPORT_AGENT`): atiende la bandeja de tickets y el chat en vivo de todos los comercios. Solo ve lo que
  el cliente envía en el ticket (texto e imágenes) y el nombre del comercio y del usuario. Para resolver un ticket
  también entra a **Clientes** y **Módulos por cliente**: corrige los datos administrativos de un comercio, activa o
  desactiva sus módulos y restablece la contraseña de su administrador; cada cambio queda en el historial del comercio
  a su nombre. El alta, el cambio de plan, el bloqueo, la baja, la eliminación y las métricas siguen siendo del dueño.

**Roles de un comercio**

| Funcionalidad | Jefe | Administrador | Empleado |
|---|:-:|:-:|:-:|
| Inicio, Estadísticas, Inteligencia IA y Alertas (ver) | ✔ | ✔ | ✘ |
| Aceptar/descartar recomendaciones, gestionar alertas, recalcular IA | ✘ | ✔ | ✘ |
| Inventario: ver, crear y editar productos | ✘ | ✔ | ✔ |
| Eliminar productos, categorías y proveedores | ✘ | ✔ | ✘ |
| Carga de mercadería (lotes, escáner, OCR) | ✘ | ✔ | ✔ (en sus sucursales) |
| Ver todas las sucursales / cambiar de sucursal | ✔ | ✔ | Solo entre las asignadas |
| Gestionar sucursales y asignar empleados | ✘ | ✔ | ✘ |
| Transferencias de stock entre sucursales | ✘ | ✔ | ✘ |
| Vencimientos (ver) y descartar vencidos o dañados | ✘ | ✔ | ✔ |
| Ventas, importación CSV, integración POS, ajustes, historial de movimientos | ✘ | ✔ | ✘ |
| Usuarios del comercio y Configuración | ✘ | ✔ | ✘ |
| Avisos y Seguridad alimentaria (ver y marcar "Entendido") | ✔ | ✔ | ✔ |
| Resolver un recall (retirar de stock) | ✘ | ✔ | ✔ |
| Soporte (tickets y chat en vivo), perfil y notificaciones | ✔ | ✔ | ✔ |

- **Jefe**: toma decisiones mirando tableros en modo lectura. Entra a *Inicio*.
- **Administrador**: máximo nivel dentro del comercio. Entra a *Inicio*.
- **Empleado**: carga mercadería y controla vencimientos desde el celular. Entra a *Carga de mercadería*.

Si los dueños **deshabilitan** un comercio, todos sus usuarios quedan bloqueados al instante: las sesiones abiertas se
cierran solas con el mensaje "El acceso de tu comercio está deshabilitado. Comunicate con GondolIA." Es reversible.

## Multi-sucursal

Un comercio puede tener varios locales. Qué se comparte y qué no:

| Compartido por el comercio | Propio de cada sucursal |
|---|---|
| Productos, categorías, proveedores, precios y stock mínimo, usuarios, configuración | Stock y lotes, ventas y movimientos, transferencias, vencimientos, alertas, análisis de IA y recomendaciones, coincidencias de recall, API key del punto de venta |

- **Selector de sucursal** en la barra superior: elegís una sucursal o **"Todas las sucursales"** (vista consolidada,
  solo si tenés acceso a más de una). Los listados muestran la columna *Sucursal* y el Inicio incluye una comparativa entre sucursales.
- **Acceso**: jefe y administrador ven todas las sucursales activas; cada empleado solo las que el administrador le asigna
  en *Usuarios* (al menos una). Un empleado nunca ve stock, ventas, alertas ni recalls de otras sucursales.
- **Operaciones de escritura** (cargar un lote, vender, ajustar, descartar, resolver un recall) se hacen siempre sobre
  **una** sucursal: si estás en "Todas", la app te pide elegir.
- **Transferencias** (administrador): mueven unidades de lotes concretos de una sucursal a otra. El lote de destino conserva
  número, vencimiento, costo y **fecha de ingreso original** (así mantiene su lugar en FIFO). No se transfieren lotes vencidos ni en cuarentena.
- **Stock mínimo** es por producto y aplica a cada sucursal por separado.
- **Límites y precio por plan** (la suscripción se cobra por sucursal activa):

  | Plan | Sucursales máximas | Precio mensual por sucursal |
  |---|:-:|--:|
  | Freemium | 1 | $ 0 |
  | Básico | 3 | $ 25.000 |
  | Profesional | 10 | $ 55.000 |

- Todo comercio tiene al menos una sucursal activa. No se puede desactivar la última ni una que todavía tenga stock.
- Técnicamente, el frontend envía la sucursal elegida en el header `X-Branch-Id` (id numérico, o `all` para todas las accesibles).

## Lotes, vencimientos y rotación FIFO/FEFO

**Cada ingreso de mercadería crea un lote nuevo**, con su cantidad, vencimiento, número de lote, costo y fecha y hora de
ingreso, aunque el producto ya tenga stock (incluso con el mismo número de lote o la misma fecha). Así un producto convive
con varios lotes y el orden de salida es exacto.

| Stock | Qué cuenta |
|---|---|
| **Vendible** | Lotes activos no vencidos. Es el que se vende y el que se compara con el mínimo. |
| **Vencido pendiente** | Lotes activos con vencimiento pasado: **nunca se venden**; se descartan desde *Vencimientos*. |
| **En cuarentena** | Lotes alcanzados por un recall: no se venden hasta resolverlo. |
| **Físico** | Activos + cuarentena. Es el "Stock total" y la base del valor de inventario. |

**Rotación** (se elige por comercio en *Configuración*):

- **FIFO** (por defecto) — *primero sale lo que entró antes*: orden por fecha de ingreso.
- **FEFO** — *primero sale lo que vence antes*: orden por vencimiento (los lotes sin vencimiento, al final) y después por ingreso.

Ejemplo con tres lotes del mismo yogur en una sucursal:

| Lote | Ingresó | Vence | Unidades | Orden FIFO | Orden FEFO |
|---|---|---|--:|:-:|:-:|
| A | 01/09 | 30/09 | 10 | 1º | 2º |
| B | 05/09 | 20/09 | 12 | 2º | 1º |
| C | 10/09 | 15/10 | 20 | 3º | 3º |

Una venta de 15 unidades con FIFO descuenta 10 de A y 5 de B; con FEFO, 12 de B y 3 de A. El lote que se va a consumir
primero se marca en la app con **"Se vende primero"**.

- Con FIFO, si cargás un lote que **vence antes** que mercadería que ingresó antes (el lote B del ejemplo), la carga
  muestra un aviso: se venderá después y conviene revisarlo o aplicar un descuento. La IA también lo tiene en cuenta al calcular el riesgo por lote.
- Si una venta pide más de lo vendible, lo que falta se registra igual (sin lote) y se abre una alerta de **venta sin stock**.
- Un lote puede tener un **descuento** (por ejemplo, al aceptar una recomendación de la IA): las unidades vendidas de ese lote salen con ese precio.
- **Vencimientos**: *Vencido* (ya venció), *Crítico* (vence en 5 días o menos), *Por vencer* (15 días o menos) y
  *Próximo* (30 días o menos). Los umbrales de crítico y por vencer se ajustan en *Configuración*.
- **Recalls**: si un lote coincide con un recall publicado (por código de barras, número de lote y rango de vencimiento),
  queda en cuarentena al instante, también si coincide al momento de cargarlo o al recibirlo por transferencia.

## Usar la cámara desde el celular

Los navegadores solo permiten usar la cámara en páginas seguras (HTTPS). Por eso GondolIA genera una **CA local** y un
certificado para las IPs de tu PC. Se guardan en `certs/` y la CA se conserva entre reinicios.

1. Conectá el celular a **la misma red WiFi** que la PC.
2. Ejecutá `./start.sh` y abrí en el celular la URL que imprime: `https://<IP-de-la-PC>:8443`.
3. El navegador va a advertir que la conexión no es privada, porque no conoce la CA local. Tenés dos opciones:
   - **Rápida**: aceptá el aviso. En Chrome: *Configuración avanzada → Acceder a … (sitio no seguro)*. En Safari:
     *Mostrar detalles → visitar este sitio web*. La cámara funciona igual.
   - **Recomendada** (una sola vez por celular): instalá la CA y el aviso desaparece.
4. Iniciá sesión (por ejemplo `empleado@elsol.com`), entrá a **Carga de mercadería** y permití el acceso a la cámara.

**Instalar la CA en Android**

1. Abrí `http://<IP-de-la-PC>:8080/gondolia-ca.crt` en Chrome: se descarga `gondolia-ca.crt`.
2. Andá a *Ajustes → Seguridad y privacidad → Más ajustes de seguridad → Cifrado y credenciales → Instalar un certificado →
   Certificado de CA → Instalar de todas formas* y elegí el archivo descargado. (Los nombres cambian según la marca: buscá
   "certificado de CA" en el buscador de Ajustes.)
3. Cerrá y volvé a abrir Chrome.

**Instalar la CA en iPhone o iPad**

1. Abrí `http://<IP-de-la-PC>:8080/gondolia-ca.crt` en **Safari** y tocá *Permitir* para descargar el perfil.
2. Andá a *Ajustes → Perfil descargado → Instalar* (ingresá el código del teléfono si lo pide).
3. Activá la confianza total: *Ajustes → General → Información → Ajustes de confianza de certificados* → encendé
   **GondolIA CA local**.

**Firewall y redes**

- **Windows**: la primera vez, Docker Desktop pide permiso en el Firewall; aceptalo para redes privadas y marcá tu WiFi
  como *Privada*. Si el celular igual no conecta, abrí los puertos desde una terminal **como administrador**:

  ```powershell
  netsh advfirewall firewall add rule name="GondolIA" dir=in action=allow protocol=TCP localport=8080,8443 profile=private
  ```

- **Redes de facultad, bares u hoteles** suelen aislar a los dispositivos entre sí: el celular no ve a la PC. Solución:
  activá el hotspot del celular, conectá la PC a esa red y ejecutá `./start.sh restart`.
- Si cambiás de red, la IP de la PC cambia: ejecutá `./start.sh restart` para regenerar el certificado con la IP nueva.
  La CA sigue siendo la misma, así que no hace falta reinstalarla.
- Una **VPN** activa en la PC puede ocultar la IP real: desactivala o indicá la IP con `LAN_IPS` en `.env`.

> La CA local es solo para desarrollo. No compartas `certs/gondolia-ca.key` y quitá la CA del celular cuando ya no la
> uses (desde los mismos ajustes). `./start.sh stop --borrar` la elimina y la próxima vez se genera una nueva.

## Arquitectura

```
                 ┌───────────────────────── docker compose (proyecto "gondolia") ─────────────────────────┐
 navegador PC ──►│ web (nginx + React build)  :80 → host ${HTTP_PORT:-8080}                                 │
 celular WiFi ──►│   TLS autofirmado          :443 → host ${HTTPS_PORT:-8443}                               │
                 │   /api/** , /ws  ──proxy──► backend (Spring Boot, :8080 interno)                          │
                 │                                 │  JDBC            │ HTTP                                 │
                 │                                 ▼                  ▼                                      │
                 │                          db (PostgreSQL 16)   ai (FastAPI :8000 interno)                  │
                 └──────────────────────────────────────────────────────────────────────────────────────────┘
```

| Servicio | Tecnología | Expuesto en la PC | Función |
|---|---|---|---|
| `web` | nginx 1.27 + build de React | `8080` (HTTP) y `8443` (HTTPS) | Sirve la app, termina TLS, genera la CA y el certificado, hace de proxy de `/api` y `/ws` |
| `backend` | Spring Boot 3.5 · Java 21 | — (solo red interna) | API REST, WebSocket, reglas de negocio, tareas programadas, datos demo |
| `ai` | Python 3.12 · FastAPI | — (solo red interna) | Análisis de ventas y stock, pronósticos, recomendaciones, OCR y lectura de códigos |
| `db` | PostgreSQL 16 | `127.0.0.1:5433` | Base de datos (volumen `pgdata`); los adjuntos van al volumen `uploads` |

### Backend (`backend/`)

Spring Boot 3.5, Java 21 y Maven. Multi-tenant por discriminador: toda tabla de negocio lleva `tenant_id`, que siempre
sale del usuario autenticado (nunca del request), y los datos por sucursal validan el acceso con `BranchAccessService`.
Esquema versionado con Flyway (`db/migration/V1__schema.sql`), autenticación JWT y WebSocket STOMP en `/ws`.

| Paquete (`com.gondolia.*`) | Responsabilidad |
|---|---|
| `security`, `auth`, `config`, `common`, `domain` | Núcleo: JWT, acceso por rol y sucursal, errores, entidades |
| `stock`, `recall`, `storage`, `ai`, `notification`, `realtime`, `bootstrap` | Servicios compartidos: rotación de lotes, coincidencia de recalls, archivos, cliente de IA, notificaciones y tiempo real |
| `catalog` | Productos, categorías, proveedores, carga de lotes, OCR y escáner |
| `movements` | Ventas, CSV, movimientos, transferencias, vencimientos e integración POS |
| `analytics`, `alerts`, `insights` | Tableros, estadísticas, motor de alertas e integración con la IA |
| `platform` | Consola de dueños: comercios, métricas y equipo |
| `announcements` | Avisos y recalls |
| `support` | Tickets y chat en vivo |
| `tenantadmin` | Usuarios, sucursales y configuración del comercio |
| `seed` | Datos demo |

La documentación de cada módulo está en `docs/api-*.md` y la API navegable en Swagger (`/api/swagger-ui.html`).

### Servicio de IA (`ai-service/`)

FastAPI sin estado (todo el contexto llega en cada request), solo accesible desde el backend.

- `POST /v1/analyze`: por sucursal, rellena la serie diaria de ventas y pronostica con Holt-Winters (estacionalidad
  semanal), Croston/SBA si la demanda es intermitente o media móvil ponderada con pocos datos; clasifica patrones (reglas +
  KMeans), ABC/XYZ, detecta anomalías (z-score robusto + IsolationForest), calcula punto de pedido y stock de seguridad,
  simula el consumo de cada lote según FIFO/FEFO para estimar unidades en riesgo y sugiere el descuento mínimo usando la
  elasticidad por categoría ajustada con el resultado de descuentos anteriores. Devuelve recomendaciones explicadas en español.
- `POST /v1/ocr`: lee etiquetas (OpenCV + Tesseract `spa+eng`) y extrae vencimientos, números de lote y fechas de elaboración.
- `POST /v1/barcode`: lee códigos de barras de una foto (zxing-cpp).
- `GET /health`.

### Frontend (`frontend/`)

React 18 + TypeScript + Vite 5 + Tailwind CSS 3, con React Query, React Router, STOMP para tiempo real, Recharts y
ZXing para el escáner. Diseño *mobile first* para empleados. Cada módulo vive en `src/features/<módulo>/`.
En Docker lo sirve nginx (`frontend/nginx/`): SPA con cache de assets, gzip, headers de seguridad una sola vez por
respuesta (`Permissions-Policy: camera=(self)`, `X-Frame-Options: DENY`, `nosniff`, sin HSTS) y una
`Content-Security-Policy` para la app, proxy a `/api` y `/ws` (sus errores propios, como un archivo de más de 15 MB o el
backend caído, responden con el JSON de error de la API), y el script `docker-entrypoint.d/40-gondolia-certs.sh`
que genera la CA local (10 años) y el certificado del servidor (825 días) con las IPs de `LAN_IPS`, `CERT_HOSTNAMES`,
`localhost` y `127.0.0.1`, regenerándolo solo cuando cambian.

### Tiempo real

Por WebSocket (`/ws`, STOMP) llegan las notificaciones, las **alertas de recall** (a todos los usuarios con acceso a la
sucursal afectada), el cierre forzado de sesión cuando se deshabilita un comercio, y los mensajes, indicadores de escritura
y agentes en línea del chat de soporte.

## Guion de demo

Preparación: `./start.sh reset -y` para partir de datos limpios (opcional), un navegador normal, una ventana de incógnito
y un celular en la misma red WiFi.

1. **Tablero multi-sucursal** — Entrá con `jefe@elsol.com`. En *Inicio* elegí "Todas las sucursales": tarjetas de
   productos, por vencer, stock bajo y valor de inventario, tendencia de ventas y comparativa entre Centro, Fisherton y
   Echesortu. Cambiá a una sucursal y mirá cómo cambian los números. El jefe solo lee: no ve inventario ni ventas.
2. **Carga con el celular y rotación** — En el celular entrá con `empleado@elsol.com` (Centro y Fisherton). En *Carga de
   mercadería* elegí la sucursal, escaneá un código de barras y sacale una foto a la etiqueta para leer vencimiento y lote.
   Cargá un lote que venza **antes** que el stock existente: aparece el aviso de FIFO y la lista "ya tenés X u. con
   vencimiento…". En el detalle del producto se ven todos los lotes y cuál "Se vende primero". Para mostrar el alta de
   un producto nuevo, escaneá o tipeá uno de los productos reales de
   [`docs/datos-demo.md` §6](docs/datos-demo.md#6-códigos-de-barras-para-probar-la-carga-de-mercadería) (una yerba, una
   gaseosa, unas galletitas): nombre, marca, contenido y categoría se completan solos, aun sin internet.
3. **Recall en vivo** — Dejá abiertas las sesiones de `admin@elsol.com` (incógnito) y `empleado@elsol.com` (celular). Con
   `dueno@gondolia.app`, en *Avisos y recalls*, creá un recall de "Sopa de tomate en lata La Huerta 340 g", EAN
   `7791234500012`, lote `L2409A`. La vista previa informa **2 comercios afectados** (sin decir cuáles). Al publicar, el
   administrador y el empleado de El Sol reciben al instante la alerta de seguridad (el lote está en Fisherton);
   `empleado.echesortu@elsol.com` no la recibe y Vida Sana solo ve el aviso general. En *Seguridad alimentaria* marcá
   "Entendido" y retirá el lote del stock. Volvé a la consola del dueño: solo ve cantidades.
4. **Recomendaciones de IA** — Con `admin@elsol.com`, en *Inteligencia IA*, elegí una sucursal: patrones de venta,
   pronóstico, días de cobertura y recomendaciones explicadas (reponer, descontar un lote en riesgo, revisar una anomalía).
   Aceptá un descuento: el lote queda con ese precio en las ventas. Con "Recalcular" se vuelve a analizar cada sucursal.
   Probá también `admin@vidasana.com` (FEFO) para comparar el orden de consumo de los lotes.
5. **Soporte en vivo** — Con `empleado@donpepe.com` abrí el botón flotante de soporte y empezá un chat. En otra ventana,
   `soporte@gondolia.app` lo ve entrar en la *Bandeja de soporte*, lo toma y responde: indicador de "escribiendo", imagen
   adjunta, cambio de estado y calificación al cerrar.
6. **Deshabilitar un comercio** — Con la sesión de `admin@donpepe.com` abierta, el dueño entra a *Clientes → Almacén Don
   Pepe → Deshabilitar* con un motivo: la sesión del comercio se cierra sola con el aviso de bloqueo y no puede volver a
   entrar. Habilitalo de nuevo y el acceso vuelve. (`admin@laesquina.com` ya está deshabilitado desde el inicio.)
7. **Métricas de la plataforma** — Con `dueno@gondolia.app`, en *Métricas*: comercios por estado y plan, sucursales,
   MRR (por sucursal activa), conversión de freemium a pago, crecimiento de 12 meses, soporte y recalls.

## Desarrollo local sin Docker

Requisitos: Java 21 + Maven 3.9, Node 22, Python 3.12 y [Tesseract](https://github.com/tesseract-ocr/tesseract) con los
idiomas `spa` y `eng` (solo para el OCR). Antes, detené el entorno completo con `./start.sh stop`, porque el backend local
usa el puerto 8080.

**1. Base de datos**

```bash
./start.sh db      # PostgreSQL en localhost:5433 (base, usuario y clave: gondolia)
```

**2. Servicio de IA** (en `http://localhost:8000`)

```bash
cd ai-service
python -m venv .venv
source .venv/Scripts/activate      # Git Bash en Windows · Linux/macOS: source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8000
pytest                             # tests
```

Si no querés instalar Python ni Tesseract, levantalo en Docker publicando el puerto:
`docker compose run --rm -p 8000:8000 ai`.

**3. Backend** (en `http://localhost:8080`)

```bash
cd backend
DB_PORT_INTERNAL=5433 mvn spring-boot:run
mvn test                           # tests
```

En PowerShell: `$env:DB_PORT_INTERNAL="5433"; mvn spring-boot:run`. Con la base vacía carga los datos demo; para un
comercio mínimo usá `APP_SEED_DEMO=false APP_DEV_FIXTURE=true`. Por defecto busca la IA en `http://localhost:8000`
(`AI_SERVICE_URL`) y guarda los adjuntos en `backend/data/uploads`.

**4. Frontend** (en `http://localhost:5173`)

```bash
cd frontend
npm ci
npm run dev                        # proxy de /api y /ws hacia http://localhost:8080
npm run build                      # verificación de tipos + build de producción
```

Para apuntar a otro backend: `GONDOLIA_BACKEND_URL=http://otra-pc:8080 npm run dev`. Con `npm run dev` la cámara solo
funciona en `localhost`; para probar con el celular usá el entorno completo (`./start.sh`).

Los contratos compartidos entre módulos están en [`SPEC.md`](SPEC.md) y en
`backend/src/main/resources/db/migration/V1__schema.sql`.

## Configuración (.env)

`./start.sh` crea `.env` desde [`.env.example`](.env.example), donde cada variable está comentada. Después de editarlo,
aplicá los cambios con `./start.sh restart`.

| Variable | Por defecto | Para qué sirve |
|---|---|---|
| `HTTP_PORT` | `8080` | Puerto HTTP en la PC |
| `HTTPS_PORT` | `8443` | Puerto HTTPS (celular y red local) |
| `DB_PORT` | `5433` | Puerto de PostgreSQL en la PC (solo `127.0.0.1`) |
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | `gondolia` | Base de datos (se aplican al crearla: si los cambiás, `./start.sh reset`) |
| `JWT_SECRET` | aleatorio (lo genera start.sh) | Firma de las sesiones; cambiarlo cierra todas |
| `JWT_EXPIRATION_HOURS` | `12` | Duración de la sesión |
| `APP_SEED_DEMO` | `true` | Carga los datos demo si no hay comercios |
| `APP_DEV_FIXTURE` | `false` | Comercio mínimo para desarrollo |
| `APP_BOOTSTRAP_OWNER_EMAIL` / `APP_BOOTSTRAP_OWNER_PASSWORD` | `dueno@gondolia.app` / `Gondolia2026!` | Dueño inicial de la plataforma |
| `APP_OPENFOODFACTS_ENABLED` | `true` | Autocompletar por código de barras con Open Food Facts (usa internet) lo que no está en el catálogo incluido |
| `APP_TIMEZONE` | `America/Argentina/Buenos_Aires` | Zona horaria del negocio (qué es "hoy") |
| `LAN_IPS` | detectadas por start.sh | IPs para el certificado y las URLs del celular (separadas por coma) |
| `CERT_HOSTNAMES` | vacío | Hostnames extra para el certificado (ej.: `mi-pc.local`) |
| `APP_CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | Orígenes que acepta el backend; en Docker no hace falta tocarlo |
| `JAVA_OPTS` | `-XX:MaxRAMPercentage=40.0 …` | Opcional: memoria y opciones de la JVM del backend |

## Pruebas y despliegue

Cada pull request contra `main` corre las pruebas de los tres servicios y la configuración de nginx
(`.github/workflows/ci.yml`); en una rama sin pull request se corren a mano desde
*Actions → Pruebas*. El despliegue a la VM de Oracle depende de que esas pruebas pasen, así que un
merge a `main` en rojo no llega al servidor.

```bash
cd backend    && mvn verify                 # unitarias (+ integración con -Dgondolia.it=true)
cd frontend   && npm run test:coverage      # Vitest + cobertura
cd ai-service && pytest                     # pytest + cobertura
bash frontend/nginx/test-config.sh          # configuración de nginx y borrado del header Origin
```

El inventario completo de secrets y variables (los de GitHub, los del `.env` de la VM y los de las
imágenes), y cómo exigir los checks para poder mergear, está en
[`docs/ci-y-variables.md`](docs/ci-y-variables.md). El despliegue en sí está en
[`deploy/README-DESPLIEGUE.md`](deploy/README-DESPLIEGUE.md).

## Solución de problemas

**"Docker no está corriendo"** — Abrí Docker Desktop y esperá a que diga *Engine running* (start.sh intenta abrirlo
solo). En Linux: `sudo systemctl start docker`; si dice *permission denied*, agregá tu usuario al grupo `docker`.

**Puerto ocupado** (`port is already allocated`, `address already in use`) — Otro programa usa 8080, 8443 o 5433.
Cambiá `HTTP_PORT`, `HTTPS_PORT` o `DB_PORT` en `.env` (por ejemplo 8081, 8444, 5434) y volvé a ejecutar `./start.sh`.
Para ver quién lo usa: `netstat -ano | findstr :8080` (Windows) o `lsof -i :8080` (Linux/macOS).

**El primer arranque tarda mucho o se agota el tiempo de espera** — Es normal que tarde 10-15 minutos: se descargan
dependencias y se cargan los datos demo. Si se cortó por tiempo, ejecutá `./start.sh` de nuevo (lo ya descargado queda
en caché) o aumentá la espera: `GONDOLIA_WAIT_TIMEOUT=1800 ./start.sh`.

**Un servicio no arranca** — start.sh muestra las últimas líneas del servicio con problemas. Para ver más:
`./start.sh logs backend` (o `ai`, `db`, `web`) y `./start.sh status`. Si el backend se reinicia por falta de memoria,
asigná más RAM a Docker Desktop (*Settings → Resources*) o bajá `JAVA_OPTS` en `.env`.

**El celular no abre la página** — Revisá que esté en la misma WiFi, que la URL use `https://` y el puerto 8443, el
Firewall de Windows y el aislamiento de redes públicas (ver [Usar la cámara desde el celular](#usar-la-cámara-desde-el-celular)).
Si la IP detectada no es la correcta, fijala en `.env` (`LAN_IPS=192.168.0.10`) y ejecutá `./start.sh restart`.

**"La conexión no es privada" o la cámara no se activa** — La cámara necesita HTTPS: usá `https://<IP>:8443`, aceptá
el aviso o instalá la CA. Si hiciste `./start.sh stop --borrar`, la CA cambió: quitá la anterior del celular e instalá la nueva.
Revisá también que el navegador tenga permiso de cámara para el sitio.

**Cambié de red WiFi** — `./start.sh restart`.

**Quiero empezar de cero** — `./start.sh reset` borra la base y los adjuntos y vuelve a cargar los datos demo.
`./start.sh stop --borrar` borra además las imágenes y los certificados.

**`$'\r': command not found` o `/usr/bin/env: 'bash\r'`** — El archivo quedó con finales de línea de Windows. El
repositorio fuerza LF con `.gitattributes`; si lo copiaste de otra forma, ejecutá `sed -i 's/\r$//' start.sh`.

**`Permission denied` al ejecutar `./start.sh`** — `chmod +x start.sh`, o ejecutalo como `bash start.sh`.

**PowerShell no deja ejecutar `start.ps1`** — `powershell -ExecutionPolicy Bypass -File .\start.ps1`.

**`Falta JWT_SECRET en .env`** al usar `docker compose` directamente — Ejecutá `./start.sh` una vez para generar `.env`.

## Estructura del repositorio

```
gondolia/
├── start.sh                  # ./start.sh [start|stop [--borrar]|restart|logs|status|reset|db|help]
├── start.ps1                 # atajo para PowerShell (ejecuta start.sh con Git Bash)
├── docker-compose.yml        # servicios db, ai, backend y web
├── .env.example              # configuración documentada (start.sh crea .env)
├── README.md                 # esta guía
├── SPEC.md                   # especificación técnica (contrato entre módulos)
├── certs/                    # (generado, ignorado por git) CA local + certificado del servidor
├── .github/workflows/        # ci.yml (pruebas) y deploy.yml (despliegue a Oracle Cloud)
├── deploy/                   # despliegue en la VM de Oracle (deploy.sh + guía)
├── docs/                     # documentación de la API por módulo y docs/ci-y-variables.md
├── backend/                  # Spring Boot 3.5 · Java 21 · Maven (+ Dockerfile)
├── ai-service/               # Python 3.12 · FastAPI (+ Dockerfile)
└── frontend/                 # React 18 · TypeScript · Vite 5 · Tailwind 3
    ├── Dockerfile            # build con Node 22 → nginx 1.27
    └── nginx/                # configuración de nginx y script de certificados
```
