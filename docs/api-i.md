# Módulo I — Importación masiva Excel/CSV

Paquete backend `com.gondolia.imports` · frontend `features/imports` · SPEC §16.

Primera carga rápida (o migración desde la planilla que usaba el comercio) de productos y stock, con revisión y
corrección fila por fila antes de tocar el inventario, y exportación del catálogo con las mismas columnas para hacer
ajustes masivos en Excel y volver a subirlos.

## Permisos y alcance

| Regla | Cómo se aplica |
|---|---|
| Solo `TENANT_ADMIN` (§3.3) | `@PreAuthorize(Roles.TENANT_ADMIN)` sobre todo el controlador. Jefe, empleado y cajero reciben **403 `FORBIDDEN`**; soporte y dueños también (no entran a `/api/tenant/**`); sin token, **401**. |
| Módulos por comercio (§14) | La importación está **siempre incluida**: no lleva `@RequiresModule`. |
| Aislamiento entre comercios (§3.4) | El `tenantId` sale de `CurrentUser`. Toda búsqueda por id usa `findByIdAndTenantId` → una importación de otro comercio responde **404 `NOT_FOUND`** (detalle, filas, PATCH, bulk, apply, cancelar, CSV de errores). El historial solo lista las del comercio. |
| Sucursales (§3.5) | La sucursal por defecto se valida con `BranchAccessService.assertAccess` (una sucursal de otro comercio → **404**). La columna «Sucursal» del archivo solo acepta sucursales accesibles por quien importa. La exportación incluye solo los lotes del alcance elegido (`X-Branch-Id` o todas). |

## Endpoints

Base: `/api/tenant/imports`. Errores con el formato estándar (`{timestamp,status,error,code,message,path,fieldErrors}`).

| Método | Ruta | Descripción |
|---|---|---|
| GET | `/fields` | Campos importables con etiqueta, tipo, descripción, sinónimos y grupo (`producto`/`stock`). |
| GET | `/template?format=xlsx\|csv` | Plantilla con encabezados en español, 4 filas de ejemplo (la leche con 2 lotes) y hoja «Instrucciones» (xlsx). |
| GET | `/export?format=xlsx\|csv&includeStock=true` | Catálogo actual con **las mismas columnas** de la plantilla; con `includeStock` una fila por lote con stock (`ACTIVE`/`RECALLED`) del alcance, y una fila sin cantidad para los productos sin stock. |
| POST | `/` (multipart `file` [+ `sheetName`]) | Sube y parsea el archivo → `ImportJobDto` en `UPLOADED` con encabezados, mapeo sugerido y filas de muestra. |
| GET | `/?page=&size=` | Historial (`PageResponse<ImportJobSummaryDto>`), más nuevas primero. |
| GET | `/{id}` | Detalle (`ImportJobDto`). Mientras está `APPLYING`, `processedRows`/`progressPct` sirven para la barra de progreso. |
| PUT | `/{id}/mapping` | `{columnMapping, options}` → normaliza y valida **todas** las filas → `ImportJobDto` en `VALIDATED` con `preview`. |
| GET | `/{id}/rows?status=ALL\|VALID\|WARNING\|ERROR\|SKIPPED\|IMPORTED\|FAILED&q=&page=&size=` | Filas paginadas (máx. 200 por página). `q` busca en el número de fila y en los valores. |
| PATCH | `/{id}/rows/{rowId}` | `{data:{campo:valor}}` → revalida la fila → `{row, job}` (contadores actualizados). |
| POST | `/{id}/rows/bulk` | `{rowIds:[]\|null, filterStatus?, action:"SKIP"\|"UNSKIP"\|"SET_FIELD", field?, value?}` → `{affectedRows, job}`. `rowIds` vacío o `null` = todas las filas del filtro (`filterStatus`, o todas). |
| GET | `/{id}/errors.csv` | CSV (UTF-8 con BOM, `;`) con las filas con error/advertencia/falla, el motivo por celda y las columnas de la plantilla, listo para corregir y volver a subir. |
| POST | `/{id}/apply` | `{ignoreErrors:false}` → `ImportJobDto` en `APPLYING`; la aplicación sigue en segundo plano. |
| DELETE | `/{id}` | Cancela una importación no aplicada (`CANCELLED`). Si está `APPLYING`, pide cortarla al terminar el bloque en curso (lo ya aplicado queda). |

### Estados

- Importación: `UPLOADED` → `VALIDATED` → `APPLYING` → `APPLIED` | `FAILED` | `CANCELLED`. Solo se editan (mapeo, filas, bulk, apply) las que están en `UPLOADED`/`VALIDATED`; si no, **409 `CONFLICT`**.
- Fila: `PENDING` (sin validar), `VALID`, `WARNING`, `ERROR`, `SKIPPED` (omitida por el usuario o por `ignoreErrors`), `IMPORTED`, `FAILED` (la validación pasó pero `StockService` la rechazó al aplicar).
- Acción de la fila: `CREATE` (producto nuevo), `UPDATE` (actualiza datos del existente), `SKIP` (no toca el producto: existente sin `updateExisting` o fila repetida; el lote igual se carga).

### Ejemplos

**Subir** — `curl -F "file=@importacion-ejemplo.xlsx" -H "Authorization: Bearer …" /api/tenant/imports`

```json
{
  "id": 7, "status": "UPLOADED", "fileName": "importacion-ejemplo.xlsx", "fileFormat": "XLSX",
  "sheetName": "Listado", "sheetNames": ["Listado"],
  "headers": ["Cod. Barra", "Descripcion", "Marca", "Rubro", "Proveedor", "U. Medida", "Precio Costo", "Precio Venta",
              "Stock Min", "Perecedero", "Cantidad", "Nro Lote", "Vto", "Fecha Ingreso", "Sucursal"],
  "suggestedMapping": {"barcode": "Cod. Barra", "name": "Descripcion", "category": "Rubro", "quantity": "Cantidad",
                       "expiryDate": "Vto", "receivedAt": "Fecha Ingreso", "branch": "Sucursal", "...": "..."},
  "columnMapping": {"...": "igual a la sugerencia hasta que se guarde otro"},
  "options": {"updateExisting": true, "createCategories": true, "createSuppliers": true, "importStock": true,
              "defaultBranchId": null, "defaultBranchName": null, "dateFormat": "DMY"},
  "totalRows": 18, "validRows": 0, "warningRows": 0, "errorRows": 0, "skippedRows": 0,
  "processedRows": 0, "progressPct": 0, "result": null, "errorMessage": null,
  "createdAt": "2026-09-19T11:57:28Z", "createdByName": "Ana Rodríguez", "appliedAt": null,
  "sampleRows": [{"Cod. Barra": "7791234000012", "Descripcion": "Leche entera La Pradera 1 L", "Precio Venta": "$ 1.350,00", "...": "..."}],
  "preview": null
}
```

**Mapeo y validación** — `PUT /api/tenant/imports/7/mapping`

```json
{"columnMapping": {"barcode": "Cod. Barra", "name": "Descripcion", "quantity": "Cantidad", "branch": "Sucursal"},
 "options": {"updateExisting": true, "createCategories": true, "createSuppliers": true, "importStock": true,
             "defaultBranchId": 1, "dateFormat": "DMY"}}
```

Respuesta: el `ImportJobDto` en `VALIDATED` con los contadores y

```json
"preview": {"productsToCreate": 10, "productsToUpdate": 1, "lotsToCreate": 12, "unitsToLoad": 305,
            "categoriesToCreate": ["Congelados", "Bebidas"],
            "suppliersToCreate": ["Molinos del Sur", "Cafes del Litoral", "Frigorifico del Oeste", "Aguas del Valle"],
            "rowsToSkip": 0, "rowsWithErrors": 6, "recallWarnings": []}
```

Errores: sin columna de nombre → 400 `VALIDATION_ERROR` («Elegí qué columna del archivo tiene el nombre…»); columna
inexistente o asignada a dos campos → 400; `dateFormat` fuera de `DMY|MDY|YMD` → 400 con `fieldErrors[options.dateFormat]`;
sucursal por defecto ajena → 404.

**Fila** (`GET /rows`, elemento de `content`)

```json
{"id": 104, "rowNumber": 5,
 "raw": {"Descripcion": "Fideos guiseros Doña Rosa 500 g", "Precio Venta": "mil doscientos", "...": "..."},
 "data": {"barcode": "7798877000034", "name": "Fideos guiseros Doña Rosa 500 g", "salePrice": "mil doscientos",
          "quantity": "24", "branch": "Sucursal Centro", "...": "..."},
 "status": "ERROR", "action": "UPDATE",
 "messages": [
   {"field": "salePrice", "level": "ERROR", "message": "El precio de venta no es un número: «mil doscientos»."},
   {"field": null, "level": "WARNING", "message": "El producto ya existe en tu catálogo (código 7798877000034): se van a actualizar sus datos."},
   {"field": "quantity", "level": "WARNING", "message": "El producto ya tiene 48 unidades en Sucursal Centro: se suma un lote nuevo."}],
 "productId": null, "lotId": null}
```

`field = null` son mensajes de la fila entera (producto existente, fila repetida). Después de aplicar, `productId` y
`lotId` apuntan a lo creado.

**Corregir una celda** — `PATCH /rows/104` `{"data": {"salePrice": "1.200"}}` → `{"row": {…"status": "WARNING"…}, "job": {…"errorRows": 5…}}`.
Campo desconocido → 400 («El campo «precio» no se puede importar.»); fila de otra importación → 404.

**Acción masiva** — `POST /rows/bulk` `{"rowIds": [108], "action": "SET_FIELD", "field": "branch", "value": "Norte"}` →
`{"affectedRows": 1, "job": {…}}`. `{"rowIds": null, "filterStatus": "ERROR", "action": "SKIP"}` omite todas las filas con error.
Acción inválida → 400; sin `action` → 400 con `fieldErrors[action]`.

**Aplicar** — `POST /apply` `{"ignoreErrors": false}` con errores sin omitir → **409 `IMPORT_HAS_ERRORS`** («Hay 6 filas con error.
Corregilas o marcá «Omitir las filas con error».»). Con `ignoreErrors: true` las filas con error pasan a `SKIPPED` y se aplica el
resto. Al terminar:

```json
"result": {"productsCreated": 12, "productsUpdated": 1, "lotsCreated": 14, "unitsLoaded": 361, "categoriesCreated": 2,
           "suppliersCreated": 4, "rowsSkipped": 4, "rowsFailed": 0, "recallMatches": 0}
```

y una notificación `SYSTEM` para quien la lanzó («Importación terminada: importacion-ejemplo.csv» · link `/app/imports/{id}`).

## Parseo (SPEC §16.2)

- **XLSX/XLS** con Apache POI: primera hoja no vacía o `sheetName` (inexistente → 400); fechas nativas y seriales de Excel,
  fórmulas evaluadas (si no se pueden evaluar, se usa el último valor guardado), números sin notación científica. Máx. 80 columnas.
- **CSV** con Apache Commons CSV: separador detectado entre `;`, `,`, tab y `|`; codificación UTF-8 (con o sin BOM) o
  **Windows-1252** si el contenido no es UTF-8 válido (así guarda Excel en español).
- Máx. **10 MB** y **10.000 filas** (400 `INVALID_FILE`). Encabezado = primera fila no vacía; las filas vacías se ignoran;
  encabezados vacíos o repetidos se renombran («Columna 3», «Precio (2)»). Otros formatos → 400 `INVALID_FILE`.
- **Auto-mapeo**: se comparan encabezados y sinónimos sin acentos, mayúsculas ni signos (`Cod. Barra` = `cod barra`);
  primero coincidencias exactas, después parciales (prefijo o contenido); cada encabezado se usa una sola vez.
- **Números** es-AR y en: `$ 1.234,50`, `1234.5`, `1.234`, `12,5`. **Booleanos**: sí/si/s/x/1/true/verdadero/ok y no/n/0/false/falso.
  **Fechas**: `dd/mm/aaaa`, `dd-mm-aa`, `aaaa-mm-dd`, seriales de Excel; el orden (`DMY`, `MDY`, `YMD`) sale de las opciones.
- **Unidades**: UNIDAD, KG, LITRO, PAQUETE, CAJA y sus variantes (`u`, `un`, `kilo`, `grs`, `lt`, `ml`, `paq`, `bolsa`,
  `bulto`…). Lo que no se reconoce (p. ej. `bidon`) da advertencia y se carga como UNIDAD.

## Validación (SPEC §16.2)

**ERROR** (bloquea la fila): falta nombre; precio o cantidad no numérico o negativo; cantidad no entera; fecha inválida
(`31/02/2027`); sucursal inexistente o no accesible; cantidad > 0 sin sucursal en la fila ni sucursal por defecto; categoría o
proveedor inexistente con la opción de crear apagada; texto demasiado largo (nombre > 200, marca/categoría > 100, proveedor > 150, descripción > 2.000, código > 32, lote); código con
caracteres no alfanuméricos; producto dado de baja al que se le quiere cargar stock sin `updateExisting`.

**WARNING** (se importa igual): dígito verificador EAN-8/12/13 inválido; precio de venta menor al costo; unidad desconocida
(usa UNIDAD); producto existente por código, o por nombre exacto si la fila no trae código → `UPDATE` con `updateExisting`, si no
`SKIP` de los datos (el stock se carga igual); producto dado de baja → se reactiva con `updateExisting`; categoría o proveedor
nuevo que se va a crear; vencimiento pasado (entra como vencido pendiente de descarte); el producto ya tiene stock en esa
sucursal (se suma un lote nuevo); fila repetida (mismo código, o mismo nombre sin código) con datos contradictorios → gana la
primera y la repetida solo carga su lote; datos de lote sin cantidad; cantidad con `importStock` apagado; el lote coincide
con un recall activo (entra en cuarentena).

La **sucursal** de la fila se reconoce por nombre, código o nombre corto, sin acentos ni mayúsculas: «Sucursal Norte»,
«sucursal norte», «Norte», «Suc. Norte» y «NOR» son la misma.

## Aplicación

- Asincrónica (`@Async`, disparada después del commit): recorre las filas `VALID`/`WARNING` en el orden del archivo, en
  **bloques de 200 filas, cada uno en su propia transacción**, y suma `processedRows` al cerrar cada bloque.
- Por fila: resuelve (o crea) categoría y proveedor comparando sin acentos ni mayúsculas —«Almacen» usa la categoría
  «Almacén» existente—, hace el upsert del producto (código de barras, o nombre exacto si no hay código) y, si
  `importStock` y cantidad > 0, llama a `StockService.receiveLot` con `MovementSource.IMPORT`.
- `receivedAt` = la columna «Fecha de ingreso» (a las 00:00 en la zona horaria de la app, `app.timezone`) o el instante de la aplicación, **más
  `rowNumber` milisegundos**: dos filas del mismo producto quedan en el orden del archivo para FIFO.
- El chequeo de recall lo hace `receiveLot`; `result.recallMatches` suma las coincidencias (esos lotes quedan `RECALLED`).
- Si `receiveLot` rechaza una fila (`ApiException`, p. ej. producto inactivo o sucursal ajena), esa fila queda `FAILED` con el
  motivo y el bloque sigue (`receiveLot` es `noRollbackFor = ApiException`). Un error inesperado deja la importación `FAILED`;
  los bloques ya confirmados quedan cargados.
- `DELETE` durante `APPLYING` corta después del bloque en curso → `CANCELLED` con el resultado parcial.

## Frontend

- `/app/imports` — **ImportsPage**: descarga de plantilla (xlsx/csv), exportación del catálogo (con stock por lote o solo
  productos, con aviso de que reimportar cantidades suma lotes), zona de arrastrar y soltar que sube y abre el asistente, e
  historial paginado (tabla en escritorio, tarjetas en celular) con estado y resultado.
- `/app/imports/:id` — **ImportWizardPage** en 5 pasos (`WizardSteps`) con barra de acciones fija:
  1. *Archivo*: arrastrar y soltar, progreso de subida y elección de hoja si el Excel tiene varias.
  2. *Columnas*: por cada campo un select de encabezados con la sugerencia y valores de muestra (una columna ya usada queda
     deshabilitada en los demás campos);
     opciones (cargar stock, actualizar existentes, crear categorías/proveedores, formato de fecha, sucursal por defecto).
  3. *Revisión*: contadores por estado como filtro, búsqueda, grilla paginada con **edición inline** (Enter guarda, Esc
     cancela; celdas con error/advertencia marcadas, motivo en tooltip y debajo del campo al editar), selección de filas,
     acciones masivas (omitir, restaurar, asignar sucursal) y descarga del CSV de errores.
  4. *Confirmar*: impacto (productos nuevos/actualizados, lotes, unidades, categorías y proveedores nuevos, recalls) y barra
     de progreso con polling cada segundo mientras está `APPLYING`.
  5. *Resultado*: contadores, links a Inventario y Vencimientos, revisión de solo lectura, descarga de las filas con
     observaciones y aviso de recalls con link a Seguridad alimentaria.
- Query keys: `['imports','list',params]`, `['imports','detail',id]`, `['imports','rows',id,params]`, `['imports','fields']`
  (por comercio, sin segmento de sucursal).

## Planillas de ejemplo

`docs/examples/importacion-ejemplo.xlsx` (hoja «Listado») y `docs/examples/importacion-ejemplo.csv` (Windows-1252, `;`):
18 filas de un almacén con encabezados «de la vida real» (`Cod. Barra`, `Descripcion`, `Rubro`, `Vto`…), precios con `$` y
miles, un producto con dos lotes y errores a propósito: fila 4 sin nombre, 5 precio «mil doscientos», 6 cantidad «12,5»,
7 fecha «31/02/2027», 8 sucursal «Sucursal Oeste», 13 cantidad negativa; advertencias por EAN inválido (fila 9), venta menor
al costo (10), vencimiento pasado (12), unidad «bidon» (14), fila 15 sin sucursal (usa la de por defecto) y fila 2 repetida.

## Decisiones

- **Sin migraciones propias**: `import_jobs` e `import_job_rows` vienen del núcleo (V2, `com.gondolia.domain.imports`).
  Las filas se leen y escriben con `JdbcTemplate` (`ImportRowStore`) para insertar/validar hasta 10.000 filas en lotes.
- `ImportJobDto` agrega a lo pedido `columnMapping` (mapeo guardado), `progressPct`, `errorMessage` y `preview` (se calcula
  en cada lectura mientras está `VALIDATED`, no solo en la respuesta del PUT). `PreviewDto` suma `rowsToSkip`,
  `rowsWithErrors` y `recallWarnings`; `FieldDto` suma `group`.
- La revalidación de una fila editada (PATCH/bulk) no recalcula duplicados del archivo; al aplicar, una fila repetida
  encuentra el producto que creó la anterior y solo carga su lote, así que el resultado es el mismo.
- Volver a guardar el mapeo revalida todo y descarta las omisiones manuales.
- Un producto dado de baja se reactiva solo si el usuario eligió actualizar los existentes (advertencia explícita).
