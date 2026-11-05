#!/usr/bin/env python3
"""Genera public/og-gondolia.png: la imagen que muestran WhatsApp, Slack, Telegram,
LinkedIn, X y Discord cuando alguien comparte el enlace de GondolIA.

    python frontend/scripts/generar-og-image.py

1200x630 (la proporción 1.91:1 que piden las redes), dibujada a 2x y reducida con
LANCZOS para que los bordes queden limpios. El contenido vive dentro del cuadrado
central porque varias apps recortan la vista previa chica a 1:1.

El isotipo es el mismo de src/components/layout/Logo.tsx y public/favicon.svg
(etiqueta de góndola con el agujero y las líneas del precio), con los colores de
src/index.css. El texto usa una tipografía del sistema: Bricolage Grotesque solo
viene en woff2, que Pillow no sabe leer, y para una miniatura la diferencia no se
nota. Si cambia el logo o la marca, volvé a correr este script y commiteá el PNG.

Requiere Pillow (pip install Pillow).
"""
from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ESCALA = 2  # se dibuja al doble y se reduce: antialiasing gratis
ANCHO, ALTO = 1200, 630
SALIDA = Path(__file__).resolve().parent.parent / "public" / "og-gondolia.png"

# Colores de src/index.css (tema oscuro del riel).
RAIL_ARRIBA = (17, 67, 44)      # #11432C
RAIL_ABAJO = (10, 45, 29)       # #0A2D1D
ACENTO = (244, 197, 66)         # #F4C542  --accent
ACENTO_TINTA = (35, 26, 2)      # #231A02  --accent-foreground
RAIL_SUAVE = (143, 179, 158)    # #8FB39E  --rail-muted
BLANCO = (255, 255, 255)

# Candidatas por orden de preferencia: Windows, macOS, Linux.
FUENTES_TITULO = ["segoeuib.ttf", "seguisb.ttf", "arialbd.ttf", "HelveticaNeue.ttc", "DejaVuSans-Bold.ttf"]
FUENTES_TEXTO = ["segoeui.ttf", "arial.ttf", "Helvetica.ttc", "DejaVuSans.ttf"]
CARPETAS_FUENTES = ["C:/Windows/Fonts", "/System/Library/Fonts", "/Library/Fonts",
                    "/usr/share/fonts/truetype/dejavu", "/usr/share/fonts"]


def cargar_fuente(candidatas: list[str], tamanio: int) -> ImageFont.FreeTypeFont:
    for nombre in candidatas:
        for carpeta in CARPETAS_FUENTES:
            ruta = Path(carpeta) / nombre
            if ruta.exists():
                return ImageFont.truetype(str(ruta), tamanio)
        try:  # por si está en el path de fuentes del sistema
            return ImageFont.truetype(nombre, tamanio)
        except OSError:
            continue
    print(f"! No encontré ninguna de {candidatas}: uso la tipografía por defecto.", file=sys.stderr)
    return ImageFont.load_default(tamanio)


def fondo(ancho: int, alto: int) -> Image.Image:
    """Degradado vertical del verde del riel + un resplandor cálido detrás del isotipo."""
    lienzo = Image.new("RGB", (ancho, alto), RAIL_ABAJO)
    pincel = ImageDraw.Draw(lienzo)
    for y in range(alto):
        t = y / max(alto - 1, 1)
        pincel.line(
            [(0, y), (ancho, y)],
            fill=tuple(round(a + (b - a) * t) for a, b in zip(RAIL_ARRIBA, RAIL_ABAJO)),
        )

    glow_r = int(alto * 0.42)
    mascara = Image.new("L", (ancho, alto), 0)
    ImageDraw.Draw(mascara).ellipse(
        [ancho // 2 - glow_r, int(alto * 0.30) - glow_r, ancho // 2 + glow_r, int(alto * 0.30) + glow_r],
        fill=46,
    )
    mascara = mascara.filter(ImageFilter.GaussianBlur(glow_r // 2))
    return Image.composite(Image.new("RGB", (ancho, alto), ACENTO), lienzo, mascara)


def isotipo(pincel: ImageDraw.ImageDraw, x: float, y: float, lado: float) -> None:
    """La etiqueta de góndola del logo. Coordenadas del viewBox 32 de Logo.tsx."""
    u = lado / 32  # un "punto" del viewBox en píxeles

    pincel.rounded_rectangle([x + 4 * u, y + 4 * u, x + 28 * u, y + 28 * u], radius=3.5 * u, fill=ACENTO)
    r = 2.4 * u  # el agujero por donde cuelga la etiqueta: deja ver el fondo
    pincel.ellipse([x + 9.5 * u - r, y + 16 * u - r, x + 9.5 * u + r, y + 16 * u + r], fill=RAIL_ABAJO)
    grosor = 2.2 * u  # las tres líneas del precio, con las puntas redondeadas
    for y_linea, x_fin in ((11.5, 24), (16.0, 22), (20.5, 24)):
        pincel.rounded_rectangle(
            [x + 15 * u, y + y_linea * u - grosor / 2, x + x_fin * u, y + y_linea * u + grosor / 2],
            radius=grosor / 2,
            fill=ACENTO_TINTA,
        )


def centrar(pincel: ImageDraw.ImageDraw, partes, y: float, ancho: int) -> None:
    """Dibuja [(texto, fuente, color), …] como una sola línea centrada."""
    anchos = [pincel.textlength(t, font=f) for t, f, _ in partes]
    x = (ancho - sum(anchos)) / 2
    for (texto, fuente, color), w in zip(partes, anchos):
        pincel.text((x, y), texto, font=fuente, fill=color, anchor="lt")
        x += w


def main() -> None:
    ancho, alto = ANCHO * ESCALA, ALTO * ESCALA
    lienzo = fondo(ancho, alto)
    pincel = ImageDraw.Draw(lienzo)

    lado = 176 * ESCALA
    isotipo(pincel, (ancho - lado) / 2, 92 * ESCALA, lado)

    titulo = cargar_fuente(FUENTES_TITULO, 104 * ESCALA)
    centrar(pincel, [("Gondol", titulo, BLANCO), ("IA", titulo, ACENTO)], 300 * ESCALA, ancho)

    eslogan = cargar_fuente(FUENTES_TEXTO, 36 * ESCALA)
    centrar(pincel, [("Tu negocio siempre a tiempo", eslogan, RAIL_SUAVE)], 440 * ESCALA, ancho)

    # Regla corta de acento, para que la tarjeta no termine en aire.
    pincel.rounded_rectangle(
        [ancho / 2 - 46 * ESCALA, 516 * ESCALA, ancho / 2 + 46 * ESCALA, 522 * ESCALA],
        radius=3 * ESCALA,
        fill=ACENTO,
    )

    SALIDA.parent.mkdir(parents=True, exist_ok=True)
    lienzo.resize((ANCHO, ALTO), Image.LANCZOS).save(SALIDA, "PNG", optimize=True)
    print(f"OK: {SALIDA}  ({SALIDA.stat().st_size // 1024} KB, {ANCHO}x{ALTO})")


if __name__ == "__main__":
    main()
