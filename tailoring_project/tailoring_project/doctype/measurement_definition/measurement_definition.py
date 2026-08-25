import re

import frappe
from frappe import _
from frappe.model.document import Document


class MeasurementDefinition(Document):
    def validate(self):
        self.measurement_code = (self.measurement_code or "").strip().upper().replace(" ", "_")
        if not re.fullmatch(r"[A-Z][A-Z0-9_]*", self.measurement_code):
            frappe.throw(_("Measurement Code must contain only uppercase letters, numbers, and underscores"))
        if self.minimum_value is not None and self.maximum_value is not None:
            if self.minimum_value > self.maximum_value:
                frappe.throw(_("Minimum Value cannot be greater than Maximum Value"))
