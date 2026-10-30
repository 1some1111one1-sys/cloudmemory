package com.cloudbridge.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;
import com.cloudbridge.app.core.FileKind;

/**
 * The big icon of one file or folder: a yellow folder, a coloured document with its type written on it, or - for
 * pictures and videos - the picture itself, so it can be recognised at a glance.
 */
final class FileIconView extends View {
    private FileKind kind = FileKind.OTHER;
    private Bitmap thumb;
    private boolean cloudBadge;
    private boolean selecting;
    private boolean checked;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Rect src = new Rect();

    FileIconView(Context c) {
        super(c);
    }

    void set(FileKind k, Bitmap preview, boolean cloud) {
        kind = k;
        thumb = preview;
        cloudBadge = cloud;
        invalidate();
    }

    void setThumb(Bitmap b) {
        thumb = b;
        invalidate();
    }

    void setSelection(boolean selectionMode, boolean isChecked) {
        selecting = selectionMode;
        checked = isChecked;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if (thumb != null && kind.hasPreview()) {
            drawPicture(c, w, h);
        } else if (kind == FileKind.FOLDER) {
            drawFolder(c, w, h);
        } else {
            drawDocument(c, w, h);
        }
        if (selecting) drawCheck(c, w, h);
    }

    private void drawPicture(Canvas c, float w, float h) {
        float pad = w * 0.06f;
        box.set(pad, pad, w - pad, h - pad);
        int bw = thumb.getWidth(), bh = thumb.getHeight();
        // Centre-crop the picture into the square.
        int side = Math.min(bw, bh);
        src.set((bw - side) / 2, (bh - side) / 2, (bw + side) / 2, (bh + side) / 2);
        c.save();
        Path clip = new Path();
        clip.addRoundRect(box, w * 0.1f, w * 0.1f, Path.Direction.CW);
        c.clipPath(clip);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF);
        c.drawBitmap(thumb, src, box, paint);
        c.restore();
        if (kind == FileKind.VIDEO) drawPlay(c, w, h);
    }

    private void drawPlay(Canvas c, float w, float h) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x88000000);
        c.drawCircle(w / 2, h / 2, w * 0.17f, paint);
        paint.setColor(0xFFFFFFFF);
        IconButton.draw(c, paint, IconButton.Glyph.PLAY, w / 2, h / 2, w * 0.09f);
    }

    private void drawFolder(Canvas c, float w, float h) {
        float l = w * 0.1f, r = w * 0.9f, t = h * 0.24f, b = h * 0.8f, rad = w * 0.07f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFB300);
        box.set(l, t - h * 0.07f, l + w * 0.42f, t + h * 0.14f);
        c.drawRoundRect(box, rad, rad, paint);
        box.set(l, t, r, b);
        c.drawRoundRect(box, rad, rad, paint);
        paint.setColor(0xFFFFD54F);
        box.set(l, t + h * 0.1f, r, b);
        c.drawRoundRect(box, rad, rad, paint);
        if (cloudBadge) {
            paint.setColor(0xFFFFFFFF);
            IconButton.draw(c, paint, IconButton.Glyph.CLOUD, w / 2, t + (b - t) * 0.58f, w * 0.17f);
        }
    }

    private void drawDocument(Canvas c, float w, float h) {
        float l = w * 0.2f, r = w * 0.8f, t = h * 0.1f, b = h * 0.9f, fold = w * 0.2f;
        int color = kind.color;
        Path body = new Path();
        body.moveTo(l, t);
        body.lineTo(r - fold, t);
        body.lineTo(r, t + fold);
        body.lineTo(r, b);
        body.lineTo(l, b);
        body.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        c.drawPath(body, paint);
        Path corner = new Path();
        corner.moveTo(r - fold, t);
        corner.lineTo(r - fold, t + fold);
        corner.lineTo(r, t + fold);
        corner.close();
        paint.setColor(0x66FFFFFF);
        c.drawPath(corner, paint);
        paint.setColor(0xFFFFFFFF);
        if (!kind.label.isEmpty()) {
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextAlign(Paint.Align.CENTER);
            float size = w * (kind.label.length() > 3 ? 0.15f : 0.19f);
            paint.setTextSize(size);
            c.drawText(kind.label, w / 2, h * 0.66f + size * 0.35f, paint);
        } else {
            paint.setStrokeWidth(Math.max(2f, w * 0.035f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            for (int i = 0; i < 3; i++) {
                float y = h * (0.5f + i * 0.12f);
                c.drawLine(l + w * 0.1f, y, r - w * 0.1f - (i == 2 ? w * 0.15f : 0), y, paint);
            }
        }
    }

    private void drawCheck(Canvas c, float w, float h) {
        float cx = w * 0.84f, cy = h * 0.16f, rad = w * 0.11f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(checked ? Ui.ACCENT : 0xCCFFFFFF);
        c.drawCircle(cx, cy, rad, paint);
        if (checked) {
            paint.setColor(0xFFFFFFFF);
            IconButton.draw(c, paint, IconButton.Glyph.CHECK, cx, cy, rad * 0.6f);
        } else {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, w * 0.02f));
            paint.setColor(0xFF9AA0A6);
            c.drawCircle(cx, cy, rad, paint);
        }
    }
}
