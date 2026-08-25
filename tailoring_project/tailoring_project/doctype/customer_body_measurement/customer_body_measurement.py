import frappe
from frappe import _
from frappe.model.document import Document


class CustomerBodyMeasurement(Document):
    def before_validate(self):
        self._add_missing_measurement_rows()

    def validate(self):
        if (self.body_height or 0) <= 0:
            frappe.throw(_("Actual Height must be greater than zero"))
        if not self.consent_confirmed:
            frappe.throw(_("Customer photo consent must be confirmed"))
        self._validate_version()
        self._validate_previous_measurement()
        self._validate_measurements()

    def _add_missing_measurement_rows(self):
        """Keep one editable child row for every active body measurement type."""
        existing = {row.measurement_type for row in self.measurements if row.measurement_type}
        definitions = frappe.get_all(
            "Measurement Definition",
            filters={"measurement_scope": "Body", "is_active": 1},
            fields=["name", "default_unit"],
            order_by="display_order asc",
        )
        for definition in definitions:
            if definition.name in existing:
                continue
            self.append(
                "measurements",
                {
                    "measurement_type": definition.name,
                    "unit": self.default_unit or definition.default_unit or "Inch",
                    "source": "Manual",
                    "is_verified": 0,
                },
            )
            existing.add(definition.name)

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

            if self.status == "Approved" and (row.measured_value or 0) <= 0:
                frappe.throw(_("Value for {0} must be greater than zero").format(row.measurement_type))
            if row.confidence is not None and not 0 <= row.confidence <= 100:
                frappe.throw(_("Confidence for {0} must be between 0 and 100").format(row.measurement_type))
