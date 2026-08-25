import frappe
from frappe import _
from frappe.model.document import Document


class CustomerBodyMeasurement(Document):
    def validate(self):
        self._validate_version()
        self._validate_previous_measurement()
        self._validate_measurements()

    def _validate_version(self):
        if (self.measurement_version or 0) < 1:
            frappe.throw(_("Measurement Version must be at least 1"))

    def _validate_previous_measurement(self):
        if not self.previous_measurement:
            return
        if self.previous_measurement == self.name:
            frappe.throw(_("Previous Measurement cannot reference the same document"))
        previous_customer = frappe.db.get_value(
            "Customer Body Measurement", self.previous_measurement, "customer"
        )
        if previous_customer and previous_customer != self.customer:
            frappe.throw(_("Previous Measurement must belong to the same Customer"))

    def _validate_measurements(self):
        if self.status == "Approved" and not self.measurements:
            frappe.throw(_("At least one measurement is required before approval"))

        seen = set()
        for row in self.measurements:
            if row.measurement_type in seen:
                frappe.throw(_("Measurement {0} is entered more than once").format(row.measurement_type))
            seen.add(row.measurement_type)

            if (row.measured_value or 0) <= 0:
                frappe.throw(_("Value for {0} must be greater than zero").format(row.measurement_type))
            if row.confidence is not None and not 0 <= row.confidence <= 100:
                frappe.throw(_("Confidence for {0} must be between 0 and 100").format(row.measurement_type))
