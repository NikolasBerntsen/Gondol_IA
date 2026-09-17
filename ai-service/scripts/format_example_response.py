"""Formatea una respuesta de /v1/analyze (leída de stdin) para docs/examples/analyze-response.json.

Uso:  curl -s -X POST http://localhost:18000/v1/analyze -H "Content-Type: application/json" \\
        --data-binary @docs/examples/analyze-request.json | python ai-service/scripts/format_example_response.py
Deja el JSON indentado pero con cada punto de pronóstico, anomalía y perfil semanal en una sola línea.
"""

import json
import sys
from pathlib import Path

OUTPUT = Path(__file__).resolve().parents[2] / "docs" / "examples" / "analyze-response.json"


def main() -> None:
    payload = json.loads(sys.stdin.buffer.read().decode("utf-8"))
    compact: dict[str, str] = {}

    def mark(value) -> str:
        marker = f"@@compact-{len(compact)}@@"
        compact[marker] = json.dumps(value, ensure_ascii=False)
        return marker

    for product in payload["products"]:
        product["weekdayProfile"] = mark(product["weekdayProfile"])
        product["forecast"] = [mark(point) for point in product["forecast"]]
        product["anomalies"] = [mark(anomaly) for anomaly in product["anomalies"]]
    text = json.dumps(payload, ensure_ascii=False, indent=2)
    for marker, value in compact.items():
        text = text.replace(f'"{marker}"', value)
    OUTPUT.write_text(text + "\n", encoding="utf-8")
    print(f"Escrito {OUTPUT} ({len(payload['products'])} productos, {len(payload['recommendations'])} recomendaciones)")


if __name__ == "__main__":
    main()
