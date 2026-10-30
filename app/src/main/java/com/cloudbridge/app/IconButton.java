package com.cloudbridge.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** A line icon drawn with the canvas, so the app needs no image files. */
final class IconButton extends View {
    enum Glyph {
        MENU, BACK, CLOSE, SEARCH, SORT, MORE, NEW_FOLDER, FOLDER, COPY, MOVE, DOWNLOAD, DELETE, RENAME,
        SELECT_ALL, CHECK, CLOUD, HOME, PLAY, PLUS, REFRESH
    }

    private Glyph glyph;
    private int color = Ui.ICON;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    IconButton(Context c, Glyph g) {
        super(c);
        glyph = g;
        setClickable(true);
    }

    void setGlyph(Glyph g) {
        glyph = g;
        invalidate();
    }

    void setColor(int c) {
        color = c;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float s = Math.min(getWidth(), getHeight()) * 0.22f;
        paint.setColor(color);
        draw(canvas, paint, glyph, getWidth() / 2f, getHeight() / 2f, s);
    }

    /** Draws {@code g} centred on (cx, cy) in a box of half-size s. The paint's colour is used; width is set here. */
    static void draw(Canvas c, Paint p, Glyph g, float cx, float cy, float s) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(2f, s * 0.2f));
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        c.save();
        c.translate(cx, cy);
        switch (g) {
            case MENU:
                for (int i = -1; i <= 1; i++) c.drawLine(-s, i * s * 0.7f, s, i * s * 0.7f, p);
                break;
            case BACK:
                c.drawLine(-s, 0, s, 0, p);
                arrowHead(c, p, -s, 0, s * 0.9f, true);
                break;
            case CLOSE:
                c.drawLine(-s * 0.8f, -s * 0.8f, s * 0.8f, s * 0.8f, p);
                c.drawLine(-s * 0.8f, s * 0.8f, s * 0.8f, -s * 0.8f, p);
                break;
            case SEARCH:
                c.drawCircle(-s * 0.15f, -s * 0.15f, s * 0.65f, p);
                c.drawLine(s * 0.35f, s * 0.35f, s, s, p);
                break;
            case SORT:
                c.drawLine(-s, -s * 0.7f, s, -s * 0.7f, p);
                c.drawLine(-s, 0, s * 0.4f, 0, p);
                c.drawLine(-s, s * 0.7f, -s * 0.2f, s * 0.7f, p);
                break;
            case MORE:
                fill(p);
                for (int i = -1; i <= 1; i++) c.drawCircle(0, i * s * 0.7f, s * 0.16f, p);
                break;
            case NEW_FOLDER:
                c.drawPath(folderPath(s), p);
                c.drawLine(-s * 0.4f, s * 0.15f, s * 0.4f, s * 0.15f, p);
                c.drawLine(0, -s * 0.25f, 0, s * 0.55f, p);
                break;
            case FOLDER:
                c.drawPath(folderPath(s), p);
                break;
            case COPY: {
                RectF back = new RectF(-s * 0.15f, -s, s, s * 0.6f);
                c.drawRoundRect(back, s * 0.2f, s * 0.2f, p);
                RectF front = new RectF(-s, -s * 0.4f, s * 0.5f, s);
                int old = p.getColor();
                fill(p);
                p.setColor(0xFFFFFFFF);
                c.drawRoundRect(front, s * 0.2f, s * 0.2f, p);
                p.setStyle(Paint.Style.STROKE);
                p.setColor(old);
                c.drawRoundRect(front, s * 0.2f, s * 0.2f, p);
                break;
            }
            case MOVE:
                c.drawLine(-s, 0, s * 0.7f, 0, p);
                arrowHead(c, p, s * 0.7f, 0, s * 0.6f, false);
                c.drawLine(s, -s * 0.8f, s, s * 0.8f, p);
                break;
            case DOWNLOAD:
                c.drawLine(0, -s, 0, s * 0.5f, p);
                c.drawLine(-s * 0.6f, -s * 0.1f, 0, s * 0.55f, p);
                c.drawLine(s * 0.6f, -s * 0.1f, 0, s * 0.55f, p);
                c.drawLine(-s, s * 0.6f, -s, s, p);
                c.drawLine(-s, s, s, s, p);
                c.drawLine(s, s, s, s * 0.6f, p);
                break;
            case DELETE: {
                c.drawLine(-s, -s * 0.6f, s, -s * 0.6f, p);
                Path handle = new Path();
                handle.moveTo(-s * 0.35f, -s * 0.6f);
                handle.lineTo(-s * 0.35f, -s);
                handle.lineTo(s * 0.35f, -s);
                handle.lineTo(s * 0.35f, -s * 0.6f);
                c.drawPath(handle, p);
                Path body = new Path();
                body.moveTo(-s * 0.7f, -s * 0.4f);
                body.lineTo(-s * 0.55f, s);
                body.lineTo(s * 0.55f, s);
                body.lineTo(s * 0.7f, -s * 0.4f);
                c.drawPath(body, p);
                break;
            }
            case RENAME:
                c.drawLine(-s * 0.7f, s * 0.7f, s * 0.7f, -s * 0.7f, p);
                c.drawLine(-s, s, -s * 0.5f, s, p);
                c.drawLine(s * 0.4f, -s, s, -s * 0.4f, p);
                break;
            case SELECT_ALL:
                c.drawRoundRect(new RectF(-s, -s, s, s), s * 0.25f, s * 0.25f, p);
                check(c, p, s);
                break;
            case CHECK:
                check(c, p, s * 1.2f);
                break;
            case CLOUD:
                fill(p);
                c.drawCircle(-s * 0.45f, s * 0.15f, s * 0.5f, p);
                c.drawCircle(s * 0.05f, -s * 0.2f, s * 0.65f, p);
                c.drawCircle(s * 0.6f, s * 0.2f, s * 0.45f, p);
                c.drawRect(-s * 0.45f, s * 0.15f, s * 0.6f, s * 0.65f, p);
                break;
            case HOME: {
                Path roof = new Path();
                roof.moveTo(-s, 0);
                roof.lineTo(0, -s);
                roof.lineTo(s, 0);
                c.drawPath(roof, p);
                Path body = new Path();
                body.moveTo(-s * 0.7f, -s * 0.2f);
                body.lineTo(-s * 0.7f, s);
                body.lineTo(s * 0.7f, s);
                body.lineTo(s * 0.7f, -s * 0.2f);
                c.drawPath(body, p);
                c.drawLine(0, s, 0, s * 0.25f, p);
                break;
            }
            case PLAY: {
                fill(p);
                Path t = new Path();
                t.moveTo(-s * 0.55f, -s * 0.8f);
                t.lineTo(s * 0.85f, 0);
                t.lineTo(-s * 0.55f, s * 0.8f);
                t.close();
                c.drawPath(t, p);
                break;
            }
            case PLUS:
                c.drawLine(-s, 0, s, 0, p);
                c.drawLine(0, -s, 0, s, p);
                break;
            case REFRESH:
                c.drawArc(new RectF(-s * 0.8f, -s * 0.8f, s * 0.8f, s * 0.8f), -40, 270, false, p);
                c.drawLine(s * 0.6f, -s * 0.9f, s * 0.7f, -s * 0.3f, p);
                c.drawLine(s * 0.6f, -s * 0.9f, s * 0.05f, -s * 0.75f, p);
                break;
            default:
                break;
        }
        c.restore();
    }

    private static void fill(Paint p) {
        p.setStyle(Paint.Style.FILL);
    }

    private static void check(Canvas c, Paint p, float s) {
        Path path = new Path();
        path.moveTo(-s * 0.5f, 0);
        path.lineTo(-s * 0.1f, s * 0.4f);
        path.lineTo(s * 0.55f, -s * 0.4f);
        c.drawPath(path, p);
    }

    /** An arrow head at (x, y); pointing left when {@code left}, else right. */
    private static void arrowHead(Canvas c, Paint p, float x, float y, float size, boolean left) {
        float dx = left ? size * 0.8f : -size * 0.8f;
        c.drawLine(x, y, x + dx, y - size * 0.8f, p);
        c.drawLine(x, y, x + dx, y + size * 0.8f, p);
    }

    private static Path folderPath(float s) {
        Path f = new Path();
        f.moveTo(-s, s * 0.8f);
        f.lineTo(-s, -s * 0.8f);
        f.lineTo(-s * 0.2f, -s * 0.8f);
        f.lineTo(s * 0.1f, -s * 0.5f);
        f.lineTo(s, -s * 0.5f);
        f.lineTo(s, s * 0.8f);
        f.close();
        return f;
    }
}
