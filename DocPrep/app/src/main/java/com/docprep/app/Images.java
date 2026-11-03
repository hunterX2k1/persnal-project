package com.docprep.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;

import java.io.ByteArrayOutputStream;
import java.util.List;

/** Bitmap helpers: shrink to Claude's useful size, flatten transparency onto white, JPEG-encode, cut zoom tiles. */
final class Images {
    private Images() {}

    static byte[] jpeg(Bitmap b) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, Options.JPEG_Q, o);
        return o.toByteArray();
    }

    static Bitmap fit(Bitmap src, int longEdge) {
        int w = src.getWidth(), h = src.getHeight();
        float s = Math.min(1f, longEdge / (float) Math.max(w, h));
        int nw = Math.max(1, Math.round(w * s)), nh = Math.max(1, Math.round(h * s));
        Bitmap out = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(Color.WHITE);                       // transparent areas become white, not black
        c.drawBitmap(src, new Rect(0, 0, w, h), new Rect(0, 0, nw, nh), null);
        return out;
    }

    /** Decodes any image file/bytes at a memory-safe size and returns it as a Claude-sized JPEG. */
    static Unit.Img fromBytes(String name, byte[] data) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0) return null;                // not a bitmap format Android can read (e.g. EMF/WMF)
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= Options.LONG_EDGE) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (b == null) return null;
        Bitmap f = fit(b, Options.LONG_EDGE);
        b.recycle();
        Unit.Img img = new Unit.Img(name, jpeg(f), f.getWidth(), f.getHeight());
        f.recycle();
        return img;
    }

    /** Adds the full view plus, in high-detail mode, 4 overlapping zoom tiles cut from a 2x render. */
    static void addPageImages(Bitmap big, String base, boolean tiles, List<Unit.Img> out) {
        Bitmap full = fit(big, Options.LONG_EDGE);
        out.add(new Unit.Img(base + ".jpg", jpeg(full), full.getWidth(), full.getHeight()));
        full.recycle();
        if (!tiles) return;
        int w = big.getWidth(), h = big.getHeight();
        int ox = w / 25, oy = h / 25;                   // 4% overlap so nothing is cut in half
        String[] names = {"_t1", "_t2", "_t3", "_t4"};
        int k = 0;
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) {
                int l = Math.max(0, x * w / 2 - ox), t = Math.max(0, y * h / 2 - oy);
                int r = Math.min(w, (x + 1) * w / 2 + ox), btm = Math.min(h, (y + 1) * h / 2 + oy);
                Bitmap tile = Bitmap.createBitmap(big, l, t, r - l, btm - t);
                Bitmap f = fit(tile, Options.LONG_EDGE);
                out.add(new Unit.Img(base + names[k++] + ".jpg", jpeg(f), f.getWidth(), f.getHeight()));
                if (tile != big) tile.recycle();
                f.recycle();
            }
        }
    }
}
