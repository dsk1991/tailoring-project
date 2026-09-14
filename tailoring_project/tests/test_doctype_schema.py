import json
import re
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from tailoring_project.tailoring_project.formula_engine import (  # noqa: E402
    FormulaError,
    evaluate_formula,
    get_references,
)


APP_ROOT = ROOT / "tailoring_project"
DOCTYPE_ROOT = APP_ROOT / "tailoring_project" / "doctype"


class TestFrappeAppStructure(unittest.TestCase):
    def test_required_frappe_app_files_exist(self):
        for filename in ("hooks.py", "modules.txt", "patches.txt"):
            with self.subTest(filename=filename):
                self.assertTrue((APP_ROOT / filename).is_file())


class TestDocTypeSchemas(unittest.TestCase):
    def load(self, folder, filename):
        path = DOCTYPE_ROOT / folder / filename
        return json.loads(path.read_text(encoding="utf-8"))

    def field_map(self, schema):
        return {field["fieldname"]: field for field in schema["fields"]}

    def test_all_eight_doctypes_are_valid_json(self):
        expected = {
            "Customer Body Measurement": "customer_body_measurement/customer_body_measurement.json",
            "Body Measurement Item": "body_measurement_item/body_measurement_item.json",
            "Measurement Definition": "measurement_definition/measurement_definition.json",
            "Garment Measurement Template": "garment_measurement_template/garment_measurement_template.json",
            "Garment Measurement Formula": "garment_measurement_formula/garment_measurement_formula.json",
            "Tailoring AI Settings": "tailoring_ai_settings/tailoring_ai_settings.json",
            "Customer Measurement by Template": "customer_measurement_by_template/customer_measurement_by_template.json",
            "Customer Template Measurement Item": "customer_template_measurement_item/customer_template_measurement_item.json",
        }
        for doctype_name, relative_path in expected.items():
            with self.subTest(doctype=doctype_name):
                schema = json.loads((DOCTYPE_ROOT / relative_path).read_text(encoding="utf-8"))
                self.assertEqual(schema["name"], doctype_name)
                self.assertEqual(schema["doctype"], "DocType")

    def test_parent_child_links_are_correct(self):
        body_parent = self.load("customer_body_measurement", "customer_body_measurement.json")
        garment_parent = self.load("garment_measurement_template", "garment_measurement_template.json")
        body_child = self.load("body_measurement_item", "body_measurement_item.json")
        formula_child = self.load("garment_measurement_formula", "garment_measurement_formula.json")
        template_snapshot = self.load("customer_measurement_by_template", "customer_measurement_by_template.json")
        template_item = self.load("customer_template_measurement_item", "customer_template_measurement_item.json")

        self.assertEqual(self.field_map(body_parent)["measurements"]["options"], "Body Measurement Item")
        self.assertEqual(
            self.field_map(garment_parent)["formula_items"]["options"], "Garment Measurement Formula"
        )
        self.assertEqual(body_child["istable"], 1)
        self.assertEqual(formula_child["istable"], 1)
        self.assertEqual(
            self.field_map(template_snapshot)["measurements"]["options"],
            "Customer Template Measurement Item",
        )
        self.assertEqual(template_item["istable"], 1)

    def test_measurement_links_use_definition_master(self):
        body_child = self.load("body_measurement_item", "body_measurement_item.json")
        formula_child = self.load("garment_measurement_formula", "garment_measurement_formula.json")
        template_item = self.load("customer_template_measurement_item", "customer_template_measurement_item.json")
        self.assertEqual(
            self.field_map(body_child)["measurement_type"]["options"], "Measurement Definition"
        )
        self.assertEqual(
            self.field_map(formula_child)["output_measurement"]["options"], "Measurement Definition"
        )
        self.assertEqual(
            self.field_map(template_item)["measurement_type"]["options"], "Measurement Definition"
        )

    def test_template_snapshot_links_to_consolidated_body_version(self):
        snapshot = self.load("customer_measurement_by_template", "customer_measurement_by_template.json")
        fields = self.field_map(snapshot)
        self.assertEqual(fields["customer"]["options"], "Customer")
        self.assertEqual(fields["measurement_template"]["options"], "Garment Measurement Template")
        self.assertEqual(fields["body_measurement"]["options"], "Customer Body Measurement")

    def test_draft_measurement_values_can_start_blank(self):
        body_child = self.load("body_measurement_item", "body_measurement_item.json")
        self.assertNotEqual(self.field_map(body_child)["measured_value"].get("reqd"), 1)

    def test_body_measurement_rows_are_auto_added_and_merged(self):
        controller = (
            DOCTYPE_ROOT / "customer_body_measurement" / "customer_body_measurement.py"
        ).read_text(encoding="utf-8")
        api_source = (ROOT / "tailoring_project" / "api.py").read_text(encoding="utf-8")
        self.assertIn("def _add_missing_measurement_rows", controller)
        self.assertIn('{"measurement_scope": "Body", "is_active": 1}', controller)
        self.assertNotIn('doc.set("measurements", [])', api_source)

    def test_photo_ai_and_consent_fields_are_present(self):
        session = self.load("customer_body_measurement", "customer_body_measurement.json")
        fields = self.field_map(session)
        for fieldname in (
            "body_height", "consent_confirmed", "front_photo", "side_photo", "back_photo",
            "capture_status", "analysis_status", "capture_quality", "quality_issues",
        ):
            self.assertIn(fieldname, fields)
        settings = self.load("tailoring_ai_settings", "tailoring_ai_settings.json")
        self.assertEqual(self.field_map(settings)["openai_api_key"]["fieldtype"], "Password")
        self.assertEqual(settings["issingle"], 1)


class TestFormulaEngine(unittest.TestCase):
    def test_evaluates_koti_shoulder_formula(self):
        result = evaluate_formula("SHOULDER_WIDTH - 0.50", {"SHOULDER_WIDTH": 18})
        self.assertEqual(result, 17.5)

    def test_evaluates_half_chest_formula(self):
        result = evaluate_formula("(CHEST_CIRCUMFERENCE / 2) + 2", {"CHEST_CIRCUMFERENCE": 40})
        self.assertEqual(result, 22)

    def test_collects_required_measurement_codes(self):
        references = get_references("MAX(CHEST_CIRCUMFERENCE, WAIST_CIRCUMFERENCE) + 2")
        self.assertEqual(references, {"CHEST_CIRCUMFERENCE", "WAIST_CIRCUMFERENCE"})

    def test_rejects_unsafe_python(self):
        with self.assertRaises(FormulaError):
            evaluate_formula("__import__('os').system('dir')", {})

    def test_reports_missing_measurement(self):
        with self.assertRaisesRegex(FormulaError, "SHOULDER_WIDTH is missing"):
            evaluate_formula("SHOULDER_WIDTH - 0.5", {})


class TestAndroidSecurityContract(unittest.TestCase):
    def test_android_is_manual_only_and_has_no_camera_or_openai(self):
        android_root = ROOT / "android" / "app" / "src" / "main"
        source = "\n".join(
            path.read_text(encoding="utf-8")
            for path in android_root.rglob("*")
            if path.is_file() and path.suffix in {".java", ".xml"}
        )
        self.assertNotIn("api.openai.com", source)
        self.assertIsNone(re.search(r"sk-[A-Za-z0-9_-]{20,}", source))
        self.assertIn("tailoring_project.api.", source)
        self.assertNotIn("analyze_measurements", source)
        self.assertNotIn("upload_measurement_photo", source)
        self.assertNotIn("android.permission.CAMERA", source)
        self.assertIn('API_PREFIX + "list_measurement_templates"', source)
        self.assertIn('API_PREFIX + "get_template_measurement_form"', source)
        self.assertIn('API_PREFIX + "save_template_measurement"', source)
        self.assertNotIn('API_PREFIX + "create_measurement_session"', source)
        self.assertIn("MeasurementGuideView", source)

    def test_server_api_is_authenticated_and_openai_key_is_not_returned(self):
        api_source = (ROOT / "tailoring_project" / "api.py").read_text(encoding="utf-8")
        self.assertNotIn("allow_guest=True", api_source)
        self.assertIn("frappe.session.user == \"Guest\"", api_source)
        self.assertNotIn('"openai_api_key"', api_source)

    def test_manual_session_api_does_not_require_photo_consent(self):
        api_source = (ROOT / "tailoring_project" / "api.py").read_text(encoding="utf-8")
        start = api_source.index("def create_manual_measurement_session")
        end = api_source.index("\n\n@frappe.whitelist()", start)
        method_source = api_source[start:end]
        self.assertIn('"measurement_mode": "Manual"', method_source)
        self.assertIn('"capture_status": "Not Required"', method_source)
        self.assertNotIn("photo consent", method_source.lower())
        self.assertIn("body_height=None", method_source)

    def test_manual_android_has_no_height_weight_setup(self):
        source = (
            ROOT / "android" / "app" / "src" / "main" / "java" / "com"
            / "modernmarwar" / "tailoringstaff" / "MainActivity.java"
        ).read_text(encoding="utf-8")
        self.assertNotIn('field("Actual Height"', source)
        self.assertNotIn('field("Weight Kg', source)
        self.assertIn('result.optJSONArray("required_measurements")', source)

    def test_dual_save_api_keeps_formula_outputs_out_of_body_master(self):
        api_source = (ROOT / "tailoring_project" / "api.py").read_text(encoding="utf-8")
        self.assertIn("def save_template_measurement", api_source)
        self.assertIn("_create_consolidated_body_version", api_source)
        self.assertIn('"update_body_measurement": 0', api_source)
        self.assertIn('"body_measurement": body_doc.name', api_source)


if __name__ == "__main__":
    unittest.main()
