import frappe
from frappe import _
from frappe.model.document import Document


class TailoringAISettings(Document):
    def validate(self):
        if self.request_timeout_seconds and not 15 <= self.request_timeout_seconds <= 180:
            frappe.throw(_("Request Timeout must be between 15 and 180 seconds"))
