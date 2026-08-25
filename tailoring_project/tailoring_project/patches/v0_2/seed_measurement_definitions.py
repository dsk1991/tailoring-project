import frappe


DEFINITIONS = [
    ("HEIGHT", "Full Height", "General", "Length", "Straight Length"),
    ("NECK_CIRCUMFERENCE", "Neck Circumference", "Neck", "Circumference", "Full Circumference"),
    ("SHOULDER_WIDTH", "Shoulder Width", "Shoulder", "Width", "Straight Length"),
    ("CHEST_CIRCUMFERENCE", "Chest Circumference", "Torso", "Circumference", "Full Circumference"),
    ("WAIST_CIRCUMFERENCE", "Waist Circumference", "Torso", "Circumference", "Full Circumference"),
    ("BELLY_CIRCUMFERENCE", "Belly Circumference", "Torso", "Circumference", "Full Circumference"),
    ("HIP_CIRCUMFERENCE", "Hip / Seat Circumference", "Lower Body", "Circumference", "Full Circumference"),
    ("ARM_LENGTH", "Arm Length", "Arm", "Length", "Straight Length"),
    ("BICEP_CIRCUMFERENCE", "Bicep Circumference", "Arm", "Circumference", "Full Circumference"),
    ("WRIST_CIRCUMFERENCE", "Wrist Circumference", "Arm", "Circumference", "Full Circumference"),
    ("BACK_LENGTH", "Back Length", "Torso", "Length", "Straight Length"),
    ("THIGH_CIRCUMFERENCE", "Thigh Circumference", "Leg", "Circumference", "Full Circumference"),
    ("KNEE_CIRCUMFERENCE", "Knee Circumference", "Leg", "Circumference", "Full Circumference"),
    ("CALF_CIRCUMFERENCE", "Calf Circumference", "Leg", "Circumference", "Full Circumference"),
    ("ANKLE_CIRCUMFERENCE", "Ankle Circumference", "Leg", "Circumference", "Full Circumference"),
    ("INSEAM_LENGTH", "Inseam Length", "Leg", "Length", "Straight Length"),
    ("OUTSEAM_LENGTH", "Outseam Length", "Leg", "Length", "Straight Length"),
]


def execute():
    for index, (code, name, section, kind, basis) in enumerate(DEFINITIONS, start=1):
        if frappe.db.exists("Measurement Definition", code):
            continue
        frappe.get_doc(
            {
                "doctype": "Measurement Definition",
                "measurement_code": code,
                "measurement_name": name,
                "measurement_scope": "Body",
                "body_section": section,
                "measurement_kind": kind,
                "value_basis": basis,
                "default_unit": "Inch",
                "display_order": index * 10,
                "is_active": 1,
            }
        ).insert(ignore_permissions=True)
