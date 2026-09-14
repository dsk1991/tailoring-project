# Tailoring Staff Android App - Manual Measurement Flow

The Android app is a manual tape-measurement client for ERPNext. It does not
request camera permission, upload customer photos, or call OpenAI.

## Staff flow

```text
ERPNext connection
  -> Customer search/select
  -> Garment Measurement Template select
  -> Required body values loaded from template formulas
  -> Latest Customer Body Measurement values prefilled
  -> Diagram + instruction + numeric value on every step
  -> Final review
  -> Customer Measurement by Template snapshot saved
  -> New consolidated Customer Body Measurement version saved
```

Only body codes referenced by the selected template formulas are shown. For
example, a Shirt template can ask for six inputs while a later Coat template
prefills shared Chest and Shoulder values from the latest body version.

The template snapshot records the input body value, formula, calculated value,
final garment value, source, and linked body-measurement version. Only manually
entered body inputs update the consolidated body record. Formula outputs stay
in the template snapshot and never replace the customer's real body value.

Version 2.0 requires the matching ERPNext backend APIs from this repository to
be deployed and the site migrated before the APK is used.
