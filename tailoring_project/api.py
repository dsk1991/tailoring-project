"""Authenticated ERPNext API used by the Tailoring Staff Android app."""

from __future__ import annotations

import base64
import binascii
import json
import re

import frappe
from frappe import _
from frappe.utils import cint, flt, now_datetime
from frappe.utils.file_manager import save_file

from tailoring_project.tailoring_project.openai_measurements import analyze_session


PHOTO_FIELDS = {"front": "front_photo", "side": "side_photo", "back": "back_photo"}
MAX_IMAGE_BYTES = 10 * 1024 * 1024


def _require_authenticated():
    if frappe.session.user == "Guest":
        frappe.throw(_("Authentication required"), frappe.AuthenticationError)


def _get_session(name, permission_type="read"):
    doc = frappe.get_doc("Customer Body Measurement", name)
    if not doc.has_permission(permission_type):
        frappe.throw(_("Not permitted"), frappe.PermissionError)
    return doc


@frappe.whitelist()
def ping():
    _require_authenticated()
    return {"ok": True, "user": frappe.session.user}


@frappe.whitelist()
def search_customers(query="", start=0, page_length=30):
    _require_authenticated()
    query = (query or "").strip()
    page_length = min(max(cint(page_length), 1), 50)
    filters = None
    or_filters = None
    if query:
        like = f"%{query}%"
        or_filters = {"name": ["like", like], "customer_name": ["like", like], "mobile_no": ["like", like]}
    return frappe.get_list(
        "Customer",
        filters=filters,
        or_filters=or_filters,
        fields=["name", "customer_name", "mobile_no", "territory"],
        order_by="modified desc",
        start=max(cint(start), 0),
        page_length=page_length,
    )


@frappe.whitelist()
def get_measurement_definitions():
    _require_authenticated()
    return frappe.get_list(
        "Measurement Definition",
        filters={"measurement_scope": "Body", "is_active": 1},
        fields=["name", "measurement_code", "measurement_name", "body_section", "measurement_kind", "value_basis", "default_unit", "display_order"],
        order_by="display_order asc",
        page_length=200,
    )


@frappe.whitelist()
def create_measurement_session(customer, body_height, unit="Inch", body_weight=None, branch=None, consent_confirmed=0):
    _require_authenticated()
    if not frappe.db.exists("Customer", customer):
        frappe.throw(_("Customer does not exist"))
    height = flt(body_height)
    if height <= 0:
        frappe.throw(_("Actual height must be greater than zero"))
    if unit not in ("Inch", "CM"):
        frappe.throw(_("Unit must be Inch or CM"))
    if not cint(consent_confirmed):
        frappe.throw(_("Customer photo consent must be confirmed"))

    previous = frappe.get_list(
        "Customer Body Measurement",
        filters={"customer": customer},
        fields=["name", "measurement_version"],
        order_by="measurement_version desc",
        page_length=1,
    )
    version = cint(previous[0].measurement_version) + 1 if previous else 1
    doc = frappe.get_doc(
        {
            "doctype": "Customer Body Measurement",
            "customer": customer,
            "measurement_date": now_datetime(),
            "measured_by": frappe.session.user,
            "branch": branch,
            "default_unit": unit,
            "measurement_version": version,
            "previous_measurement": previous[0].name if previous else None,
            "body_height": height,
            "body_weight": flt(body_weight) if body_weight else None,
            "consent_confirmed": 1,
            "consent_by": frappe.session.user,
            "consent_on": now_datetime(),
            "status": "Draft",
            "capture_status": "Pending Photos",
            "analysis_status": "Not Started",
        }
    )
    doc.insert()
    return _serialize_session(doc)


@frappe.whitelist()
def upload_measurement_photo(session_name, photo_type, image_base64, filename=None):
    _require_authenticated()
    doc = _get_session(session_name, "write")
    photo_type = (photo_type or "").lower()
    fieldname = PHOTO_FIELDS.get(photo_type)
    if not fieldname:
        frappe.throw(_("Photo Type must be front, side, or back"))
    content = _decode_image(image_base64)
    safe_name = re.sub(r"[^A-Za-z0-9_.-]", "_", filename or f"{session_name}-{photo_type}.jpg")
    file_doc = save_file(safe_name, content, doc.doctype, doc.name, is_private=1)
    doc.set(fieldname, file_doc.file_url)
    doc.capture_status = "Photos Complete" if all(doc.get(value) for value in PHOTO_FIELDS.values()) else "Photos In Progress"
    doc.save()
    return {"session_name": doc.name, "photo_type": photo_type, "file_url": file_doc.file_url, "capture_status": doc.capture_status}


@frappe.whitelist()
def analyze_measurements(session_name):
    _require_authenticated()
    doc = _get_session(session_name, "write")
    if not all(doc.get(value) for value in PHOTO_FIELDS.values()):
        frappe.throw(_("Front, side, and back photos are required"))
    doc.analysis_status = "Processing"
    doc.save()
    frappe.db.commit()
    try:
        result = analyze_session(doc)
    except Exception:
        doc.reload()
        doc.analysis_status = "Failed"
        doc.save(ignore_version=True)
        raise

    allowed = {
        row.measurement_code: row
        for row in frappe.get_all(
            "Measurement Definition",
            filters={"measurement_scope": "Body", "is_active": 1},
            fields=["name", "measurement_code", "measurement_name", "default_unit"],
        )
    }
    existing_rows = {row.measurement_type: row for row in doc.measurements if row.measurement_type}
    accepted = []
    confidence_values = []
    for item in result.get("measurements") or []:
        code = str(item.get("code") or "").upper()
        definition = allowed.get(code)
        value = flt(item.get("value"))
        confidence = max(0, min(100, flt(item.get("confidence"))))
        if not definition or value <= 0:
            continue
        row = existing_rows.get(definition.name)
        if not row:
            row = doc.append("measurements", {"measurement_type": definition.name})
            existing_rows[definition.name] = row
        ai_notes = str(item.get("notes") or "")[:500]
        has_staff_value = (row.measured_value or 0) > 0 and row.source in ("Manual", "Staff Edited")
        if has_staff_value:
            suggestion = _("AI suggested {0} {1} ({2}% confidence)").format(
                value, doc.default_unit, confidence
            )
            row.remarks = "\n".join(filter(None, [row.remarks, suggestion]))[:500]
        else:
            row.measured_value = value
            row.unit = doc.default_unit
            row.source = "AI"
            row.confidence = confidence
            row.is_verified = 0
            row.remarks = ai_notes
        accepted.append(
            {
                "code": code,
                "name": definition.measurement_name,
                "value": row.measured_value,
                "unit": row.unit or doc.default_unit,
                "source": row.source,
                "confidence": row.confidence or 0,
                "verified": bool(row.is_verified),
                "remarks": row.remarks or "",
            }
        )
        confidence_values.append(confidence)

    quality = result.get("capture_quality") or "review"
    issues = [str(value)[:250] for value in result.get("quality_issues") or []]
    doc.analysis_status = "Needs New Photos" if quality == "fail" else "Ready for Review"
    doc.capture_quality = quality.title()
    doc.quality_issues = "\n".join(issues)
    doc.ai_model_version = result.get("model")
    doc.ai_response_id = result.get("response_id")
    doc.ai_request_id = result.get("request_id")
    doc.overall_confidence = sum(confidence_values) / len(confidence_values) if confidence_values else 0
    doc.save()
    return {
        "session_name": doc.name,
        "capture_quality": quality,
        "quality_issues": issues,
        "analysis_status": doc.analysis_status,
        "overall_confidence": doc.overall_confidence,
        "measurements": accepted,
    }


@frappe.whitelist()
def save_reviewed_measurements(session_name, measurements, status="Reviewed"):
    _require_authenticated()
    doc = _get_session(session_name, "write")
    rows = frappe.parse_json(measurements) if isinstance(measurements, str) else measurements
    if not isinstance(rows, list) or not rows:
        frappe.throw(_("At least one measurement is required"))
    if status not in ("Draft", "Reviewed", "Approved"):
        frappe.throw(_("Invalid status"))

    allowed = {
        row.measurement_code: row.name
        for row in frappe.get_all(
            "Measurement Definition",
            filters={"measurement_scope": "Body", "is_active": 1},
            fields=["name", "measurement_code"],
        )
    }
    existing_rows = {row.measurement_type: row for row in doc.measurements if row.measurement_type}
    seen = set()
    for item in rows:
        code = str(item.get("code") or "").upper()
        value = flt(item.get("value"))
        if code not in allowed or code in seen or value <= 0:
            frappe.throw(_("Invalid or duplicate measurement: {0}").format(code or "unknown"))
        seen.add(code)
        measurement_type = allowed[code]
        row = existing_rows.get(measurement_type)
        if not row:
            row = doc.append("measurements", {"measurement_type": measurement_type})
            existing_rows[measurement_type] = row
        row.measured_value = value
        row.unit = item.get("unit") if item.get("unit") in ("Inch", "CM") else doc.default_unit
        row.source = "Staff Edited" if item.get("edited") else "AI"
        row.confidence = max(0, min(100, flt(item.get("confidence"))))
        row.is_verified = 1
        row.remarks = str(item.get("remarks") or "")[:500]
    doc.status = status
    doc.analysis_status = "Reviewed"
    doc.reviewed_by = frappe.session.user
    doc.reviewed_on = now_datetime()
    doc.save()
    return _serialize_session(doc)


@frappe.whitelist()
def get_measurement_session(session_name):
    _require_authenticated()
    return _serialize_session(_get_session(session_name))


@frappe.whitelist()
def list_measurement_sessions(customer=None, limit=30):
    _require_authenticated()
    filters = {"customer": customer} if customer else None
    return frappe.get_list(
        "Customer Body Measurement",
        filters=filters,
        fields=["name", "customer", "customer_name", "measurement_date", "status", "capture_status", "analysis_status", "overall_confidence"],
        order_by="measurement_date desc",
        page_length=min(max(cint(limit), 1), 100),
    )


def _decode_image(value):
    raw = value or ""
    if "," in raw and raw.startswith("data:"):
        raw = raw.split(",", 1)[1]
    try:
        content = base64.b64decode(raw, validate=True)
    except (ValueError, binascii.Error) as exc:
        raise frappe.ValidationError(_("Invalid photo data")) from exc
    if not content or len(content) > MAX_IMAGE_BYTES:
        frappe.throw(_("Photo must be between 1 byte and 10 MB"))
    if not (content.startswith(b"\xff\xd8\xff") or content.startswith(b"\x89PNG\r\n\x1a\n")):
        frappe.throw(_("Only JPEG or PNG photos are supported"))
    return content


def _serialize_measurement(row, code=None):
    return {
        "code": code or row.measurement_type,
        "name": row.measurement_name,
        "value": row.measured_value,
        "unit": row.unit,
        "source": row.source,
        "confidence": row.confidence,
        "verified": bool(row.is_verified),
        "remarks": row.remarks,
    }


def _serialize_session(doc):
    return {
        "name": doc.name,
        "customer": doc.customer,
        "customer_name": doc.customer_name,
        "measurement_date": str(doc.measurement_date),
        "unit": doc.default_unit,
        "body_height": doc.body_height,
        "body_weight": doc.body_weight,
        "status": doc.status,
        "capture_status": doc.capture_status,
        "analysis_status": doc.analysis_status,
        "capture_quality": doc.capture_quality,
        "quality_issues": doc.quality_issues,
        "overall_confidence": doc.overall_confidence,
        "measurements": [_serialize_measurement(row) for row in doc.measurements],
    }
