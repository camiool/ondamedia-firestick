package cl.ondamedia.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/** Puntero dibujado sobre la página, movido con las flechas del control remoto. */
public class CursorView extends View {

    private static final long HIDE_AFTER_MS = 5000;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float radius;
    private float x, y;
    private boolean visible = true;
    private boolean pressed = false;

    private final Runnable autoHide = () -> {
        visible = false;
        invalidate();
    };
    private final Runnable release = () -> {
        pressed = false;
        invalidate();
    };

    public CursorView(Context ctx) {
        super(ctx);
        float d = ctx.getResources().getDisplayMetrics().density;
        radius = 11 * d;
        fill.setColor(Color.argb(170, 255, 255, 255));
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2.5f * d);
        stroke.setColor(Color.argb(230, 0, 0, 0));
        setFocusable(false);
        setClickable(false);
    }

    public float getCursorX() { return x; }
    public float getCursorY() { return y; }

    public void center() {
        moveTo(getWidth() / 2f, getHeight() / 2f);
    }

    public void moveTo(float nx, float ny) {
        x = nx;
        y = ny;
        showNow();
    }

    public void showNow() {
        visible = true;
        removeCallbacks(autoHide);
        postDelayed(autoHide, HIDE_AFTER_MS);
        invalidate();
    }

    public void hideNow() {
        removeCallbacks(autoHide);
        visible = false;
        invalidate();
    }

    public void pulse() {
        pressed = true;
        showNow();
        removeCallbacks(release);
        postDelayed(release, 150);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (!visible) return;
        float r = pressed ? radius * 0.7f : radius;
        c.drawCircle(x, y, r, fill);
        c.drawCircle(x, y, r, stroke);
    }
}
