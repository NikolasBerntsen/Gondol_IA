"""API HTTP del servicio de IA de GondolIA (solo red interna; la consume el backend)."""

import logging
import time
import uuid
from datetime import date, datetime
from typing import Annotated
from zoneinfo import ZoneInfo

from fastapi import FastAPI, File, Request, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from . import MODEL_VERSION, SERVICE_VERSION
from .analysis.formatting import fmt_num
from .analysis.pipeline import analyze
from .barcode import decode_barcodes
from .logging_config import setup_logging
from .ocr.engine import OcrUnavailableError, tesseract_available
from .ocr.preprocess import InvalidImageError, load_image
from .ocr.reader import read_label
from .schemas import (
    AnalyzeRequest,
    AnalyzeResponse,
    BarcodeOut,
    BarcodeResponse,
    ErrorResponse,
    FieldError,
    HealthResponse,
    OcrResponse,
)

setup_logging()
log = logging.getLogger("gondolia.ai")

BUSINESS_TIMEZONE = ZoneInfo("America/Argentina/Buenos_Aires")
MAX_UPLOAD_BYTES = 15 * 1024 * 1024

app = FastAPI(
    title="GondolIA - Servicio de IA",
    version=SERVICE_VERSION,
    description="Análisis de ventas y stock por sucursal, OCR de etiquetas y lectura de códigos de barras.",
)


class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message


def _error(status: int, code: str, message: str, field_errors: list[FieldError] | None = None) -> JSONResponse:
    body = ErrorResponse(code=code, message=message, field_errors=field_errors)
    return JSONResponse(status_code=status, content=body.model_dump(mode="json", exclude_none=True))


@app.middleware("http")
async def request_logging(request: Request, call_next):
    request_id = request.headers.get("x-request-id") or uuid.uuid4().hex[:16]
    started = time.perf_counter()
    status = 500
    try:
        response = await call_next(request)
        status = response.status_code
        response.headers["X-Request-Id"] = request_id
        return response
    finally:
        level = logging.DEBUG if request.url.path == "/health" and status < 400 else logging.INFO
        log.log(
            level,
            "Request atendido",
            extra={
                "requestId": request_id,
                "method": request.method,
                "path": request.url.path,
                "status": status,
                "durationMs": int((time.perf_counter() - started) * 1000),
            },
        )


@app.exception_handler(ApiError)
async def api_error_handler(_: Request, exc: ApiError) -> JSONResponse:
    return _error(exc.status, exc.code, exc.message)


VALIDATION_MESSAGES = {
    "missing": "es obligatorio",
    "int_type": "debe ser un número entero",
    "int_parsing": "debe ser un número entero",
    "int_from_float": "debe ser un número entero",
    "float_type": "debe ser un número",
    "float_parsing": "debe ser un número",
    "finite_number": "debe ser un número finito",
    "bool_type": "debe ser verdadero o falso",
    "bool_parsing": "debe ser verdadero o falso",
    "string_type": "debe ser un texto",
    "list_type": "debe ser una lista",
    "dict_type": "debe ser un objeto",
    "model_type": "debe ser un objeto",
    "model_attributes_type": "debe ser un objeto",
    "date_type": "debe ser una fecha AAAA-MM-DD",
    "date_parsing": "debe ser una fecha AAAA-MM-DD",
    "date_from_datetime_parsing": "debe ser una fecha AAAA-MM-DD",
    "datetime_type": "debe ser una fecha y hora ISO-8601",
    "datetime_parsing": "debe ser una fecha y hora ISO-8601",
    "datetime_from_date_parsing": "debe ser una fecha y hora ISO-8601",
    "literal_error": "debe ser uno de: {expected}",
    "greater_than": "debe ser mayor a {gt}",
    "greater_than_equal": "debe ser mayor o igual a {ge}",
    "less_than": "debe ser menor a {lt}",
    "less_than_equal": "debe ser menor o igual a {le}",
    "too_long": "admite como máximo {max_length} elementos",
    "json_invalid": "el JSON es inválido",
    "json_type": "el cuerpo debe ser JSON",
}


def _validation_message(error: dict) -> str:
    kind = error.get("type", "")
    if kind == "value_error":
        return str(error.get("msg", "")).removeprefix("Value error, ")
    template = VALIDATION_MESSAGES.get(kind)
    if template is None:
        return "valor inválido"
    context = {
        key: fmt_num(value, 3) if isinstance(value, (int, float)) else str(value).replace("'", "").replace(" or ", ", ")
        for key, value in (error.get("ctx") or {}).items()
    }
    try:
        return template.format(**context)
    except (KeyError, IndexError):
        return template.split(":")[0]


@app.exception_handler(RequestValidationError)
async def validation_error_handler(_: Request, exc: RequestValidationError) -> JSONResponse:
    field_errors = []
    for error in exc.errors():
        location = [str(part) for part in error.get("loc", ()) if part not in ("body", "query", "path")]
        if str(error.get("type", "")).startswith("json"):
            location = []  # la posición del error de sintaxis no es un campo
        field_errors.append(FieldError(field=".".join(location) or "body", message=_validation_message(error)))
    return _error(422, "VALIDATION_ERROR", "Revisá los datos enviados al servicio de IA", field_errors)


@app.exception_handler(StarletteHTTPException)
async def http_error_handler(_: Request, exc: StarletteHTTPException) -> JSONResponse:
    messages = {404: ("NOT_FOUND", "El recurso no existe"), 405: ("METHOD_NOT_ALLOWED", "Método no permitido")}
    code, message = messages.get(exc.status_code, ("HTTP_ERROR", str(exc.detail)))
    return _error(exc.status_code, code, message)


@app.exception_handler(Exception)
async def unexpected_error_handler(request: Request, exc: Exception) -> JSONResponse:
    log.exception("Error inesperado", extra={"path": request.url.path})
    return _error(500, "INTERNAL_ERROR", "Ocurrió un error inesperado en el servicio de IA")


def _read_upload(file: UploadFile) -> bytes:
    data = file.file.read(MAX_UPLOAD_BYTES + 1)
    if len(data) > MAX_UPLOAD_BYTES:
        raise ApiError(413, "FILE_TOO_LARGE", "La imagen supera el tamaño máximo de 15 MB")
    if not data:
        raise ApiError(400, "INVALID_FILE", "El archivo está vacío")
    return data


def _business_today() -> date:
    return datetime.now(BUSINESS_TIMEZONE).date()


@app.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    return HealthResponse(status="ok", version=SERVICE_VERSION, model_version=MODEL_VERSION, tesseract=tesseract_available())


@app.post("/v1/analyze", response_model=AnalyzeResponse)
def analyze_branch(request: AnalyzeRequest) -> AnalyzeResponse:
    return analyze(request)


@app.post("/v1/ocr", response_model=OcrResponse)
def ocr_label(file: Annotated[UploadFile, File()]) -> OcrResponse:
    data = _read_upload(file)
    try:
        return read_label(data, _business_today())
    except InvalidImageError as exc:
        raise ApiError(400, "INVALID_FILE", str(exc)) from exc
    except OcrUnavailableError as exc:
        raise ApiError(503, "OCR_UNAVAILABLE", "El lector de etiquetas (OCR) no está disponible") from exc


@app.post("/v1/barcode", response_model=BarcodeResponse)
def read_barcodes(file: Annotated[UploadFile, File()]) -> BarcodeResponse:
    data = _read_upload(file)
    try:
        image = load_image(data)
    except InvalidImageError as exc:
        raise ApiError(400, "INVALID_FILE", str(exc)) from exc
    return BarcodeResponse(barcodes=[BarcodeOut(value=b.value, format=b.format) for b in decode_barcodes(image)])
