package com.chanmin.sketch;

import java.util.Random;

/**
 * Pencil-sketch filter, pure Java (no Android / OpenCV dependency).
 * Pipeline mirrors the original Python/OpenCV version:
 *   denoise -> CLAHE(2.0, 8x8) -> colour-dodge shading -> DoG outlines
 *   -> multiply -> gamma 1.25 -> paper grain.
 */
public final class SketchCore {

    public interface Progress { void onProgress(int percent); }

    private static final int THREADS = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));

    private SketchCore() {}

    /** @param argb input pixels (ARGB), @return output pixels (opaque grey ARGB). */
    public static int[] process(int[] argb, int w, int h, Progress p) {
        final int n = w * h;
        float[] gray = new float[n];
        for (int i = 0; i < n; i++) {
            int c = argb[i];
            gray[i] = 0.299f * ((c >> 16) & 255) + 0.587f * ((c >> 8) & 255) + 0.114f * (c & 255);
        }
        report(p, 5);

        // 1) edge-preserving denoise (replaces NL-means + 2x bilateral)
        float[] t1 = new float[n], t2 = new float[n], t3 = new float[n], t4 = new float[n];
        guided(gray, w, h, 4, 18f * 18f, t1, t2, t3, t4);
        report(p, 20);
        guided(gray, w, h, 3, 12f * 12f, t1, t2, t3, t4);
        report(p, 35);

        // 2) CLAHE on 8-bit grey
        clahe(gray, w, h, 2.0, 8, 8);
        report(p, 45);

        // 3) colour-dodge shading: gray * 255 / (255 - blur(255 - gray))
        float[] blur = t1;
        for (int i = 0; i < n; i++) blur[i] = 255f - gray[i];
        boxGauss(blur, w, h, Math.max(w, h) / 70f, t2);
        float[] dodgeBuf = blur;
        for (int i = 0; i < n; i++) {
            float d = gray[i] * 255f / Math.max(255f - blur[i], 1f);
            dodgeBuf[i] = d > 255f ? 255f : d;
        }
        report(p, 60);

        // 4) DoG outlines (stored as g1 - g2 in g1)
        float[] g1 = t3, g2 = t4;
        System.arraycopy(gray, 0, g1, 0, n);
        System.arraycopy(gray, 0, g2, 0, n);
        gauss(g1, w, h, 1.2f, t2);
        gauss(g2, w, h, 3.0f, t2);
        for (int i = 0; i < n; i++) g1[i] -= g2[i];
        report(p, 80);

        // 5) combine + gamma + grain
        float[] gammaLut = new float[4097];
        for (int i = 0; i <= 4096; i++) gammaLut[i] = (float) (255.0 * Math.pow(i / 4096.0, 1.25));
        float[] grain = t2;
        Random rnd = new Random(0);
        for (int i = 0; i < n; i++) grain[i] = (float) rnd.nextGaussian();
        gauss(grain, w, h, 0.8f, g2);
        report(p, 90);

        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            float dodge = dodgeBuf[i];
            float dog = g1[i];
            float lines = 1f - Math.max(-dog - 3f, 0f) / 14f;
            if (lines < 0f) lines = 0f;
            float v = dodge * lines;
            int li = (int) (v / 255f * 4096f + 0.5f);
            if (li < 0) li = 0; else if (li > 4096) li = 4096;
            v = gammaLut[li] + grain[i] * 4f;
            int q = Math.round(v);
            if (q < 0) q = 0; else if (q > 255) q = 255;
            out[i] = 0xFF000000 | (q << 16) | (q << 8) | q;
        }
        report(p, 100);
        return out;
    }

    private static void report(Progress p, int v) { if (p != null) p.onProgress(v); }

    // ---------------------------------------------------------------- parallel helper
    interface RangeBody { void run(int from, int to); }

    static void parallel(final int count, final RangeBody body) {
        if (THREADS == 1 || count < 64) { body.run(0, count); return; }
        Thread[] ts = new Thread[THREADS];
        final int chunk = (count + THREADS - 1) / THREADS;
        for (int t = 0; t < THREADS; t++) {
            final int from = t * chunk, to = Math.min(count, from + chunk);
            ts[t] = new Thread(new Runnable() { public void run() { if (from < to) body.run(from, to); } });
            ts[t].start();
        }
        for (Thread t : ts) {
            try { t.join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    // ---------------------------------------------------------------- box filter (mean, radius r, clamped window)
    /** Mean over (2r+1)^2 window, shrinking at borders. src -> dst, tmp scratch. */
    static void boxMean(final float[] src, final float[] dst, final int w, final int h, final int r, final float[] tmp) {
        parallel(h, new RangeBody() { public void run(int y0, int y1) {
            for (int y = y0; y < y1; y++) {
                int row = y * w;
                double s = 0; int cnt = 0;
                for (int x = 0; x <= Math.min(r, w - 1); x++) { s += src[row + x]; cnt++; }
                for (int x = 0; x < w; x++) {
                    tmp[row + x] = (float) (s / cnt);
                    int add = x + r + 1, rem = x - r;
                    if (add < w) { s += src[row + add]; cnt++; }
                    if (rem >= 0) { s -= src[row + rem]; cnt--; }
                }
            }
        }});
        parallel(w, new RangeBody() { public void run(int x0, int x1) {
            for (int x = x0; x < x1; x++) {
                double s = 0; int cnt = 0;
                for (int y = 0; y <= Math.min(r, h - 1); y++) { s += tmp[y * w + x]; cnt++; }
                for (int y = 0; y < h; y++) {
                    dst[y * w + x] = (float) (s / cnt);
                    int add = y + r + 1, rem = y - r;
                    if (add < h) { s += tmp[add * w + x]; cnt++; }
                    if (rem >= 0) { s -= tmp[rem * w + x]; cnt--; }
                }
            }
        }});
    }

    /** Self-guided filter (He et al.), in place on I. Uses 4 scratch buffers. */
    static void guided(float[] I, int w, int h, int r, float eps, float[] a, float[] b, float[] c, float[] d) {
        int n = w * h;
        boxMean(I, a, w, h, r, d);                       // a = mean_I
        for (int i = 0; i < n; i++) c[i] = I[i] * I[i];
        boxMean(c, b, w, h, r, d);                       // b = mean_II
        for (int i = 0; i < n; i++) {
            float m = a[i];
            float var = b[i] - m * m;
            if (var < 0) var = 0;
            float A = var / (var + eps);
            b[i] = m - A * m;                            // b = B
            a[i] = A;                                    // a = A
        }
        boxMean(a, c, w, h, r, d);                       // c = mean_A
        boxMean(b, a, w, h, r, d);                       // a = mean_B
        for (int i = 0; i < n; i++) I[i] = c[i] * I[i] + a[i];
    }

    // ---------------------------------------------------------------- gaussian (exact, separable, reflect-101)
    static void gauss(final float[] img, final int w, final int h, float sigma, final float[] tmp) {
        final int rad = Math.max(1, (int) Math.ceil(sigma * 3));
        final float[] k = new float[2 * rad + 1];
        float sum = 0;
        for (int i = -rad; i <= rad; i++) { k[i + rad] = (float) Math.exp(-(i * i) / (2.0 * sigma * sigma)); sum += k[i + rad]; }
        for (int i = 0; i < k.length; i++) k[i] /= sum;
        parallel(h, new RangeBody() { public void run(int y0, int y1) {
            for (int y = y0; y < y1; y++) {
                int row = y * w;
                for (int x = 0; x < w; x++) {
                    float s = 0;
                    for (int j = -rad; j <= rad; j++) s += k[j + rad] * img[row + reflect(x + j, w)];
                    tmp[row + x] = s;
                }
            }
        }});
        parallel(w, new RangeBody() { public void run(int x0, int x1) {
            for (int x = x0; x < x1; x++) {
                for (int y = 0; y < h; y++) {
                    float s = 0;
                    for (int j = -rad; j <= rad; j++) s += k[j + rad] * tmp[reflect(y + j, h) * w + x];
                    img[y * w + x] = s;
                }
            }
        }});
    }

    static int reflect(int i, int n) {
        if (n == 1) return 0;
        while (i < 0 || i >= n) { if (i < 0) i = -i; if (i >= n) i = 2 * n - 2 - i; }
        return i;
    }

    /** Large-sigma Gaussian approximated by 3 successive box blurs (in place). */
    static void boxGauss(float[] img, int w, int h, float sigma, float[] tmp) {
        // box sizes for 3 passes (Kovesi / Kutskir)
        int nPass = 3;
        double wIdeal = Math.sqrt((12 * sigma * sigma / nPass) + 1);
        int wl = (int) Math.floor(wIdeal); if (wl % 2 == 0) wl--;
        int wu = wl + 2;
        double mIdeal = (12 * sigma * sigma - nPass * wl * wl - 4 * nPass * wl - 3 * nPass) / (-4.0 * wl - 4);
        long m = Math.round(mIdeal);
        for (int i = 0; i < nPass; i++) {
            int size = i < m ? wl : wu;
            boxMean(img, img, w, h, (size - 1) / 2, tmp);
        }
    }

    // ---------------------------------------------------------------- CLAHE (OpenCV-style), in place on float grey
    static void clahe(float[] img, int w, int h, double clip, int tx, int ty) {
        int tileW = (w + tx - 1) / tx, tileH = (h + ty - 1) / ty;
        int tileArea = tileW * tileH;
        int clipLimit = Math.max(1, (int) (clip * tileArea / 256));
        float lutScale = 255f / tileArea;
        int[][] luts = new int[tx * ty][256];
        int[] hist = new int[256];
        for (int j = 0; j < ty; j++) {
            for (int i = 0; i < tx; i++) {
                java.util.Arrays.fill(hist, 0);
                int y0 = j * tileH, x0 = i * tileW;
                // pad with reflect-101 like OpenCV when the image isn't divisible
                for (int y = y0; y < y0 + tileH; y++) {
                    int yy = reflect(y, h) * w;
                    for (int x = x0; x < x0 + tileW; x++) {
                        int v = Math.round(img[yy + reflect(x, w)]);
                        hist[v < 0 ? 0 : (v > 255 ? 255 : v)]++;
                    }
                }
                int clipped = 0;
                for (int k = 0; k < 256; k++) if (hist[k] > clipLimit) { clipped += hist[k] - clipLimit; hist[k] = clipLimit; }
                int batch = clipped / 256, residual = clipped - batch * 256;
                for (int k = 0; k < 256; k++) hist[k] += batch;
                if (residual != 0) {
                    int step = Math.max(256 / residual, 1);
                    for (int k = 0; k < 256 && residual > 0; k += step, residual--) hist[k]++;
                }
                int[] lut = luts[j * tx + i];
                int s = 0;
                for (int k = 0; k < 256; k++) { s += hist[k]; lut[k] = Math.min(255, Math.round(s * lutScale)); }
            }
        }
        float invTW = 1f / tileW, invTH = 1f / tileH;
        for (int y = 0; y < h; y++) {
            float tyf = y * invTH - 0.5f;
            int ty1 = (int) Math.floor(tyf), ty2 = ty1 + 1;
            float ya = tyf - ty1;
            ty1 = Math.max(ty1, 0); ty2 = Math.min(ty2, ty - 1);
            for (int x = 0; x < w; x++) {
                float txf = x * invTW - 0.5f;
                int tx1 = (int) Math.floor(txf), tx2 = tx1 + 1;
                float xa = txf - tx1;
                tx1 = Math.max(tx1, 0); tx2 = Math.min(tx2, tx - 1);
                int v = Math.round(img[y * w + x]);
                v = v < 0 ? 0 : (v > 255 ? 255 : v);
                float r = (luts[ty1 * tx + tx1][v] * (1 - xa) + luts[ty1 * tx + tx2][v] * xa) * (1 - ya)
                        + (luts[ty2 * tx + tx1][v] * (1 - xa) + luts[ty2 * tx + tx2][v] * xa) * ya;
                img[y * w + x] = Math.round(r);
            }
        }
    }
}
