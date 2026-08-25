# Tailoring Project

ERPNext/Frappe v16 app for storing versioned customer body measurements and
defining garment measurement templates with safe formulas.

## Included DocTypes

1. **Customer Body Measurement** - one versioned measurement session per customer.
2. **Body Measurement Item** - child rows containing individual body values.
3. **Measurement Definition** - reusable body/garment measurement master.
4. **Garment Measurement Template** - garment, fit, version, and validity header.
5. **Garment Measurement Formula** - child rows defining output formulas.

Measurements are stored as rows instead of adding a separate field to the
Customer DocType for every possible measurement.

## Formula examples

```text
SHOULDER_WIDTH
SHOULDER_WIDTH - 0.50
CHEST_CIRCUMFERENCE + 4
(CHEST_CIRCUMFERENCE / 2) + 2
ROUND(WAIST_CIRCUMFERENCE + 1, 2)
```

The formula parser allows numeric constants, approved measurement codes,
parentheses, `+`, `-`, `*`, `/`, and the functions `MIN`, `MAX`, and `ROUND`.
Python `eval()` is not used.

## Installation

```bash
cd frappe-bench
bench get-app https://github.com/dsk1991/tailoring-project.git
bench --site your-site.local install-app tailoring_project
bench --site your-site.local migrate
```

Use the repository URL as the only positional argument to `bench get-app`.
Do not prefix the command with a folder name such as `tests`.

ERPNext v16 is required because Customer, Branch, and related links are used.

## Development validation

The repository includes dependency-free schema and formula tests:

```bash
python -m unittest discover -s tailoring_project/tests -v
```

Live ERPNext installation and user-permission testing must still be completed
on the target site.
