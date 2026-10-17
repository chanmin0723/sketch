package com.chanmin.sketch;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_PICK = 1;
    private static final int MAX_SIDE = 3000;      // same as the original script
    private static final int PREVIEW_SIDE = 1600;

    private static final int C_PAPER = 0xFFF3EFE6;
    private static final int C_CARD = 0xFFFFFFFF;
    private static final int C_INK = 0xFF2B2B2B;
    private static final int C_MUTED = 0xFF7A746A;
    private static final int C_LINE = 0xFFDCD5C8;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean busy = false;

    private ImageView preview;
    private TextView placeholder, previewTag, status;
    private ProgressBar progress;
    private Button pickBtn, shareBtn;

    private Bitmap lastOriginalPreview, lastSketchPreview;
    private boolean showingSketch = true;
    private final ArrayList<Uri> lastSaved = new ArrayList<>();

    // ------------------------------------------------------------------ UI
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(C_PAPER);
        getWindow().setNavigationBarColor(C_PAPER);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        setContentView(buildUi());
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_PAPER);
        root.setPadding(dp(20), dp(18), dp(20), dp(16));

        TextView title = text("스케치 변환", 26, C_INK);
        title.setTypeface(Typeface.create("serif", Typeface.BOLD));
        root.addView(title);

        TextView sub = text("사진을 고르면 연필 스케치 PNG로 바꿔서\n갤러리 › Pictures › Sketch 에 저장해요.", 14, C_MUTED);
        sub.setPadding(0, dp(4), 0, dp(14));
        root.addView(sub);

        // preview card
        FrameLayout card = new FrameLayout(this);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(C_CARD);
        cardBg.setCornerRadius(dp(16));
        cardBg.setStroke(dp(1), C_LINE);
        card.setBackground(cardBg);
        card.setClipToOutline(true);
        card.setPadding(dp(8), dp(8), dp(8), dp(8));

        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { togglePreview(); }
        });
        card.addView(preview, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        placeholder = text("아직 변환한 사진이 없어요\n아래 ‘사진 선택’을 눌러 시작하세요", 15, C_MUTED);
        placeholder.setGravity(Gravity.CENTER);
        card.addView(placeholder, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        previewTag = text("", 12, Color.WHITE);
        GradientDrawable tagBg = new GradientDrawable();
        tagBg.setColor(0xB32B2B2B);
        tagBg.setCornerRadius(dp(12));
        previewTag.setBackground(tagBg);
        previewTag.setPadding(dp(10), dp(4), dp(10), dp(4));
        previewTag.setVisibility(View.GONE);
        FrameLayout.LayoutParams tagLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        tagLp.bottomMargin = dp(8);
        card.addView(previewTag, tagLp);

        root.addView(card, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // status + progress
        status = text("", 14, C_INK);
        status.setPadding(0, dp(12), 0, dp(6));
        root.addView(status);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(ColorStateList.valueOf(C_INK));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(C_LINE));
        progress.setVisibility(View.INVISIBLE);
        root.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));

        // buttons
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(12), 0, 0);

        pickBtn = button("사진 선택", true);
        pickBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickImages(); }
        });
        shareBtn = button("공유", false);
        shareBtn.setEnabled(false);
        shareBtn.setAlpha(0.4f);
        shareBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { shareLast(); }
        });

        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, dp(52), 2f);
        lp1.rightMargin = dp(10);
        row.addView(pickBtn, lp1);
        row.addView(shareBtn, new LinearLayout.LayoutParams(0, dp(52), 1f));
        root.addView(row);

        TextView hint = text("갤러리에서 사진 공유 → ‘스케치로 변환’으로도 바로 쓸 수 있어요.", 12, C_MUTED);
        hint.setPadding(0, dp(10), 0, 0);
        hint.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(hint);
        return root;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private Button button(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(primary ? Color.WHITE : C_INK);
        b.setStateListAnimator(null);
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(14));
        if (primary) shape.setColor(C_INK);
        else { shape.setColor(C_CARD); shape.setStroke(dp(1), C_INK); }
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(primary ? 0x33FFFFFF : 0x22000000), shape, null));
        return b;
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void setBusy(boolean b) {
        busy = b;
        pickBtn.setEnabled(!b);
        pickBtn.setAlpha(b ? 0.5f : 1f);
        boolean canShare = !b && !lastSaved.isEmpty();
        shareBtn.setEnabled(canShare);
        shareBtn.setAlpha(canShare ? 1f : 0.4f);
        progress.setVisibility(b ? View.VISIBLE : View.INVISIBLE);
    }

    private void togglePreview() {
        if (lastSketchPreview == null || lastOriginalPreview == null) return;
        showingSketch = !showingSketch;
        preview.setImageBitmap(showingSketch ? lastSketchPreview : lastOriginalPreview);
        previewTag.setText(showingSketch ? "스케치 · 탭하면 원본" : "원본 · 탭하면 스케치");
    }

    // ------------------------------------------------------------------ input
    private void pickImages() {
        if (busy) return;
        Intent i;
        if (Build.VERSION.SDK_INT >= 33) {
            i = new Intent(MediaStore.ACTION_PICK_IMAGES);
            i.setType("image/*");
            i.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, Math.min(30, MediaStore.getPickImagesMaxLimit()));
        } else {
            i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            Intent g = new Intent(Intent.ACTION_GET_CONTENT);
            g.setType("image/*");
            g.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(Intent.createChooser(g, "사진 선택"), REQ_PICK);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return;
        ArrayList<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) for (int k = 0; k < clip.getItemCount(); k++) uris.add(clip.getItemAt(k).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        startJob(uris);
    }

    @SuppressWarnings("deprecation")
    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        ArrayList<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        }
        if (!uris.isEmpty()) {
            intent.setAction(Intent.ACTION_MAIN); // don't re-run on resume
            startJob(uris);
        }
    }

    // ------------------------------------------------------------------ processing
    private void startJob(final List<Uri> uris) {
        if (uris.isEmpty()) return;
        if (busy) { toast("변환 중이에요. 끝난 뒤 다시 시도해 주세요."); return; }
        lastSaved.clear();
        setBusy(true);
        progress.setProgress(0);
        worker.execute(new Runnable() {
            public void run() { runJob(uris); }
        });
    }

    private void runJob(List<Uri> uris) {
        final int total = uris.size();
        int ok = 0;
        String lastError = null;
        for (int idx = 0; idx < total; idx++) {
            final int i = idx;
            ui("변환 중… " + (i + 1) + " / " + total, i * 100 / total);
            try {
                convertOne(uris.get(i), i, total);
                ok++;
            } catch (Throwable t) {
                lastError = t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "");
            }
        }
        final int done = ok;
        final String err = lastError;
        runOnUiThread(new Runnable() {
            public void run() {
                setBusy(false);
                if (done == total) status.setText("완료! " + done + "장을 Pictures/Sketch 에 저장했어요.");
                else status.setText(done + " / " + total + "장 저장. 일부 실패: " + err);
            }
        });
    }

    private void convertOne(Uri uri, final int index, final int total) throws Exception {
        Bitmap src;
        int maxSide = MAX_SIDE;
        int[] out;
        int w, h;
        while (true) {
            src = decode(uri, maxSide);
            w = src.getWidth();
            h = src.getHeight();
            try {
                int[] px = new int[w * h];
                src.getPixels(px, 0, w, 0, 0, w, h);
                out = SketchCore.process(px, w, h, new SketchCore.Progress() {
                    public void onProgress(int p) {
                        final int overall = (index * 100 + p * 90 / 100) / total;
                        runOnUiThread(new Runnable() { public void run() { progress.setProgress(overall); } });
                    }
                });
                break;
            } catch (OutOfMemoryError oom) {
                src.recycle();
                System.gc();
                if (maxSide <= 1500) throw new Exception("메모리 부족");
                maxSide = maxSide * 2 / 3;   // retry smaller
            }
        }

        final Bitmap origPrev = scaled(src, PREVIEW_SIDE);
        if (origPrev != src) src.recycle();

        Bitmap result = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888);
        out = null;
        Uri saved = savePng(result, baseName(uri));
        lastSaved.add(saved);
        final Bitmap sketchPrev = scaled(result, PREVIEW_SIDE);
        if (sketchPrev != result) result.recycle();

        runOnUiThread(new Runnable() {
            public void run() {
                lastOriginalPreview = origPrev;
                lastSketchPreview = sketchPrev;
                showingSketch = true;
                placeholder.setVisibility(View.GONE);
                preview.setImageBitmap(sketchPrev);
                previewTag.setText("스케치 · 탭하면 원본");
                previewTag.setVisibility(View.VISIBLE);
                progress.setProgress((index + 1) * 100 / total);
            }
        });
    }

    private Bitmap decode(Uri uri, final int maxSide) throws Exception {
        ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
        Bitmap bm = ImageDecoder.decodeBitmap(source, new ImageDecoder.OnHeaderDecodedListener() {
            public void onHeaderDecoded(ImageDecoder decoder, ImageDecoder.ImageInfo info, ImageDecoder.Source s) {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                Size size = info.getSize();
                int m = Math.max(size.getWidth(), size.getHeight());
                if (m > maxSide) {
                    double sc = (double) maxSide / m;
                    decoder.setTargetSize(Math.max(1, (int) (size.getWidth() * sc)), Math.max(1, (int) (size.getHeight() * sc)));
                }
            }
        });
        if (bm.getConfig() != Bitmap.Config.ARGB_8888) {
            Bitmap c = bm.copy(Bitmap.Config.ARGB_8888, false);
            bm.recycle();
            bm = c;
        }
        return bm;
    }

    private static Bitmap scaled(Bitmap b, int maxSide) {
        int m = Math.max(b.getWidth(), b.getHeight());
        if (m <= maxSide) return b;
        double s = (double) maxSide / m;
        return Bitmap.createScaledBitmap(b, (int) (b.getWidth() * s), (int) (b.getHeight() * s), true);
    }

    private String baseName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (Exception ignored) { }
        if (name == null || name.isEmpty())
            name = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return name + "_sketch";
    }

    private Uri savePng(Bitmap bm, String name) throws Exception {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name + ".png");
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Sketch");
        v.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new Exception("저장 실패");
        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            if (os == null || !bm.compress(Bitmap.CompressFormat.PNG, 100, os)) throw new Exception("PNG 쓰기 실패");
        } catch (Exception e) {
            getContentResolver().delete(uri, null, null);
            throw e;
        }
        v.clear();
        v.put(MediaStore.Images.Media.IS_PENDING, 0);
        getContentResolver().update(uri, v, null, null);
        return uri;
    }

    // ------------------------------------------------------------------ share
    private void shareLast() {
        if (lastSaved.isEmpty()) return;
        Intent s;
        if (lastSaved.size() == 1) {
            s = new Intent(Intent.ACTION_SEND);
            s.putExtra(Intent.EXTRA_STREAM, lastSaved.get(0));
        } else {
            s = new Intent(Intent.ACTION_SEND_MULTIPLE);
            s.putParcelableArrayListExtra(Intent.EXTRA_STREAM, new ArrayList<>(lastSaved));
        }
        s.setType("image/png");
        ClipData cd = ClipData.newRawUri("sketch", lastSaved.get(0));
        for (int i = 1; i < lastSaved.size(); i++) cd.addItem(new ClipData.Item(lastSaved.get(i)));
        s.setClipData(cd);
        s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(s, "스케치 공유"));
    }

    // ------------------------------------------------------------------ helpers
    private void ui(final String msg, final int pct) {
        runOnUiThread(new Runnable() {
            public void run() { status.setText(msg); progress.setProgress(pct); }
        });
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
