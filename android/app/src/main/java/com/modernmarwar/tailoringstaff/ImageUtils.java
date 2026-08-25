package com.modernmarwar.tailoringstaff;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.util.Base64;

import androidx.exifinterface.media.ExifInterface;

import java.io.ByteArrayOutputStream;
import java.io.File;

final class ImageUtils {
    private ImageUtils() { }

    static String toUploadBase64(File file) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        int sample = 1;
        while (bounds.outWidth / sample > 1800 || bounds.outHeight / sample > 1800) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (bitmap == null) throw new IllegalArgumentException("Captured photo could not be read");
        bitmap = rotateIfNeeded(file, bitmap);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 86, output);
        bitmap.recycle();
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
    }

    static Bitmap preview(File file) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 8;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private static Bitmap rotateIfNeeded(File file, Bitmap bitmap) {
        try {
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            int degrees = orientation == ExifInterface.ORIENTATION_ROTATE_90 ? 90 :
                    orientation == ExifInterface.ORIENTATION_ROTATE_180 ? 180 :
                            orientation == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
            if (degrees == 0) return bitmap;
            Matrix matrix = new Matrix();
            matrix.postRotate(degrees);
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (rotated != bitmap) bitmap.recycle();
            return rotated;
        } catch (Exception ignored) {
            return bitmap;
        }
    }
}
