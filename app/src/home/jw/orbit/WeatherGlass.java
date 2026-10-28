package home.jw.orbit;

import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;

import org.json.JSONObject;

import java.io.InputStream;
import java.util.Scanner;

/**
 * The weather corner in the same liquid glass as the clock: a glass icon and glass temperature digits
 * (tools/glass_glyphs.py makes them, glyph/wx/). The glass shows the blurred wallpaper behind it, bent
 * at the edges, frosted by the "Glass strength" setting. Drawn into one bitmap at the top-left corner.
 */
final class WeatherGlass {
    static final int X = 40, Y = 18, W = 620, H = 140;

    private final AssetManager assets;
    private JSONObject meta;
    private int pad;
    private float dispScale;
    private final Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
    private final Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
    private Bitmap pixelsOf;
    private int[] bp;
    private int bw, bh;
    private float scale; // design px per backdrop px

    WeatherGlass(AssetManager assets) {
        this.assets = assets;
        try (InputStream is = assets.open("glyph/wx/meta.json"); Scanner s = new Scanner(is, "UTF-8").useDelimiter("\\A")) {
            meta = new JSONObject(s.next());
            pad = meta.getInt("pad");
            dispScale = (float) meta.getDouble("dispScale");
        } catch (Exception e) {
            meta = null;
        }
    }

    /** Which glass icon fits the weather code (WMO codes, as Open-Meteo reports them). */
    static String icon(Glance.Weather w) {
        int c = w.code;
        if (c <= 1) return w.day ? "sun" : "moon";
        if (c == 2) return w.day ? "partly_day" : "partly_night";
        if (c == 45 || c == 48) return "fog";
        if (c >= 95) return "storm";
        if ((c >= 71 && c <= 77) || c == 85 || c == 86) return "snow";
        if (c >= 51) return "rain";
        return "cloud";
    }

    /**
     * @param temp     e.g. "56°" or "-3°"
     * @param backdrop the blurred wallpaper (wall/.../_blur.jpg, quarter size)
     * @return the bitmap to draw at (0, 0) in design px, or null if the glass assets are missing
     */
    Bitmap render(String temp, String icon, int weight, Bitmap backdrop, float frost) {
        if (meta == null || backdrop == null) return null;
        try {
            if (pixelsOf != backdrop) {
                bw = backdrop.getWidth();
                bh = backdrop.getHeight();
                bp = new int[bw * bh];
                backdrop.getPixels(bp, 0, bw, 0, 0, bw, bh);
                pixelsOf = backdrop;
                scale = 1920f / bw;
            }
            out.eraseColor(0);
            Canvas co = new Canvas(out);
            JSONObject ic = meta.getJSONObject("icons").getJSONObject(icon);
            piece(co, ic.getString("file"), X, Y, frost);
            float pen = X + ic.getInt("size") - pad + 14;
            JSONObject chars = meta.getJSONObject("weights").getJSONObject(String.valueOf(weight)).getJSONObject("chars");
            for (int i = 0; i < temp.length(); i++) {
                String ch = String.valueOf(temp.charAt(i));
                if (!chars.has(ch)) continue;
                JSONObject g = chars.getJSONObject(ch);
                piece(co, g.getString("file"), Math.round(pen - pad), Y, frost);
                pen += (float) g.getDouble("advance") + 2;
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private void piece(Canvas co, String base, int x, int y, float frost) throws Exception {
        String dir = "glyph/wx/" + base;
        Bitmap shadow = decode(dir + "_shadow.png");
        co.drawBitmap(shadow, x, y, p);
        shadow.recycle();

        Bitmap mask = decode(dir + "_mask.png"), disp = decode(dir + "_disp.png");
        int gw = mask.getWidth(), gh = mask.getHeight();
        int[] m = new int[gw * gh], d = new int[gw * gh], body = new int[gw * gh];
        mask.getPixels(m, 0, gw, 0, 0, gw, gh);
        disp.getPixels(d, 0, gw, 0, 0, gw, gh);
        mask.recycle();
        disp.recycle();
        for (int j = 0; j < gh; j++) {
            float f = frost + 0.07f * (1 - j / (float) gh); // milkier toward the top, like the clock
            float keep = (1 - f) * 1.08f, add = 255 * f;
            for (int k = 0; k < gw; k++) {
                int i = j * gw + k, a = m[i] >>> 24;
                if (a == 0) continue;
                int dv = d[i];
                // Where this bit of glass looks, in backdrop px (bilinear: the backdrop is quarter size).
                float sx = (x + k + (((dv >> 16) & 255) - 128) / dispScale) / scale - 0.5f;
                float sy = (y + j + (((dv >> 8) & 255) - 128) / dispScale) / scale - 0.5f;
                int c = sample(sx, sy);
                int r = Math.min(255, (int) (((c >> 16) & 255) * keep + add));
                int g = Math.min(255, (int) (((c >> 8) & 255) * keep + add));
                int b = Math.min(255, (int) ((c & 255) * keep + add));
                body[i] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
        Bitmap bb = Bitmap.createBitmap(body, gw, gh, Bitmap.Config.ARGB_8888);
        co.drawBitmap(bb, x, y, p);
        bb.recycle();

        Bitmap over = decode(dir + "_over.png");
        co.drawBitmap(over, x, y, p);
        over.recycle();
    }

    private int sample(float fx, float fy) {
        fx = Math.max(0, Math.min(bw - 1.001f, fx));
        fy = Math.max(0, Math.min(bh - 1.001f, fy));
        int x0 = (int) fx, y0 = (int) fy;
        float tx = fx - x0, ty = fy - y0;
        int c00 = bp[y0 * bw + x0], c10 = bp[y0 * bw + x0 + 1], c01 = bp[(y0 + 1) * bw + x0], c11 = bp[(y0 + 1) * bw + x0 + 1];
        int out = 0;
        for (int sh = 0; sh <= 16; sh += 8) {
            float top = ((c00 >> sh) & 255) * (1 - tx) + ((c10 >> sh) & 255) * tx;
            float bot = ((c01 >> sh) & 255) * (1 - tx) + ((c11 >> sh) & 255) * tx;
            out |= ((int) (top * (1 - ty) + bot * ty) & 255) << sh;
        }
        return out;
    }

    private Bitmap decode(String path) throws Exception {
        try (InputStream is = assets.open(path)) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            o.inScaled = false;
            return BitmapFactory.decodeStream(is, null, o);
        }
    }
}
