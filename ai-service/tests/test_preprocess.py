import io

import pytest
from PIL import Image

from app.ocr.preprocess import InvalidImageError, load_image


def encoded(image: Image.Image, fmt: str, **options) -> bytes:
    buffer = io.BytesIO()
    image.save(buffer, format=fmt, **options)
    return buffer.getvalue()


def test_high_resolution_phone_photos_are_decoded_reduced():
    photo = encoded(Image.new("RGB", (8200, 6200), "white"), "JPEG", quality=60)  # 50 MP
    image = load_image(photo)
    assert image.shape[2] == 3
    assert 4000 <= max(image.shape[:2]) < 8200


def test_huge_lossless_images_are_rejected_with_a_clear_message():
    with pytest.raises(InvalidImageError, match="demasiado grande"):
        load_image(encoded(Image.new("L", (8000, 8000), 255), "PNG"))


def test_exif_orientation_is_applied():
    exif = Image.Exif()
    exif[0x0112] = 6  # rotada 90° a la derecha
    image = load_image(encoded(Image.new("RGB", (400, 200), "white"), "JPEG", exif=exif))
    assert image.shape[:2] == (400, 200)


def test_tiny_or_broken_files_are_invalid():
    with pytest.raises(InvalidImageError, match="demasiado chica"):
        load_image(encoded(Image.new("RGB", (4, 4), "white"), "PNG"))
    with pytest.raises(InvalidImageError, match="no es una imagen"):
        load_image(encoded(Image.new("RGB", (100, 100), "white"), "JPEG")[:100])
