import frappe
from frappe import _
from frappe.model.document import Document

from tailoring_project.tailoring_project.formula_engine import FormulaError, get_references


class GarmentMeasurementTemplate(Document):
    def validate(self):
        if (self.template_version or 0) < 1:
            frappe.throw(_("Template Version must be at least 1"))

        seen = set()
        for row in self.formula_items:
            if row.output_measurement in seen:
                frappe.throw(_("Output Measurement {0} is entered more than once").format(row.output_measurement))
            seen.add(row.output_measurement)
            try:
                get_references(row.formula)
            except FormulaError as exc:
                frappe.throw(_("Invalid formula for {0}: {1}").format(row.output_measurement, str(exc)))
            if row.minimum_value is not None and row.maximum_value is not None:
                if row.minimum_value > row.maximum_value:
                    frappe.throw(
                        _("Minimum Value cannot exceed Maximum Value for {0}").format(row.output_measurement)
                    )
