"""Decodificación de imágenes y variantes de preprocesado con OpenCV para OCR y códigos de barras."""

import io
import math

import cv2
import numpy as np
from PIL import Image, ImageOps, UnidentifiedImageError

MAX_PIXELS = 60_000_000
"""Tope para imágenes que se decodifican a tamaño completo (PNG, WEBP...)."""
MAX_JPEG_PIXELS = 250_000_000
"""Los JPEG de cámaras de 50-200 MP se decodifican directamente reducidos (ver `JPEG_DECODE_SIDE`)."""
JPEG_DECODE_SIDE = 4000
OCR_MAX_SIDE = 2400
OCR_MIN_SIDE = 1600
PROBE_SIDE = 1200
ROTATIONS = (0, 90, 180, 270)


# Pillow avisa desde MAX_IMAGE_PIXELS y rechaza desde el doble: se alinea con el tope de JPEG de este módulo.
Image.MAX_IMAGE_PIXELS = MAX_JPEG_PIXELS // 2


class InvalidImageError(ValueError):
    """El archivo recibido no es una imagen que se pueda procesar."""


def load_image(data: bytes) -> np.ndarray:
    """Decodifica PNG/JPEG/WEBP/GIF/BMP a BGR, respetando la orientación EXIF de las fotos del celular."""
    if not data:
        raise InvalidImageError("El archivo está vacío")
    try:
        with Image.open(io.BytesIO(data)) as source:
            pixels = source.width * source.height
            if source.format == "JPEG" and pixels <= MAX_JPEG_PIXELS:
                # Reduce en el propio decodificador (escala 1/2, 1/4 u 1/8, sin bajar de 4000 px de lado mayor):
                # rápido y sin ocupar cientos de MB con fotos de 50-200 MP.
                ratio = min(1.0, JPEG_DECODE_SIDE / max(source.width, source.height))
                source.draft("RGB", (math.ceil(source.width * ratio), math.ceil(source.height * ratio)))
            elif pixels > MAX_PIXELS:
                raise InvalidImageError("La imagen es demasiado grande: usá una foto de menor resolución")
            image = ImageOps.exif_transpose(source)
            if image.mode in ("RGBA", "LA") or (image.mode == "P" and "transparency" in image.info):
                rgba = image.convert("RGBA")
                background = Image.new("RGBA", rgba.size, (255, 255, 255, 255))
                background.alpha_composite(rgba)
                image = background
            rgb = np.asarray(image.convert("RGB"))
    except InvalidImageError:
        raise
    except Image.DecompressionBombError as exc:
        raise InvalidImageError("La imagen es demasiado grande: usá una foto de menor resolución") from exc
    except (UnidentifiedImageError, OSError, ValueError, SyntaxError) as exc:
        raise InvalidImageError("El archivo no es una imagen válida") from exc
    if rgb.ndim != 3 or rgb.shape[0] < 8 or rgb.shape[1] < 8:
        raise InvalidImageError("La imagen es demasiado chica")
    return cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR)


def to_gray(image: np.ndarray) -> np.ndarray:
    return image if image.ndim == 2 else cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)


def resize_long_side(image: np.ndarray, target: int) -> np.ndarray:
    height, width = image.shape[:2]
    scale = target / max(height, width)
    if abs(scale - 1.0) < 0.05:
        return image
    interpolation = cv2.INTER_CUBIC if scale > 1 else cv2.INTER_AREA
    return cv2.resize(image, None, fx=scale, fy=scale, interpolation=interpolation)


def scale_for_ocr(gray: np.ndarray) -> np.ndarray:
    """Agranda las imágenes chicas (Tesseract lee mejor letras de 25-40 px) y achica las enormes."""
    long_side = max(gray.shape[:2])
    if long_side < OCR_MIN_SIDE:
        return resize_long_side(gray, min(OCR_MIN_SIDE, long_side * 3))
    if long_side > OCR_MAX_SIDE:
        return resize_long_side(gray, OCR_MAX_SIDE)
    return gray


def rotate(image: np.ndarray, degrees: int) -> np.ndarray:
    if degrees % 360 == 0:
        return image
    codes = {90: cv2.ROTATE_90_CLOCKWISE, 180: cv2.ROTATE_180, 270: cv2.ROTATE_90_COUNTERCLOCKWISE}
    return cv2.rotate(image, codes[degrees % 360])


def ensure_dark_text(gray: np.ndarray) -> np.ndarray:
    """Tesseract espera texto oscuro sobre fondo claro: invierte etiquetas con fondo oscuro."""
    return cv2.bitwise_not(gray) if float(np.median(gray)) < 110 else gray


def clahe(gray: np.ndarray) -> np.ndarray:
    return cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8)).apply(gray)


def ocr_variants(gray: np.ndarray) -> dict[str, np.ndarray]:
    """Variantes para Tesseract: gris, contraste local (CLAHE), umbral adaptativo y Otsu."""
    base = ensure_dark_text(gray)
    enhanced = clahe(base)
    adaptive = cv2.adaptiveThreshold(cv2.medianBlur(enhanced, 3), 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY, 31, 15)
    _, otsu = cv2.threshold(cv2.GaussianBlur(base, (5, 5), 0), 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    return {"gray": base, "clahe": enhanced, "adaptive": adaptive, "otsu": ensure_dark_text(otsu)}


def barcode_variants(gray: np.ndarray):
    """Variantes para zxing-cpp, de la más barata a la más agresiva (se generan a demanda)."""
    yield "gray", gray
    yield "clahe", clahe(gray)
    long_side = max(gray.shape[:2])
    if long_side < 1000:
        yield "upscaled", resize_long_side(gray, min(2000, long_side * 2))
    if long_side > 2500:
        yield "downscaled", resize_long_side(gray, 1600)
    blurred = cv2.GaussianBlur(gray, (0, 0), 2)
    yield "sharpened", cv2.addWeighted(gray, 1.6, blurred, -0.6, 0)
    _, otsu = cv2.threshold(cv2.GaussianBlur(gray, (3, 3), 0), 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    yield "otsu", otsu
    yield "adaptive", cv2.adaptiveThreshold(gray, 255, cv2.ADAPTIVE_THRESH_GAUSSIAN_C, cv2.THRESH_BINARY, 41, 10)
    yield "inverted", cv2.bitwise_not(gray)
    height, width = gray.shape[:2]
    for angle in (45, -45):
        matrix = cv2.getRotationMatrix2D((width / 2, height / 2), angle, 1.0)
        cos, sin = abs(matrix[0, 0]), abs(matrix[0, 1])
        new_w, new_h = int(height * sin + width * cos), int(height * cos + width * sin)
        matrix[0, 2] += new_w / 2 - width / 2
        matrix[1, 2] += new_h / 2 - height / 2
        yield f"rotated{angle}", cv2.warpAffine(gray, matrix, (new_w, new_h), borderValue=255)
