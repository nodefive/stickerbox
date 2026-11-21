package com.unl0cker.stickerbox;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * The sticker canvas. Everything is kept in "output space", the 512 x 512 sticker, and drawn
 * scaled into a square frame on screen.
 *
 *  - image:   source bitmap placed by matrix m (source px -> output px), moved with one finger,
 *             zoomed and rotated with two
 *  - cut:     none (square), circle, or a freehand outline kept in source space so it follows
 *             the image
 *  - outline: a solid border around whatever is left after the cut
 *  - text:    one caption, dragged and pinched in the Text tool
 */
public final class EditorView extends View {
    static final int SIZE = Images.SIZE;
    static final int TOOL_MOVE = 0, TOOL_CUT = 1, TOOL_TEXT = 2, TOOL_OUTLINE = 3;
    static final int CUT_NONE = 0, CUT_CIRCLE = 1, CUT_FREE = 2;

    interface Listener {
        void onTextTap(boolean hasText);

        void onChanged();
    }

    static final class Caption {
        String text;
        int color = Color.WHITE;
        float size = 72;
        float cx = SIZE / 2f;
        float cy = SIZE * 0.84f;
    }

    private Bitmap src;
    private final Matrix m = new Matrix();
    private int tool = TOOL_MOVE;
    private int cut = CUT_NONE;
    private boolean drawCut;
    private Path freePath;
    private int outline;
    private int outlineColor = Color.WHITE;
    private Caption caption;
    private Listener listener;
    private boolean changed;

    private final RectF frame = new RectF();
    private float k = 1;
    private Bitmap composite;
    private boolean dirty = true;
    private boolean interacting;

    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint ghostPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint checker = new Paint();
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pathPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pathShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // gesture state
    private float lastFx, lastFy, lastSpan, lastAngle;
    private int lastCount;
    private float downX, downY;
    private long downTime;
    private boolean movedFar;
    private Path drawing;
    private Path drawingSrc;
    private int drawPoints;
    private float lastSx, lastSy;

    public EditorView(Context c, AttributeSet a) {
        super(c, a);
        int sq = Ui.dp(c, 10);
        Bitmap tile = Bitmap.createBitmap(sq * 2, sq * 2, Bitmap.Config.ARGB_8888);
        Canvas tc = new Canvas(tile);
        tc.drawColor(c.getColor(R.color.checker_a));
        Paint p = new Paint();
        p.setColor(c.getColor(R.color.checker_b));
        tc.drawRect(0, 0, sq, sq, p);
        tc.drawRect(sq, sq, sq * 2, sq * 2, p);
        checker.setShader(new BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(Ui.dp(c, 1));
        border.setColor(c.getColor(R.color.divider));
        ghostPaint.setAlpha(70);
        pathPaint.setStyle(Paint.Style.STROKE);
        pathPaint.setStrokeWidth(Ui.dp(c, 2.5f));
        pathPaint.setColor(c.getColor(R.color.button));
        pathPaint.setStrokeJoin(Paint.Join.ROUND);
        pathPaint.setStrokeCap(Paint.Cap.ROUND);
        pathShadow.set(pathPaint);
        pathShadow.setStrokeWidth(Ui.dp(c, 5f));
        pathShadow.setColor(0x99FFFFFF);
        textFill.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textFill.setTextAlign(Paint.Align.CENTER);
        textStroke.set(textFill);
        textStroke.setStyle(Paint.Style.STROKE);
        textStroke.setStrokeJoin(Paint.Join.ROUND);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(Ui.dp(c, 1.5f));
        boxPaint.setColor(c.getColor(R.color.button));
        boxPaint.setPathEffect(new DashPathEffect(new float[]{Ui.dp(c, 6), Ui.dp(c, 4)}, 0));
        setLayerType(LAYER_TYPE_SOFTWARE, null); // clipPath with anti-aliasing for the live preview
    }

    void setListener(Listener l) { listener = l; }

    /** exact: an existing 512 px sticker, placed 1:1; otherwise fitted with a small margin. */
    void setSource(Bitmap b, boolean exact) {
        src = b;
        m.reset();
        if (exact) {
            m.setScale(SIZE / (float) b.getWidth(), SIZE / (float) b.getHeight());
        } else {
            fitImage(false);
        }
        changed = !exact;
        markDirty();
    }

    boolean hasImage() { return src != null; }

    boolean isChanged() { return changed; }

    int tool() { return tool; }

    void setTool(int t) {
        tool = t;
        invalidate();
    }

    int cut() { return cut; }

    void setCut(int c) {
        if (c == CUT_FREE) {
            drawCut = true; // wait for the user to draw; keep the current cut until then
        } else {
            drawCut = false;
            cut = c;
            if (c == CUT_NONE) freePath = null;
            changed = true;
            markDirty();
        }
    }

    boolean waitingForDrawing() { return drawCut; }

    int outline() { return outline; }

    int outlineColor() { return outlineColor; }

    void setOutline(int px, int color) {
        outline = px;
        outlineColor = color;
        if (cut == CUT_FREE) fitToCut();
        changed = true;
        markDirty();
    }

    Caption caption() { return caption; }

    void setCaption(Caption c) {
        caption = c;
        changed = true;
        markDirty();
    }

    void captionChanged() {
        changed = true;
        markDirty();
    }

    void rotate90() {
        m.postRotate(90, SIZE / 2f, SIZE / 2f);
        changed = true;
        markDirty();
    }

    /** Fits the image (fill=false: whole image visible; fill=true: covers the square). */
    void fitImage(boolean fill) {
        if (src == null) return;
        RectF b = new RectF(0, 0, src.getWidth(), src.getHeight());
        if (cut == CUT_FREE && freePath != null && !fill) {
            fitToCut();
            return;
        }
        m.reset();
        float margin = fill ? 0 : 8 + outline;
        float avail = SIZE - 2 * margin;
        float s = fill ? Math.max(SIZE / b.width(), SIZE / b.height()) : Math.min(avail / b.width(), avail / b.height());
        m.setScale(s, s);
        m.postTranslate((SIZE - b.width() * s) / 2f, (SIZE - b.height() * s) / 2f);
        changed = true;
        markDirty();
    }

    /** Scales and centres so the freehand cut fills the sticker, leaving room for the outline. */
    private void fitToCut() {
        if (freePath == null) return;
        Path p = new Path(freePath);
        p.transform(m);
        RectF b = new RectF();
        p.computeBounds(b, true);
        if (b.width() < 1 || b.height() < 1) return;
        float margin = 10 + outline;
        float s = (SIZE - 2 * margin) / Math.max(b.width(), b.height());
        m.postTranslate(-b.centerX(), -b.centerY());
        m.postScale(s, s);
        m.postTranslate(SIZE / 2f, SIZE / 2f);
        markDirty();
    }

    private void markDirty() {
        dirty = true;
        invalidate();
        if (listener != null) listener.onChanged();
    }

    // ---- rendering ----

    private Path cutPath() {
        if (cut == CUT_CIRCLE) {
            Path p = new Path();
            p.addCircle(SIZE / 2f, SIZE / 2f, SIZE / 2f - outline - 2, Path.Direction.CW);
            return p;
        }
        if (cut == CUT_FREE && freePath != null) {
            Path p = new Path(freePath);
            p.transform(m);
            return p;
        }
        return null;
    }

    /** The finished 512 x 512 sticker. */
    Bitmap render() {
        Bitmap layer = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(layer);
        if (src != null) c.drawBitmap(src, m, bitmapPaint);
        Path cp = cutPath();
        if (cp != null) {
            Bitmap mask = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
            Paint mp = new Paint(Paint.ANTI_ALIAS_FLAG);
            mp.setColor(Color.BLACK);
            new Canvas(mask).drawPath(cp, mp);
            Paint dstIn = new Paint();
            dstIn.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
            c.drawBitmap(mask, 0, 0, dstIn);
        }
        Bitmap out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        Canvas oc = new Canvas(out);
        if (outline > 0) {
            Bitmap alpha = layer.extractAlpha();
            Paint op = new Paint();
            op.setColor(outlineColor);
            int steps = 32;
            for (float r : new float[]{outline, outline * 0.66f, outline * 0.33f}) {
                for (int i = 0; i < steps; i++) {
                    double a = 2 * Math.PI * i / steps;
                    oc.drawBitmap(alpha, (float) (Math.cos(a) * r), (float) (Math.sin(a) * r), op);
                }
            }
        }
        oc.drawBitmap(layer, 0, 0, null);
        drawCaption(oc);
        return out;
    }

    private void drawCaption(Canvas c) {
        if (caption == null || caption.text == null || caption.text.isEmpty()) return;
        textFill.setTextSize(caption.size);
        textFill.setColor(caption.color);
        textStroke.setTextSize(caption.size);
        textStroke.setStrokeWidth(caption.size * 0.18f);
        textStroke.setColor(isLight(caption.color) ? Color.BLACK : Color.WHITE);
        String[] lines = caption.text.split("\n");
        float lh = caption.size * 1.12f;
        float top = caption.cy - lh * lines.length / 2f;
        Paint.FontMetrics fm = textFill.getFontMetrics();
        for (int i = 0; i < lines.length; i++) {
            float baseline = top + lh * i + (lh - (fm.descent - fm.ascent)) / 2f - fm.ascent;
            c.drawText(lines[i], caption.cx, baseline, textStroke);
            c.drawText(lines[i], caption.cx, baseline, textFill);
        }
    }

    private RectF captionBounds() {
        if (caption == null || caption.text == null || caption.text.isEmpty()) return null;
        textFill.setTextSize(caption.size);
        String[] lines = caption.text.split("\n");
        float w = 0;
        for (String l : lines) w = Math.max(w, textFill.measureText(l));
        float h = caption.size * 1.12f * lines.length;
        float pad = caption.size * 0.2f;
        return new RectF(caption.cx - w / 2 - pad, caption.cy - h / 2 - pad, caption.cx + w / 2 + pad, caption.cy + h / 2 + pad);
    }

    private static boolean isLight(int color) {
        return (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000 > 140;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float pad = Ui.dp(getContext(), 20);
        float side = Math.min(w, h) - 2 * pad;
        frame.set((w - side) / 2f, (h - side) / 2f, (w + side) / 2f, (h + side) / 2f);
        k = side / SIZE;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (frame.isEmpty()) return;
        float r = Ui.dp(getContext(), 12);
        canvas.drawRoundRect(frame, r, r, checker);
        if (src == null) return;

        if (interacting && tool != TOOL_TEXT && drawing == null) {
            // Show the whole image faintly while moving, so its position outside the frame is visible.
            canvas.save();
            canvas.translate(frame.left, frame.top);
            canvas.scale(k, k);
            canvas.drawBitmap(src, m, ghostPaint);
            canvas.restore();
        }

        canvas.save();
        Path clip = new Path();
        clip.addRoundRect(frame, r, r, Path.Direction.CW);
        canvas.clipPath(clip);
        canvas.translate(frame.left, frame.top);
        canvas.scale(k, k);
        if (interacting) {
            // Fast live preview without the outline.
            canvas.save();
            Path cp = drawing == null ? cutPath() : null;
            if (cp != null) canvas.clipPath(cp);
            canvas.drawBitmap(src, m, bitmapPaint);
            canvas.restore();
            drawCaption(canvas);
        } else {
            if (dirty || composite == null) {
                composite = render();
                dirty = false;
            }
            canvas.drawBitmap(composite, 0, 0, bitmapPaint);
        }
        canvas.restore();
        canvas.drawRoundRect(frame, r, r, border);

        if (drawing != null) {
            canvas.drawPath(drawing, pathShadow);
            canvas.drawPath(drawing, pathPaint);
        }
        if (tool == TOOL_TEXT) {
            RectF b = captionBounds();
            if (b != null) {
                canvas.save();
                canvas.translate(frame.left, frame.top);
                canvas.scale(k, k);
                boxPaint.setStrokeWidth(Ui.dp(getContext(), 1.5f) / k);
                canvas.drawRoundRect(b, 8 / k, 8 / k, boxPaint);
                canvas.restore();
            }
        }
    }

    // ---- touch ----

    private float ox(float x) { return (x - frame.left) / k; }

    private float oy(float y) { return (y - frame.top) / k; }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (src == null) return false;
        int action = e.getActionMasked();
        int count = e.getPointerCount();
        if (action == MotionEvent.ACTION_POINTER_UP) count--;

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                interacting = true;
                downX = e.getX();
                downY = e.getY();
                downTime = e.getEventTime();
                movedFar = false;
                if (tool == TOOL_CUT && drawCut) startDrawing(e.getX(), e.getY());
                reference(e, -1);
                invalidate();
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                drawing = null; // two fingers: transform instead of drawing
                drawingSrc = null;
                reference(e, -1);
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                reference(e, e.getActionIndex());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.hypot(e.getX() - downX, e.getY() - downY) > Ui.dp(getContext(), 8)) movedFar = true;
                if (drawing != null && count == 1) {
                    continueDrawing(e.getX(), e.getY());
                } else {
                    transform(e, count);
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                interacting = false;
                boolean tap = action == MotionEvent.ACTION_UP && !movedFar && e.getEventTime() - downTime < 300;
                if (drawing != null) finishDrawing();
                else if (tap && tool == TOOL_TEXT && listener != null) {
                    RectF b = captionBounds();
                    if (b == null) listener.onTextTap(false);
                    else if (b.contains(ox(e.getX()), oy(e.getY()))) listener.onTextTap(true);
                } else if (movedFar) {
                    changed = true;
                }
                markDirty();
                return true;
            default:
                return true;
        }
    }

    /** Re-reads the reference positions, skipping the pointer that is leaving. */
    private void reference(MotionEvent e, int skip) {
        float sx = 0, sy = 0;
        int n = 0;
        float[] xs = new float[2], ys = new float[2];
        for (int i = 0; i < e.getPointerCount(); i++) {
            if (i == skip) continue;
            if (n < 2) {
                xs[n] = e.getX(i);
                ys[n] = e.getY(i);
            }
            sx += e.getX(i);
            sy += e.getY(i);
            n++;
        }
        if (n == 0) return;
        lastFx = sx / n;
        lastFy = sy / n;
        lastCount = n;
        if (n >= 2) {
            lastSpan = (float) Math.hypot(xs[1] - xs[0], ys[1] - ys[0]);
            lastAngle = (float) Math.atan2(ys[1] - ys[0], xs[1] - xs[0]);
        }
    }

    private void transform(MotionEvent e, int count) {
        if (count != lastCount) {
            reference(e, -1);
            return;
        }
        float fx = 0, fy = 0;
        for (int i = 0; i < e.getPointerCount(); i++) {
            fx += e.getX(i);
            fy += e.getY(i);
        }
        fx /= e.getPointerCount();
        fy /= e.getPointerCount();
        float dx = (fx - lastFx) / k;
        float dy = (fy - lastFy) / k;
        float scale = 1;
        float rot = 0;
        if (count >= 2) {
            float span = (float) Math.hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0));
            float angle = (float) Math.atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0));
            if (lastSpan > 0) scale = span / lastSpan;
            rot = (float) Math.toDegrees(angle - lastAngle);
            lastSpan = span;
            lastAngle = angle;
        }
        lastFx = fx;
        lastFy = fy;
        float px = ox(fx), py = oy(fy);
        if (tool == TOOL_TEXT) {
            if (caption == null) return;
            caption.cx += dx;
            caption.cy += dy;
            caption.size = Math.max(16, Math.min(260, caption.size * scale));
        } else {
            m.postTranslate(dx, dy);
            if (count >= 2) {
                m.postScale(scale, scale, px, py);
                m.postRotate(rot, px, py);
            }
        }
    }

    private void startDrawing(float x, float y) {
        drawing = new Path();
        drawing.moveTo(x, y);
        drawingSrc = new Path();
        float[] s = toSource(x, y);
        drawingSrc.moveTo(s[0], s[1]);
        lastSx = x;
        lastSy = y;
        drawPoints = 1;
    }

    private void continueDrawing(float x, float y) {
        float mx = (x + lastSx) / 2f, my = (y + lastSy) / 2f;
        drawing.quadTo(lastSx, lastSy, mx, my);
        float[] c = toSource(lastSx, lastSy);
        float[] mid = toSource(mx, my);
        drawingSrc.quadTo(c[0], c[1], mid[0], mid[1]);
        lastSx = x;
        lastSy = y;
        drawPoints++;
    }

    private void finishDrawing() {
        Path srcPath = drawingSrc;
        drawing = null;
        drawingSrc = null;
        if (srcPath == null || drawPoints < 8) return;
        srcPath.close();
        Path check = new Path(srcPath);
        check.transform(m);
        RectF b = new RectF();
        check.computeBounds(b, true);
        if (b.width() < 24 || b.height() < 24) return;
        freePath = srcPath;
        cut = CUT_FREE;
        drawCut = false;
        changed = true;
        fitToCut();
    }

    private float[] toSource(float x, float y) {
        Matrix inv = new Matrix();
        m.invert(inv);
        float[] p = {ox(x), oy(y)};
        inv.mapPoints(p);
        return p;
    }
}
