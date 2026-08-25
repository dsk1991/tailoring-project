"""Server-only OpenAI image analysis for customer body measurement sessions."""

from __future__ import annotations

import base64
import json
import mimetypes

import frappe
import requests
from frappe import _
from frappe.utils import cint


OPENAI_RESPONSES_URL = "https://api.openai.com/v1/responses"


def analyze_session(session):
    settings = frappe.get_cached_doc("Tailoring AI Settings")
    if not settings.enabled:
        frappe.throw(_("AI Measurement is disabled in Tailoring AI Settings"))

    api_key = settings.get_password("openai_api_key")
    if not api_key:
        frappe.throw(_("OpenAI API Key is not configured"))

    definitions = frappe.get_all(
        "Measurement Definition",
        filters={"measurement_scope": "Body", "is_active": 1},
        fields=["measurement_code", "measurement_name", "measurement_kind", "value_basis"],
        order_by="display_order asc",
    )
    if not definitions:
        frappe.throw(_("Create active Body Measurement Definitions before running AI analysis"))

    photo_fields = (("front_photo", "front"), ("side_photo", "side"), ("back_photo", "back"))
    image_inputs = []
    for fieldname, label in photo_fields:
        file_url = session.get(fieldname)
        if not file_url:
            frappe.throw(_("{0} photo is required").format(label.title()))
        image_inputs.append({"type": "input_text", "text": f"{label.title()} photo:"})
        image_inputs.append(
            {
                "type": "input_image",
                "image_url": _file_as_data_url(file_url),
                "detail": "high",
            }
        )

    measurement_catalog = [
        {
            "code": row.measurement_code,
            "name": row.measurement_name,
            "kind": row.measurement_kind,
            "basis": row.value_basis,
        }
        for row in definitions
    ]
    unit = session.default_unit or "Inch"
    instructions = (
        "You are assisting a trained tailoring staff member. Inspect front, side, and back full-body photos "
        "captured at fixed footprints against a 2x2 inch calibration grid. First judge whether the full body, "
        "grid, pose, and camera angle are usable. Estimate only the requested body measurements. Do not invent "
        "a value when a body region is hidden; omit it and add a quality issue. These are estimates that require "
        "staff review, not medical measurements. Use the supplied actual height as the primary scale and return "
        f"all values in {unit}. Actual height: {session.body_height} {unit}. "
        f"Optional weight in kg: {session.body_weight or 'not supplied'}. Requested catalog: "
        f"{json.dumps(measurement_catalog, separators=(',', ':'))}."
    )
    if settings.measurement_prompt:
        instructions += f" Additional business instructions: {settings.measurement_prompt.strip()}"

    schema = {
        "type": "object",
        "additionalProperties": False,
        "properties": {
            "capture_quality": {"type": "string", "enum": ["pass", "review", "fail"]},
            "quality_issues": {"type": "array", "items": {"type": "string"}},
            "measurements": {
                "type": "array",
                "items": {
                    "type": "object",
                    "additionalProperties": False,
                    "properties": {
                        "code": {"type": "string"},
                        "value": {"type": "number"},
                        "confidence": {"type": "number", "minimum": 0, "maximum": 100},
                        "notes": {"type": "string"},
                    },
                    "required": ["code", "value", "confidence", "notes"],
                },
            },
        },
        "required": ["capture_quality", "quality_issues", "measurements"],
    }
    payload = {
        "model": settings.model or "gpt-5.4-mini",
        "store": bool(settings.store_openai_response),
        "max_output_tokens": 3000,
        "instructions": instructions,
        "input": [{"role": "user", "content": [{"type": "input_text", "text": "Analyze this capture."}, *image_inputs]}],
        "text": {"format": {"type": "json_schema", "name": "body_measurement_result", "strict": True, "schema": schema}},
    }

    try:
        response = requests.post(
            OPENAI_RESPONSES_URL,
            headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
            json=payload,
            timeout=cint(settings.request_timeout_seconds) or 90,
        )
        response.raise_for_status()
    except requests.RequestException as exc:
        detail = _safe_error_detail(getattr(exc, "response", None))
        frappe.log_error(f"OpenAI measurement request failed: {exc}\n{detail}", "Tailoring AI")
        frappe.throw(_("AI measurement request failed. Please retry or check Tailoring AI Settings."))

    response_json = response.json()
    output_text = _extract_output_text(response_json)
    try:
        result = json.loads(output_text)
    except (TypeError, json.JSONDecodeError) as exc:
        frappe.log_error(f"Invalid OpenAI structured output: {output_text}", "Tailoring AI")
        raise frappe.ValidationError(_("AI returned an invalid measurement response")) from exc

    result["response_id"] = response_json.get("id")
    result["request_id"] = response.headers.get("x-request-id")
    result["model"] = response_json.get("model") or settings.model
    return result


def _file_as_data_url(file_url):
    file_doc = frappe.get_doc("File", {"file_url": file_url})
    content = file_doc.get_content()
    mime_type = mimetypes.guess_type(file_url)[0] or "image/jpeg"
    return f"data:{mime_type};base64,{base64.b64encode(content).decode('ascii')}"


def _extract_output_text(response_json):
    for item in response_json.get("output") or []:
        for content in item.get("content") or []:
            if content.get("type") == "output_text" and content.get("text"):
                return content["text"]
    frappe.throw(_("AI response did not contain measurement output"))


def _safe_error_detail(response):
    if response is None:
        return "No HTTP response"
    try:
        payload = response.json()
        return json.dumps(payload)[:2000]
    except Exception:
        return (response.text or "")[:2000]
