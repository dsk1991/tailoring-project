# Tailoring Staff app flow

## ERPNext setup

1. Update the `tailoring_project` app and run `bench --site <site> migrate`.
2. Open **Tailoring AI Settings** as System Manager.
3. Store the OpenAI API key in the Password field, select an image-capable model,
   enable AI Measurement, and save.
4. Give each app operator the standard **Sales User** role and read access to
   the customers they are allowed to measure.
5. Generate a separate ERPNext API key/secret for each staff user. Do not share
   one common administrator token.

## Android workflow

```text
ERPNext connection
  -> Customer search
  -> Height/weight + consent
  -> Measurement session
  -> Front photo
  -> Side photo
  -> Back photo
  -> Private ERPNext upload
  -> ERPNext calls OpenAI
  -> Capture-quality result
  -> AI measurement rows
  -> Staff review/edit
  -> Reviewed measurement save
```

If photo quality fails or no estimate is returned, staff can retake the photos
or switch to manual measurement entry. A session remains Draft until staff
saves the reviewed values.

## Capture standard

- Use a flat 2x2 inch reference grid with high-contrast calibration markers.
- Keep customer and photographer on their printed fixed footprints.
- Keep the phone upright, level, at a consistent height, distance, and zoom.
- Capture the complete body with head and feet visible.
- Use fitted clothing; avoid jackets, loose kurtas, dupattas, or covered joints.
- Front: feet parallel and arms slightly away from the torso.
- Side: exact 90-degree pose with head straight.
- Back: shoulders level and full body/grid visible.

## Security boundaries

- The Android app calls only authenticated ERPNext endpoints.
- ERPNext API credentials are encrypted using Android Keystore.
- Production ERPNext connections require HTTPS.
- Photos are saved as private ERPNext File records.
- The OpenAI API key is read only on the ERPNext server from a Password field.
- No OpenAI secret or direct OpenAI endpoint exists in the APK.
- Customer consent is required before a measurement session can be created.

## Accuracy boundary

Three RGB photos provide estimates, not guaranteed tape-accurate 3D body
measurements. Actual height, controlled calibration, capture quality, and staff
verification are mandatory. Validate the system against a measured customer
dataset before using it for garment production.
