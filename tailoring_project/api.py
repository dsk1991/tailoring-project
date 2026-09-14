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
from tailoring_project.tailoring_project.formula_engine import FormulaError, evaluate_formula, get_references


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
            "measurement_mode": "AI Photo",
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
def create_manual_measurement_session(customer, body_height=None, unit="Inch", body_weight=None, branch=None):
    _require_authenticated()
    if not frappe.db.exists("Customer", customer):
        frappe.throw(_("Customer does not exist"))
    if unit not in ("Inch", "CM"):
        frappe.throw(_("Unit must be Inch or CM"))

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
            "body_height": flt(body_height) if body_height else None,
            "body_weight": flt(body_weight) if body_weight else None,
            "measurement_mode": "Manual",
            "consent_confirmed": 0,
            "status": "Draft",
            "capture_status": "Not Required",
            "analysis_status": "Manual Entry",
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
        requested_source = item.get("source")
        if requested_source not in ("Manual", "AI", "Staff Edited"):
            requested_source = "Manual"
        row.source = "Staff Edited" if item.get("edited") else requested_source
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


@frappe.whitelist()
def list_measurement_templates():
    """Return active garment templates available to the logged-in staff member."""
    _require_authenticated()
    return frappe.get_list(
        "Garment Measurement Template",
        filters={"is_active": 1},
        fields=["name", "template_name", "garment_type", "fit_type", "gender", "template_version", "default_unit", "description"],
        order_by="garment_type asc, fit_type asc, template_version desc",
        page_length=200,
    )


@frappe.whitelist()
def get_template_measurement_form(customer, template):
    """Build the template input form and prefill it from the customer's latest body version."""
    _require_authenticated()
    if not frappe.db.exists("Customer", customer):
        frappe.throw(_("Customer does not exist"))
    template_doc = frappe.get_doc("Garment Measurement Template", template)
    if not template_doc.has_permission("read"):
        frappe.throw(_("Not permitted"), frappe.PermissionError)
    if not template_doc.is_active:
        frappe.throw(_("Measurement Template is not active"))

    required_codes = set()
    for formula_row in template_doc.formula_items:
        try:
            required_codes.update(get_references(formula_row.formula))
        except FormulaError as exc:
            frappe.throw(_("Invalid formula for {0}: {1}").format(formula_row.output_measurement, exc))
    if not required_codes:
        frappe.throw(_("Add formula items to this Measurement Template first"))

    definitions = frappe.get_all(
        "Measurement Definition",
        filters={"name": ["in", list(required_codes)], "measurement_scope": "Body", "is_active": 1},
        fields=["name", "measurement_code", "measurement_name", "body_section", "measurement_kind", "value_basis", "default_unit", "display_order", "instructions"],
        order_by="display_order asc",
    )
    found_codes = {row.measurement_code for row in definitions}
    missing = sorted(required_codes - found_codes)
    if missing:
        frappe.throw(_("Active Body Measurement Definitions missing: {0}").format(", ".join(missing)))

    latest = _latest_body_measurement(customer)
    target_unit = template_doc.default_unit or "Inch"
    existing = _body_value_map(latest, target_unit)
    inputs = []
    for definition in definitions:
        value = existing.get(definition.measurement_code)
        inputs.append(
            {
                "name": definition.name,
                "measurement_code": definition.measurement_code,
                "measurement_name": definition.measurement_name,
                "body_section": definition.body_section,
                "measurement_kind": definition.measurement_kind,
                "value_basis": definition.value_basis,
                "default_unit": template_doc.default_unit or definition.default_unit or "Inch",
                "display_order": definition.display_order,
                "instructions": definition.instructions or "",
                "existing_value": value,
                "has_existing_value": value is not None,
            }
        )

    return {
        "template": template_doc.name,
        "template_name": template_doc.template_name,
        "garment_type": template_doc.garment_type,
        "fit_type": template_doc.fit_type,
        "template_version": template_doc.template_version,
        "unit": template_doc.default_unit or "Inch",
        "body_measurement": latest.name if latest else None,
        "required_measurements": inputs,
    }


@frappe.whitelist()
def save_template_measurement(customer, template, measurements, status="Reviewed", branch=None):
    """Atomically save a template snapshot and a new consolidated body-measurement version."""
    _require_authenticated()
    if not frappe.db.exists("Customer", customer):
        frappe.throw(_("Customer does not exist"))
    if status not in ("Draft", "Reviewed", "Approved"):
        frappe.throw(_("Invalid status"))
    template_doc = frappe.get_doc("Garment Measurement Template", template)
    if not template_doc.has_permission("read"):
        frappe.throw(_("Not permitted"), frappe.PermissionError)
    if not template_doc.is_active:
        frappe.throw(_("Measurement Template is not active"))

    submitted_rows = frappe.parse_json(measurements) if isinstance(measurements, str) else measurements
    if not isinstance(submitted_rows, list) or not submitted_rows:
        frappe.throw(_("At least one measurement is required"))
    required_codes = set()
    for formula_row in template_doc.formula_items:
        try:
            required_codes.update(get_references(formula_row.formula))
        except FormulaError as exc:
            frappe.throw(_("Invalid formula for {0}: {1}").format(formula_row.output_measurement, exc))
    allowed = {
        row.measurement_code: row
        for row in frappe.get_all(
            "Measurement Definition",
            filters={"name": ["in", list(required_codes)], "measurement_scope": "Body", "is_active": 1},
            fields=["name", "measurement_code", "default_unit"],
        )
    }
    submitted = {}
    submitted_meta = {}
    for item in submitted_rows:
        code = str(item.get("code") or "").upper()
        value = flt(item.get("value"))
        if code not in allowed or code in submitted or value <= 0:
            frappe.throw(_("Invalid or duplicate template measurement: {0}").format(code or "unknown"))
        submitted[code] = value
        submitted_meta[code] = item
    missing = sorted(required_codes - set(submitted))
    if missing:
        frappe.throw(_("Required measurements missing: {0}").format(", ".join(missing)))

    previous = _latest_body_measurement(customer)
    target_unit = template_doc.default_unit or "Inch"
    previous_values = _body_value_map(previous, target_unit)
    consolidated = dict(previous_values)
    consolidated.update(submitted)
    changed_codes = {
        code for code, value in submitted.items()
        if code not in previous_values or abs(previous_values[code] - value) >= 0.0001
    }
    body_doc = _create_consolidated_body_version(
        customer, previous, consolidated, changed_codes, target_unit, branch
    )

    snapshot = frappe.get_doc(
        {
            "doctype": "Customer Measurement by Template",
            "customer": customer,
            "measurement_date": now_datetime(),
            "measured_by": frappe.session.user,
            "branch": branch,
            "measurement_template": template_doc.name,
            "garment_type": template_doc.garment_type,
            "fit_type": template_doc.fit_type,
            "template_version": template_doc.template_version,
            "body_measurement": body_doc.name,
            "default_unit": template_doc.default_unit or "Inch",
            "status": status,
        }
    )
    snapshot_rows = {}
    for code, value in submitted.items():
        old_value = previous_values.get(code)
        was_existing = bool(submitted_meta[code].get("was_existing")) and old_value is not None and abs(old_value - value) < 0.0001
        snapshot_rows[code] = snapshot.append(
            "measurements",
            {
                "measurement_type": allowed[code].name,
                "body_value": value,
                "final_value": value,
                "unit": template_doc.default_unit or allowed[code].default_unit or "Inch",
                "source": "From Body" if was_existing else "New Manual",
                "update_body_measurement": 1,
                "is_verified": 1,
            },
        )

    calculated = []
    for formula_row in sorted(template_doc.formula_items, key=lambda row: row.display_order or 0):
        try:
            raw_value = evaluate_formula(formula_row.formula, consolidated)
        except FormulaError as exc:
            frappe.throw(_("Cannot calculate {0}: {1}").format(formula_row.output_measurement, exc))
        value = _round_measurement(raw_value, formula_row.rounding_rule)
        if formula_row.minimum_value not in (None, "") and value < flt(formula_row.minimum_value):
            frappe.throw(_("Calculated {0} is below its minimum value").format(formula_row.output_measurement))
        if flt(formula_row.maximum_value) > 0 and value > flt(formula_row.maximum_value):
            frappe.throw(_("Calculated {0} is above its maximum value").format(formula_row.output_measurement))
        output_code = formula_row.output_measurement
        row = snapshot_rows.get(output_code)
        if row:
            row.formula = formula_row.formula
            row.calculated_value = value
            row.final_value = value
        else:
            row = snapshot.append(
                "measurements",
                {
                    "measurement_type": output_code,
                    "body_value": consolidated.get(output_code),
                    "formula": formula_row.formula,
                    "calculated_value": value,
                    "final_value": value,
                    "unit": template_doc.default_unit or "Inch",
                    "source": "Formula",
                    "update_body_measurement": 0,
                    "is_verified": 1,
                },
            )
            snapshot_rows[output_code] = row
        calculated.append(
            {
                "code": output_code,
                "name": frappe.db.get_value("Measurement Definition", output_code, "measurement_name") or output_code,
                "formula": formula_row.formula,
                "value": value,
                "unit": template_doc.default_unit or "Inch",
            }
        )
    snapshot.insert()
    return {
        "name": snapshot.name,
        "template_measurement": snapshot.name,
        "body_measurement": body_doc.name,
        "customer": customer,
        "garment_type": template_doc.garment_type,
        "fit_type": template_doc.fit_type,
        "status": snapshot.status,
        "calculated_measurements": calculated,
    }


@frappe.whitelist()
def list_template_measurements(customer=None, limit=30):
    _require_authenticated()
    filters = {"customer": customer} if customer else None
    return frappe.get_list(
        "Customer Measurement by Template",
        filters=filters,
        fields=["name", "customer", "customer_name", "measurement_date", "measurement_template", "garment_type", "fit_type", "body_measurement", "status"],
        order_by="measurement_date desc",
        page_length=min(max(cint(limit), 1), 100),
    )


def _latest_body_measurement(customer):
    rows = frappe.get_all(
        "Customer Body Measurement",
        filters={"customer": customer},
        fields=["name"],
        order_by="measurement_version desc, creation desc",
        page_length=1,
    )
    return frappe.get_doc("Customer Body Measurement", rows[0].name) if rows else None


def _body_value_map(doc, target_unit=None):
    if not doc:
        return {}
    values = {}
    for row in doc.measurements:
        value = flt(row.measured_value)
        if not row.measurement_type or value <= 0:
            continue
        source_unit = row.unit or doc.default_unit or target_unit
        if target_unit and source_unit != target_unit:
            value = value * 2.54 if source_unit == "Inch" and target_unit == "CM" else value / 2.54
        values[row.measurement_type] = round(value, 2)
    return values


def _create_consolidated_body_version(customer, previous, values, changed_codes, unit, branch):
    version = cint(previous.measurement_version) + 1 if previous else 1
    doc = frappe.get_doc(
        {
            "doctype": "Customer Body Measurement",
            "customer": customer,
            "measurement_date": now_datetime(),
            "measured_by": frappe.session.user,
            "branch": branch,
            "default_unit": unit,
            "measurement_version": version,
            "previous_measurement": previous.name if previous else None,
            "body_height": values.get("HEIGHT"),
            "body_weight": previous.body_weight if previous else None,
            "measurement_mode": "Manual",
            "consent_confirmed": 0,
            "status": "Reviewed",
            "capture_status": "Not Required",
            "analysis_status": "Manual Entry",
            "reviewed_by": frappe.session.user,
            "reviewed_on": now_datetime(),
        }
    )
    previous_rows = {row.measurement_type: row for row in previous.measurements} if previous else {}
    for code, value in values.items():
        old_row = previous_rows.get(code)
        doc.append(
            "measurements",
            {
                "measurement_type": code,
                "measured_value": value,
                "unit": unit,
                "source": "Staff Edited" if code in changed_codes and old_row else ("Manual" if code in changed_codes else (old_row.source or "Manual")),
                "confidence": 0 if code in changed_codes else (old_row.confidence or 0),
                "is_verified": 1,
                "remarks": _("Updated from garment template measurement") if code in changed_codes else (old_row.remarks or ""),
            },
        )
    doc.insert()
    return doc


def _round_measurement(value, rule):
    increments = {"Nearest 0.10": 0.10, "Nearest 0.25": 0.25, "Nearest 0.50": 0.50}
    increment = increments.get(rule)
    if not increment:
        return round(float(value), 2)
    return round(round(float(value) / increment) * increment, 2)


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
        "measurement_mode": doc.measurement_mode or "Manual",
        "status": doc.status,
        "capture_status": doc.capture_status,
        "analysis_status": doc.analysis_status,
        "capture_quality": doc.capture_quality,
        "quality_issues": doc.quality_issues,
        "overall_confidence": doc.overall_confidence,
        "measurements": [_serialize_measurement(row) for row in doc.measurements],
    }
