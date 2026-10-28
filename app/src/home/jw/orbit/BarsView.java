package home.jw.orbit;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

/**
 * The little equalizer next to the song on the home screen. It's its own small view so it can animate
 * smoothly (~30 fps) without redrawing the whole home screen every frame. OrbitView moves and fades it.
 */
final class BarsView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    boolean playing;

    BarsView(Context c) {
        super(c);
        p.setColor(0xE6FFFFFF);
        setAlpha(0);
        setFocusable(false);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), bw = w / 5;
        long t = SystemClock.uptimeMillis();
        for (int i = 0; i < 3; i++) {
            // three bars bouncing at different speeds; resting low when paused
            float k = playing ? 0.3f + 0.7f * (0.5f + 0.5f * (float) Math.sin(t / (180.0 + i * 55) + i * 1.9)) : 0.35f;
            r.set(i * bw * 2, h - h * k, i * bw * 2 + bw, h);
            c.drawRoundRect(r, bw / 2, bw / 2, p);
        }
        if (playing && getAlpha() > 0.01f) postInvalidateDelayed(33);
    }
}
