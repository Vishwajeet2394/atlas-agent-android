package com.atlas.agent;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.media.ExifInterface;
import android.media.ThumbnailUtils;
import android.os.ParcelFileDescriptor;
import android.util.Base64;
import android.util.Size;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** Image scaling, PDF page rendering and thumbnails. */
final class Media {
    private Media() { }

    static Bitmap decodeScaled(File f, int maxDim) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) {
            // HEIC and friends: let the platform try
            try { return ThumbnailUtils.createImageThumbnail(f, new Size(maxDim, maxDim), null); } catch (Exception e) { return null; }
        }
        int s = 1;
        while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= maxDim) s *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = s;
        Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
        if (b == null) return null;
        b = fit(b, maxDim);
        try {
            int rot = new ExifInterface(f.getAbsolutePath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            int deg = rot == ExifInterface.ORIENTATION_ROTATE_90 ? 90 : rot == ExifInterface.ORIENTATION_ROTATE_180 ? 180 : rot == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
            if (deg != 0) {
                Matrix m = new Matrix(); m.postRotate(deg);
                b = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            }
        } catch (Exception ignored) { }
        return b;
    }

    static Bitmap fit(Bitmap b, int maxDim) {
        int w = b.getWidth(), h = b.getHeight(), m = Math.max(w, h);
        if (m <= maxDim) return b;
        float k = maxDim / (float) m;
        return Bitmap.createScaledBitmap(b, Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)), true);
    }

    static byte[] jpeg(Bitmap b, int q) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, q, bo);
        return bo.toByteArray();
    }

    static String b64(byte[] d) { return Base64.encodeToString(d, Base64.NO_WRAP); }

    static String imageB64(File f, int maxDim) {
        Bitmap b = decodeScaled(f, maxDim);
        return b == null ? null : b64(jpeg(b, 82));
    }

    static int pdfPageCount(File f) {
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer r = new PdfRenderer(pfd)) {
            return r.getPageCount();
        } catch (Exception e) { return 0; }
    }

    /** First pages of a PDF as JPEGs (base64). Works for password-free PDFs. */
    static List<String> pdfPages(File f, int maxPages, int maxDim) {
        List<String> out = new ArrayList<>();
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer r = new PdfRenderer(pfd)) {
            int n = Math.min(maxPages, r.getPageCount());
            for (int i = 0; i < n; i++) {
                Bitmap b = renderPage(r, i, maxDim);
                if (b != null) out.add(b64(jpeg(b, 80)));
            }
        } catch (Exception ignored) { }
        return out;
    }

    private static Bitmap renderPage(PdfRenderer r, int i, int maxDim) {
        try (PdfRenderer.Page p = r.openPage(i)) {
            int w = p.getWidth(), h = p.getHeight();
            float k = maxDim / (float) Math.max(w, h);
            int bw = Math.max(1, Math.round(w * k)), bh = Math.max(1, Math.round(h * k));
            Bitmap b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            new Canvas(b).drawColor(Color.WHITE);
            p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return b;
        } catch (Exception e) { return null; }
    }

    static String readText(File f, int maxChars) {
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[Math.min((int) Math.min(f.length(), 4L * maxChars), 4 * maxChars)];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            String s = new String(buf, 0, n, StandardCharsets.UTF_8);
            return s.length() > maxChars ? s.substring(0, maxChars) : s;
        } catch (Exception e) { return null; }
    }

    static byte[] readAll(File f) {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[65536]; int r;
            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
            return bo.toByteArray();
        } catch (Exception e) { return null; }
    }

    static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) { return String.valueOf(s.hashCode()); }
    }

    /** Small JPEG for the grid, cached on disk. size: 320 for thumbnails, 1600 for previews. */
    static byte[] thumb(Context c, File f, String kind, int size) {
        File dir = new File(c.getCacheDir(), "thumbs");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File cache = new File(dir, md5(f.getAbsolutePath() + ":" + f.lastModified() + ":" + size) + ".jpg");
        if (cache.exists()) return readAll(cache);
        Bitmap b = null;
        try {
            if ("image".equals(kind)) b = decodeScaled(f, size);
            else if ("video".equals(kind)) b = ThumbnailUtils.createVideoThumbnail(f, new Size(size, size), null);
            else if ("pdf".equals(kind)) {
                try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
                     PdfRenderer r = new PdfRenderer(pfd)) { b = renderPage(r, 0, size); }
            } else if ("audio".equals(kind)) b = ThumbnailUtils.createAudioThumbnail(f, new Size(size, size), null);
        } catch (Exception ignored) { }
        if (b == null) return null;
        byte[] d = jpeg(b, size > 600 ? 85 : 72);
        try (FileOutputStream o = new FileOutputStream(cache)) { o.write(d); } catch (Exception ignored) { }
        return d;
    }
}
