package com.fieldhelper.diag.ai;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Offline OCR for scanned pages with the open-source PaddleOCR models (Apache 2.0), run by ONNX Runtime.
 * Model 1 (detector) marks where text is; model 2 (recognizer) reads each piece.
 * Pure Java: works on the phone and on a computer for testing.
 */
public final class PaddleOcr implements AutoCloseable {

    /** A grey picture: one byte per pixel, 0 = black, 255 = white. */
    public static final class Gray {
        public final int w, h;
        public final byte[] px;
        public Gray(int w, int h, byte[] px) { this.w = w; this.h = h; this.px = px; }
        int at(int x, int y) { return px[y * w + x] & 0xFF; }
    }

    /** One piece of text found on the page, in pixels. */
    public static final class Word {
        public final String text;
        public final int x0, y0, x1, y1;
        public final float conf;
        public final boolean vertical;
        Word(String text, int x0, int y0, int x1, int y1, float conf, boolean vertical) {
            this.text = text; this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1; this.conf = conf; this.vertical = vertical;
        }
    }

    private static final int STRIP_H = 640, OVERLAP = 224;
    private static final float THRESH = 0.3f, BOX_THRESH = 0.5f, UNCLIP = 1.6f, MIN_CONF = 0.5f;
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f}, STD = {0.229f, 0.224f, 0.225f};

    private final OrtEnvironment env;
    private final OrtSession det, rec;
    private final List<String> chars = new ArrayList<>();

    public PaddleOcr(String detPath, String recPath, InputStream keys) throws OrtException, IOException {
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        o.setIntraOpNumThreads(4);
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        det = env.createSession(detPath, o);
        rec = env.createSession(recPath, o);
        chars.add(""); // CTC blank
        try (BufferedReader r = new BufferedReader(new InputStreamReader(keys, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) chars.add(line);
        }
        chars.add(" ");
    }

    /** Reads all text on a page. */
    public List<Word> read(Gray img) throws OrtException {
        List<int[]> boxes = detect(img);
        List<Word> out = new ArrayList<>();
        for (int[] b : boxes) {
            Word w = recognize(img, b);
            if (w != null) out.add(w);
        }
        return out;
    }

    // ---------- detection ----------

    private List<int[]> detect(Gray img) throws OrtException {
        int W = (img.w + 31) / 32 * 32;
        List<int[]> all = new ArrayList<>();
        for (int top = 0; ; top += STRIP_H - OVERLAP) {
            int y0 = Math.min(top, Math.max(0, img.h - STRIP_H));
            int H = Math.min(STRIP_H, (img.h + 31) / 32 * 32);
            float[] in = new float[3 * H * W];
            int plane = H * W;
            for (int y = 0; y < H; y++) {
                int sy = y0 + y;
                for (int x = 0; x < W; x++) {
                    float v = (sy < img.h && x < img.w) ? img.at(x, sy) / 255f : 1f;
                    int i = y * W + x;
                    in[i] = (v - MEAN[0]) / STD[0];
                    in[plane + i] = (v - MEAN[1]) / STD[1];
                    in[2 * plane + i] = (v - MEAN[2]) / STD[2];
                }
            }
            float[] prob;
            try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(in), new long[]{1, 3, H, W});
                 OrtSession.Result r = det.run(Collections.singletonMap(det.getInputNames().iterator().next(), t))) {
                float[][][][] p = (float[][][][]) r.get(0).getValue();
                prob = new float[H * W];
                for (int y = 0; y < H; y++) System.arraycopy(p[0][0][y], 0, prob, y * W, W);
            }
            boolean firstStrip = y0 == 0, lastStrip = y0 + H >= img.h;
            for (int[] b : boxesFrom(prob, W, H)) {
                // drop pieces cut by the strip edge: they are read whole in the neighbouring strip
                if (b[1] <= 1 && !firstStrip) continue;
                if (b[3] >= H - 2 && !lastStrip) continue;
                b[1] += y0; b[3] += y0;
                b[2] = Math.min(b[2], img.w - 1); b[3] = Math.min(b[3], img.h - 1);
                all.add(b);
            }
            if (lastStrip) break;
        }
        return dedupe(all);
    }

    /** Text areas from the probability map: threshold, dilate, connected pieces, score, grow. */
    private static List<int[]> boxesFrom(float[] prob, int W, int H) {
        boolean[] bin = new boolean[W * H];
        for (int i = 0; i < bin.length; i++) bin[i] = prob[i] > THRESH;
        boolean[] dil = new boolean[W * H];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int i = y * W + x;
            dil[i] = bin[i] || (x > 0 && bin[i - 1]) || (y > 0 && bin[i - W]) || (x > 0 && y > 0 && bin[i - W - 1]);
        }
        boolean[] seen = new boolean[W * H];
        List<int[]> out = new ArrayList<>();
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int s = 0; s < dil.length; s++) {
            if (!dil[s] || seen[s]) continue;
            int x0 = W, y0 = H, x1 = -1, y1 = -1;
            q.add(s); seen[s] = true;
            while (!q.isEmpty()) {
                int i = q.poll();
                int x = i % W, y = i / W;
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y;
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    int nx = x + dx, ny = y + dy;
                    if (nx < 0 || ny < 0 || nx >= W || ny >= H) continue;
                    int j = ny * W + nx;
                    if (dil[j] && !seen[j]) { seen[j] = true; q.add(j); }
                }
            }
            int bw = x1 - x0 + 1, bh = y1 - y0 + 1;
            if (Math.min(bw, bh) < 3) continue;
            double sum = 0;
            for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) sum += prob[y * W + x];
            if (sum / (bw * bh) < BOX_THRESH) continue;
            int d = (int) Math.round(bw * (double) bh * UNCLIP / (2.0 * (bw + bh)));
            out.add(new int[]{Math.max(0, x0 - d), Math.max(0, y0 - d), Math.min(W - 1, x1 + d), Math.min(H - 1, y1 + d)});
        }
        return out;
    }

    private static List<int[]> dedupe(List<int[]> boxes) {
        boxes.sort((a, b) -> Integer.compare((b[2] - b[0]) * (b[3] - b[1]), (a[2] - a[0]) * (a[3] - a[1])));
        List<int[]> keep = new ArrayList<>();
        for (int[] b : boxes) {
            boolean dup = false;
            for (int[] k : keep) {
                int ix = Math.min(b[2], k[2]) - Math.max(b[0], k[0]), iy = Math.min(b[3], k[3]) - Math.max(b[1], k[1]);
                if (ix > 0 && iy > 0 && ix * (double) iy > 0.6 * (b[2] - b[0]) * (double) (b[3] - b[1])) { dup = true; break; }
            }
            if (!dup) keep.add(b);
        }
        keep.sort((a, b) -> a[1] != b[1] ? Integer.compare(a[1], b[1]) : Integer.compare(a[0], b[0]));
        return keep;
    }

    // ---------- recognition ----------

    private Word recognize(Gray img, int[] b) throws OrtException {
        int bw = b[2] - b[0] + 1, bh = b[3] - b[1] + 1;
        boolean vertical = bh >= 1.5 * bw;
        if (!vertical) {
            Object[] r = run(crop(img, b, 0));
            return word((String) r[0], (Float) r[1], b, false);
        }
        // sideways text: drawings usually read bottom-to-top, so turn clockwise first; try the other way only if unsure
        Object[] best = run(crop(img, b, 1));
        if ((Float) best[1] < 0.85f) {
            Object[] ccw = run(crop(img, b, -1));
            if ((Float) ccw[1] > (Float) best[1]) best = ccw;
        }
        return word((String) best[0], (Float) best[1], b, true);
    }

    private Word word(String raw, float conf, int[] b, boolean vertical) {
        String t = clean(raw);
        if (t.isEmpty() || conf < MIN_CONF) return null;
        return new Word(t, b[0], b[1], b[2], b[3], conf, vertical);
    }

    /** Keeps Latin letters, digits and normal symbols; drops look-alike characters from other scripts and arrow marks. */
    static String clean(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x250 || c == '°' || c == 'Ω' || c == 'µ' || c == '±') b.append(c);
            else if (c >= 0xFF01 && c <= 0xFF5E) b.append((char) (c - 0xFEE0)); // full-width → normal
        }
        String t = b.toString().replaceAll("\\s+", " ").trim();
        t = t.replaceAll("^[<>^|]+\\s*", "").replaceAll("\\s*[<>^|]+$", "");
        return t.trim();
    }

    /** Crops a box to a 48-pixel-high strip for the reader. turn: 0 none, 1 clockwise, -1 anticlockwise. */
    private static float[][] crop(Gray img, int[] b, int turn) {
        int bw = b[2] - b[0] + 1, bh = b[3] - b[1] + 1;
        int sw = turn == 0 ? bw : bh, sh = turn == 0 ? bh : bw; // size after turning
        int outH = 48;
        int outW = Math.max(1, Math.min(2000, Math.round(outH * sw / (float) sh)));
        float[][] g = new float[outH][outW];
        for (int y = 0; y < outH; y++) {
            float fy = (y + 0.5f) * sh / outH - 0.5f;
            for (int x = 0; x < outW; x++) {
                float fx = (x + 0.5f) * sw / outW - 0.5f;
                // map turned coordinates back to the page
                float px, py;
                if (turn == 0) { px = fx; py = fy; }
                else if (turn == 1) { px = fy; py = bh - 1 - fx; }     // page rotated clockwise
                else { px = bw - 1 - fy; py = fx; }                      // page rotated anticlockwise
                g[y][x] = bilinear(img, b[0] + px, b[1] + py);
            }
        }
        return g;
    }

    private static float bilinear(Gray img, float x, float y) {
        x = Math.max(0, Math.min(img.w - 1, x));
        y = Math.max(0, Math.min(img.h - 1, y));
        int x0 = (int) x, y0 = (int) y, x1 = Math.min(img.w - 1, x0 + 1), y1 = Math.min(img.h - 1, y0 + 1);
        float ax = x - x0, ay = y - y0;
        float top = img.at(x0, y0) * (1 - ax) + img.at(x1, y0) * ax;
        float bot = img.at(x0, y1) * (1 - ax) + img.at(x1, y1) * ax;
        return top * (1 - ay) + bot * ay;
    }

    /** Runs the reader on one strip: returns {text, confidence}. */
    private Object[] run(float[][] g) throws OrtException {
        int H = g.length, w = g[0].length, W = Math.max(320, w);
        float[] in = new float[3 * H * W];
        int plane = H * W;
        for (int y = 0; y < H; y++) for (int x = 0; x < w; x++) {
            float v = (g[y][x] / 255f - 0.5f) / 0.5f;
            int i = y * W + x;
            in[i] = v; in[plane + i] = v; in[2 * plane + i] = v;
        }
        try (OnnxTensor t = OnnxTensor.createTensor(env, FloatBuffer.wrap(in), new long[]{1, 3, H, W});
             OrtSession.Result r = rec.run(Collections.singletonMap(rec.getInputNames().iterator().next(), t))) {
            float[][][] p = (float[][][]) r.get(0).getValue(); // [1][T][classes]
            StringBuilder sb = new StringBuilder();
            int last = 0;
            double confSum = 0;
            int n = 0;
            for (float[] step : p[0]) {
                int best = 0;
                for (int k = 1; k < step.length; k++) if (step[k] > step[best]) best = k;
                if (best != 0 && best != last && best < chars.size()) { sb.append(chars.get(best)); confSum += step[best]; n++; }
                last = best;
            }
            return new Object[]{sb.toString(), n == 0 ? 0f : (float) (confSum / n)};
        }
    }

    @Override
    public void close() throws OrtException { det.close(); rec.close(); }
}
