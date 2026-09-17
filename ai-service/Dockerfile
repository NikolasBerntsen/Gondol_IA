# GondolIA - servicio de IA (FastAPI + análisis de series + OCR con Tesseract)
FROM python:3.12-slim

RUN apt-get update && apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-spa tesseract-ocr-eng && rm -rf /var/lib/apt/lists/*

COPY requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir -r /tmp/requirements.txt

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    TZ=America/Argentina/Buenos_Aires \
    OMP_NUM_THREADS=1 \
    OPENBLAS_NUM_THREADS=1 \
    MKL_NUM_THREADS=1 \
    OMP_THREAD_LIMIT=1 \
    LOG_LEVEL=INFO

RUN groupadd --system --gid 10001 gondolia \
    && useradd --system --uid 10001 --gid gondolia --home-dir /app --shell /usr/sbin/nologin gondolia

WORKDIR /app
COPY --chown=gondolia:gondolia pytest.ini ./
COPY --chown=gondolia:gondolia app ./app
COPY --chown=gondolia:gondolia tests ./tests

USER gondolia
EXPOSE 8000

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "2"]
