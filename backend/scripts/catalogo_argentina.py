"""Catálogo de referencia de productos argentinos para autocompletar la carga de mercadería por código de barras.

El backend lo empaqueta en backend/src/main/resources/catalog/productos-argentina.csv y lo consulta antes que a Open
Food Facts (ReferenceCatalog): responde al instante y sin internet. Cada fila es un producto real con su código de
barras tal como figura en la base pública; nunca se inventa un código.

Datos: Open Food Facts (https://world.openfoodfacts.org) y, para unos pocos productos de limpieza y perfumería, sus
bases hermanas Open Products Facts y Open Beauty Facts. Licencia ODbL 1.0 para la base de datos
(https://opendatacommons.org/licenses/odbl/1-0/) y DbCL 1.0 para su contenido. El archivo conserva la atribución en
su cabecera, que escribe este script.

Cómo se armó y cómo ampliarlo (Python 3.10+, solo biblioteca estándar):

  1. candidatos: baja de Open Food Facts los productos argentinos (código 779…) más escaneados y los de las marcas de
     MARCAS, propone nombre, marca, contenido y categoría, y los deja en un CSV aparte, sin los que ya están en el
     catálogo:
       python3 backend/scripts/catalogo_argentina.py candidatos --salida /tmp/candidatos.csv
  2. Revisá los candidatos a mano: descartá los de nombre vacío, dudoso o en otro idioma, dejá el nombre prolijo
     ("Yerba mate Playadito 500 g", "Galletitas Chocolinas 150 g"), la marca, el contenido y una categoría de
     CATEGORIAS, y pegá las filas buenas en el catálogo.
  3. verificar: controla el formato (6 columnas y sin comillas, porque el backend corta cada línea en ";" tal cual),
     los dígitos verificadores, los duplicados y las categorías, y con --online confirma que cada código existe en su
     base (columna fuente) y deja un informe con los datos públicos al lado de los nuestros para revisar nombres y
     contenidos. --reescribir ordena las filas y rehace la cabecera:
       python3 backend/scripts/catalogo_argentina.py verificar --reescribir
       python3 backend/scripts/catalogo_argentina.py verificar --online --informe /tmp/verificacion.tsv
"""

from __future__ import annotations

import argparse
import csv
import io
import json
import re
import sys
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

CATALOGO = Path(__file__).resolve().parents[1] / "src" / "main" / "resources" / "catalog" / "productos-argentina.csv"
USER_AGENT = "GondolIA/1.0 (prototipo universitario)"
SEARCH_URL = "https://search.openfoodfacts.org/search"
PRODUCT_URLS = {
    "OFF": "https://world.openfoodfacts.org/api/v2/product/{code}.json",
    "OPF": "https://world.openproductsfacts.org/api/v2/product/{code}.json",
    "OBF": "https://world.openbeautyfacts.org/api/v2/product/{code}.json",
}
COLUMNAS = ["codigo", "nombre", "marca", "contenido", "categoria", "fuente"]

CABECERA = """\
# Catálogo de referencia de productos argentinos de GondolIA: autocompleta la carga de mercadería por código de
# barras sin depender de internet (ver docs/datos-demo.md). Lo genera y verifica backend/scripts/catalogo_argentina.py.
#
# Fuente: Open Food Facts (https://world.openfoodfacts.org), y Open Products Facts / Open Beauty Facts para limpieza y
# perfumería (columna "fuente"). Los datos se publican bajo la Open Database License (ODbL) 1.0
# (https://opendatacommons.org/licenses/odbl/1-0/) y su contenido bajo la Database Contents License (DbCL) 1.0.
# © colaboradores de Open Food Facts. Nombres, marcas y contenidos revisados y normalizados para GondolIA; los
# códigos de barras son los de la base pública, sin cambios.
#
# Formato: separado por ";", una fila por producto y sin comillas (ningún campo lleva ";" ni comillas). codigo =
# EAN-13/EAN-8 con dígito verificador válido.
"""

# Categorías del mundo demo (backend/src/main/java/com/gondolia/seed/DemoCatalog.java), en el orden en que se listan.
CATEGORIAS = [
    "Bebidas", "Bebidas alcohólicas", "Lácteos", "Fiambres y quesos", "Almacén", "Conservas", "Legumbres",
    "Galletitas y snacks", "Golosinas", "Panificados", "Cereales y harinas", "Sin TACC", "Endulzantes",
    "Bebidas vegetales", "Untables", "Frutos secos", "Semillas", "Especias", "Refrigerados", "Congelados",
    "Suplementos", "Infusiones", "Limpieza", "Higiene y perfumería", "Dermocosmética", "Botiquín", "Bebés",
]

# Etiquetas de categoría de Open Food Facts → categoría de GondolIA. Gana la primera que tenga el producto, así que
# las más específicas van antes (cerveza antes que bebida, queso untable antes que queso).
CATEGORIAS_OFF = [
    ("en:alcoholic-beverages", "Bebidas alcohólicas"), ("en:beers", "Bebidas alcohólicas"),
    ("en:wines", "Bebidas alcohólicas"), ("en:ciders", "Bebidas alcohólicas"),
    ("en:plant-based-milk-alternatives", "Bebidas vegetales"), ("en:soy-based-drinks", "Bebidas vegetales"),
    ("en:nut-based-drinks", "Bebidas vegetales"),
    ("en:cheese-spreads", "Lácteos"), ("en:yogurts", "Lácteos"), ("en:milks", "Lácteos"),
    ("en:dairy-desserts", "Lácteos"), ("en:butters", "Lácteos"), ("en:creams", "Lácteos"),
    ("en:cheeses", "Fiambres y quesos"), ("en:hams", "Fiambres y quesos"), ("en:sausages", "Fiambres y quesos"),
    ("en:salamis", "Fiambres y quesos"),
    ("en:peanut-butters", "Untables"),
    ("en:alfajores", "Golosinas"), ("en:chocolates", "Golosinas"), ("en:candies", "Golosinas"),
    ("en:chewing-gum", "Golosinas"), ("en:turron", "Golosinas"),
    ("en:cereal-bars", "Galletitas y snacks"), ("en:biscuits", "Galletitas y snacks"),
    ("en:crackers", "Galletitas y snacks"), ("en:crisps", "Galletitas y snacks"),
    ("en:salty-snacks", "Galletitas y snacks"),
    ("en:breakfast-cereals", "Cereales y harinas"), ("en:rolled-oats", "Cereales y harinas"),
    ("en:breads", "Panificados"), ("en:crispbreads", "Panificados"), ("en:cakes", "Panificados"),
    ("en:frozen-foods", "Congelados"), ("en:ice-creams", "Congelados"),
    ("en:tomato-purees", "Conservas"), ("en:canned-foods", "Conservas"), ("en:canned-fishes", "Conservas"),
    ("en:pulses", "Legumbres"),
    ("en:nuts", "Frutos secos"),
    ("en:sweeteners", "Endulzantes"), ("en:sugar-substitutes", "Endulzantes"), ("en:honeys", "Endulzantes"),
    ("en:spices", "Especias"),
    ("en:beverages", "Bebidas"),
]

MARCAS = """coca-cola pepsi manaos villavicencio villa-del-sur cepita baggio arcor bagley terrabusi havanna cachafaz
guaymallen lay-s doritos playadito taragui rosamonte cruz-de-malta amanda la-virginia cabrales la-serenisima sancor
ilolay tregar milkaut lucchetti matarazzo marolio gallo morixe ledesma natura knorr hellmann-s la-campagnola paty
quilmes granix quaker""".split()

# Nombres de marca tal como se escriben (OFF los trae en minúscula o sin tildes).
MARCAS_PROLIJAS = {
    "la serenisima": "La Serenísima", "taragui": "Taragüí", "sancor": "SanCor", "hellmann's": "Hellmann's",
    "hellmanns": "Hellmann's", "guaymallen": "Guaymallén", "aguila": "Águila", "cbse": "CBSé", "levite": "Levité",
    "lay's": "Lay's", "lays": "Lay's", "mani king": "Maní King", "tia maruca": "Tía Maruca", "trio": "Trío",
    "pipore": "Piporé", "nunez": "Núñez", "danica": "Dánica", "arlistan": "Arlistán",
}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="comando", required=True)
    cand = sub.add_parser("candidatos", help="Baja candidatos de Open Food Facts para revisar a mano")
    cand.add_argument("--salida", type=Path, required=True, help="CSV de candidatos a escribir")
    cand.add_argument("--paginas", type=int, default=20, help="Páginas de 100 productos más escaneados (20)")
    ver = sub.add_parser("verificar", help="Controla el catálogo (y con --online, contra la base pública)")
    ver.add_argument("--online", action="store_true", help="Confirma cada código en su base pública")
    ver.add_argument("--informe", type=Path, help="Con --online: TSV con nuestros datos al lado de los públicos")
    ver.add_argument("--reescribir", action="store_true", help="Ordena las filas y rehace la cabecera")
    args = parser.parse_args()
    if args.comando == "candidatos":
        candidatos(args.salida, args.paginas)
    else:
        sys.exit(verificar(args.online, args.informe, args.reescribir))


# --- candidatos -----------------------------------------------------------------------------------------------------

def candidatos(salida: Path, paginas: int) -> None:
    existentes = {fila["codigo"] for fila in leer_catalogo()}
    vistos: dict[str, dict] = {}
    consultas = [('code:779* AND countries_tags:"en:argentina"', pagina) for pagina in range(1, paginas + 1)]
    consultas += [(f'brands_tags:"{marca}" AND code:779*', 1) for marca in MARCAS]
    for consulta, pagina in consultas:
        for producto in buscar(consulta, pagina):
            vistos.setdefault(producto.get("code", ""), producto)
        time.sleep(1)
    filas = []
    for codigo, producto in vistos.items():
        if codigo in existentes or not gtin_valido(codigo):
            continue
        nombre = limpiar(producto.get("product_name_es") or producto.get("product_name"))
        marcas = producto.get("brands") or []
        if isinstance(marcas, str):
            marcas = marcas.split(",")
        marca = marca_prolija(marcas[0] if marcas else "")
        if not nombre or not marca:
            continue
        contenido = contenido_prolijo(producto.get("quantity"))
        filas.append({
            "codigo": codigo,
            "nombre": nombre_sugerido(nombre, marca, contenido),
            "marca": marca,
            "contenido": contenido,
            "categoria": categoria_sugerida(producto.get("categories_tags") or []),
            "fuente": "OFF",
            "nombre_off": nombre,
            "escaneos": producto.get("unique_scans_n") or 0,
        })
    filas.sort(key=lambda fila: -int(fila["escaneos"]))
    with salida.open("w", encoding="utf-8", newline="") as archivo:
        writer = csv.DictWriter(archivo, fieldnames=COLUMNAS + ["nombre_off", "escaneos"], delimiter=";")
        writer.writeheader()
        writer.writerows(filas)
    print(f"{len(filas)} candidatos en {salida} (revisalos antes de pasarlos al catálogo)")


def buscar(consulta: str, pagina: int) -> list[dict]:
    params = urllib.parse.urlencode({
        "q": consulta, "page": pagina, "page_size": 100, "sort_by": "-unique_scans_n",
        "fields": "code,product_name,product_name_es,brands,quantity,categories_tags,unique_scans_n",
    })
    try:
        return pedir_json(f"{SEARCH_URL}?{params}").get("hits", [])
    except (urllib.error.URLError, TimeoutError) as error:
        print(f"  no respondió la búsqueda {consulta!r} (página {pagina}): {error}", file=sys.stderr)
        return []


def limpiar(texto: str | None) -> str:
    return " ".join((texto or "").split())


def marca_prolija(marca: str) -> str:
    marca = limpiar(marca)
    if not marca:
        return ""
    clave = sin_tildes(marca).lower()
    if clave in MARCAS_PROLIJAS:
        return MARCAS_PROLIJAS[clave]
    return marca.title() if marca.islower() else marca


def contenido_prolijo(cantidad: str | None) -> str:
    """"500g" → "500 g", "2.25 l" → "2,25 L", "1000 cm3" → "1 L". Si no se entiende, lo deja vacío."""
    match = re.match(r"^\s*(\d+(?:[.,]\d+)?)\s*(kg|g|gr|grs|gramos|ml|cc|cm3|cm³|l|lt|lts|litros?)\b",
                     (cantidad or "").lower())
    if not match:
        return ""
    valor = float(match.group(1).replace(",", "."))
    unidad = {"gr": "g", "grs": "g", "gramos": "g", "cc": "ml", "cm3": "ml", "cm³": "ml", "lt": "L", "lts": "L",
              "l": "L", "litro": "L", "litros": "L"}.get(match.group(2), match.group(2))
    if unidad == "ml" and valor >= 1000:
        valor, unidad = valor / 1000, "L"
    if unidad == "g" and valor >= 1000:
        valor, unidad = valor / 1000, "kg"
    numero = f"{valor:.2f}".rstrip("0").rstrip(".").replace(".", ",")
    return f"{numero} {unidad}"


def nombre_sugerido(nombre: str, marca: str, contenido: str) -> str:
    sugerido = nombre[0].upper() + nombre[1:]
    if sin_tildes(marca).lower() not in sin_tildes(sugerido).lower():
        sugerido += f" {marca}"
    if contenido and contenido.split()[0] not in sugerido:
        sugerido += f" {contenido}"
    return sugerido


def categoria_sugerida(etiquetas: list[str]) -> str:
    for etiqueta, categoria in CATEGORIAS_OFF:
        if etiqueta in etiquetas:
            return categoria
    return "Almacén"


def sin_tildes(texto: str) -> str:
    return "".join(c for c in unicodedata.normalize("NFD", texto) if unicodedata.category(c) != "Mn")


# --- verificar ------------------------------------------------------------------------------------------------------

def verificar(online: bool, informe: Path | None, reescribir: bool) -> int:
    filas = leer_catalogo()
    errores = []
    vistos: set[str] = set()
    for numero, fila in enumerate(filas, start=1):
        codigo = fila["codigo"]
        if not gtin_valido(codigo):
            errores.append(f"fila {numero}: {codigo} no es un EAN-13/EAN-8/UPC-A con dígito verificador válido")
        if codigo in vistos:
            errores.append(f"fila {numero}: {codigo} está repetido")
        vistos.add(codigo)
        if not fila["nombre"] or not fila["marca"]:
            errores.append(f"fila {numero}: {codigo} no tiene nombre o marca")
        if fila["categoria"] not in CATEGORIAS:
            errores.append(f"fila {numero}: {codigo} tiene una categoría que no es del mundo demo: {fila['categoria']}")
        if fila["fuente"] not in PRODUCT_URLS:
            errores.append(f"fila {numero}: {codigo} tiene una fuente desconocida: {fila['fuente']}")
        con_comillas = [columna for columna in COLUMNAS if '"' in fila[columna]]
        if con_comillas:
            errores.append(f"fila {numero}: {codigo} tiene comillas en {', '.join(con_comillas)} (el backend no las "
                           f"interpreta y las mostraría tal cual)")
    if online:
        errores += verificar_online(filas, informe)
    for error in errores:
        print(error, file=sys.stderr)
    if reescribir and not errores:
        escribir_catalogo(filas)
    print(f"{len(filas)} productos en {CATALOGO.name}: {'OK' if not errores else f'{len(errores)} problemas'}")
    return 1 if errores else 0


def verificar_online(filas: list[dict], informe: Path | None) -> list[str]:
    errores = []
    lineas = ["codigo\tnombre\tcontenido\tnombre_publico\tmarcas_publicas\tcontenido_publico"]
    for fila in filas:
        url = PRODUCT_URLS[fila["fuente"]].format(code=fila["codigo"])
        url += "?fields=code,product_name,product_name_es,brands,quantity"
        try:
            respuesta = pedir_json(url)
        except urllib.error.HTTPError as error:
            respuesta = {"status": 0} if error.code == 404 else None
        except (urllib.error.URLError, TimeoutError):
            respuesta = None
        if respuesta is None:
            errores.append(f"{fila['codigo']}: no se pudo consultar {fila['fuente']}")
        elif respuesta.get("status") != 1:
            errores.append(f"{fila['codigo']}: no existe en {fila['fuente']}")
        else:
            producto = respuesta.get("product", {})
            lineas.append("\t".join([
                fila["codigo"], fila["nombre"], fila["contenido"],
                limpiar(producto.get("product_name_es") or producto.get("product_name")),
                limpiar(producto.get("brands")), limpiar(producto.get("quantity")),
            ]))
        time.sleep(0.7)  # La API de productos admite 100 lecturas por minuto.
    if informe:
        informe.write_text("\n".join(lineas) + "\n", encoding="utf-8")
        print(f"Informe para revisar en {informe}")
    return errores


# --- catálogo -------------------------------------------------------------------------------------------------------

def leer_catalogo() -> list[dict]:
    """Filas del catálogo leídas igual que ReferenceCatalog del backend: cada línea cortada en ";", sin interpretar
    comillas. Una comilla queda en el campo (verificar la rechaza) y un ";" dentro de un campo suma una columna."""
    if not CATALOGO.exists():
        return []
    datos = [linea.split(";") for linea in CATALOGO.read_text(encoding="utf-8").splitlines()
             if linea.strip() and not linea.startswith("#")]
    if not datos or datos[0] != COLUMNAS:
        raise SystemExit(f"Columnas inesperadas en {CATALOGO}: {datos[0] if datos else []} (se esperaba {COLUMNAS})")
    malas = [";".join(campos) for campos in datos[1:] if len(campos) != len(COLUMNAS)]
    if malas:
        raise SystemExit(f"Filas de {CATALOGO} sin {len(COLUMNAS)} columnas (¿un \";\" dentro de un campo?):\n"
                         + "\n".join(malas))
    return [{clave: valor.strip() for clave, valor in zip(COLUMNAS, campos)} for campos in datos[1:]]


def escribir_catalogo(filas: list[dict]) -> None:
    orden = {categoria: indice for indice, categoria in enumerate(CATEGORIAS)}
    filas = sorted(filas, key=lambda fila: (orden.get(fila["categoria"], 99), sin_tildes(fila["nombre"]).lower()))
    salida = io.StringIO()
    # Sin comillas, como lo lee el backend: un campo con ";" o comillas hace fallar la escritura en lugar de colarse.
    writer = csv.DictWriter(salida, fieldnames=COLUMNAS, delimiter=";", lineterminator="\n", quoting=csv.QUOTE_NONE)
    writer.writeheader()
    writer.writerows(filas)
    CATALOGO.parent.mkdir(parents=True, exist_ok=True)
    CATALOGO.write_text(CABECERA + salida.getvalue(), encoding="utf-8")


def gtin_valido(codigo: str) -> bool:
    """EAN-13, UPC-A (12) o EAN-8 con dígito verificador correcto (igual que Barcodes.isValidGtin del backend)."""
    if not re.fullmatch(r"\d{8}|\d{12}|\d{13}", codigo or ""):
        return False
    digitos = [int(c) for c in codigo]
    suma = sum(d * (3 if (len(digitos) - 1 - i) % 2 == 1 else 1) for i, d in enumerate(digitos[:-1]))
    return (10 - suma % 10) % 10 == digitos[-1]


def pedir_json(url: str) -> dict:
    pedido = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(pedido, timeout=30) as respuesta:
        return json.load(respuesta)


if __name__ == "__main__":
    main()
