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
 * The liquid-glass clock, modelled on the iOS 26 Lock Screen glass clock. Each digit is a thick
 * glass slab with a rounded bevel. tools/glass_glyphs.py precomputed, per digit, its shape, a
 * refraction map (where in the wallpaper each glass pixel looks) and the light on the glass.
 * Once a minute this stacks them: faint shadow, the frosted wallpaper seen through the bent glass,
 * frosting (the "Glass strength" setting), then the highlights. Reference: tools/preview_glass.py.
 */
final class ClockRenderer {
    static final int CENTER_X = 960;

    private final AssetManager assets;
    private JSONObject meta;
    private int regionTop, regionH, baselineY;
    private float dispScale;
    private final Bitmap out;
    private Bitmap trimmed;
    private int left;
    private Bitmap pixelsOf;
    private int[] backPx;

    ClockRenderer(AssetManager assets) {
        this.assets = assets;
        try (InputStream is = assets.open("glyph/meta.json"); Scanner s = new Scanner(is, "UTF-8").useDelimiter("\\A")) {
            meta = new JSONObject(s.next());
            regionTop = meta.getJSONArray("clockRegion").getInt(1);
            regionH = meta.getJSONArray("clockRegion").getInt(3) - regionTop;
            baselineY = meta.getInt("baseline");
            dispScale = (float) meta.getDouble("dispScale");
        } catch (Exception e) {
            throw new RuntimeException("glyph/meta.json", e);
        }
        out = Bitmap.createBitmap(1920, regionH, Bitmap.Config.ARGB_8888);
    }

    int top() {
        return regionTop;
    }

    /** Where the last rendered bitmap starts, in design px. */
    int left() {
        return left;
    }

    /**
     * @param backdrop the frosted wallpaper behind the clock (1920 x region, from wall/<phase>_clock.jpg)
     * @param frost    0.06 clear .. 0.24 milky
     */
    Bitmap render(String text, int weight, Bitmap backdrop, float frost) {
        try {
            if (pixelsOf != backdrop) { // read the backdrop's pixels once per wallpaper
                backPx = new int[backdrop.getWidth() * backdrop.getHeight()];
                backdrop.getPixels(backPx, 0, backdrop.getWidth(), 0, 0, backdrop.getWidth(), backdrop.getHeight());
                pixelsOf = backdrop;
            }
            int bw = backdrop.getWidth(), bh = backdrop.getHeight();
            JSONObject w = meta.getJSONObject("weights").getJSONObject(String.valueOf(weight));
            JSONObject chars = w.getJSONObject("chars");
            int pad = meta.getInt("pad");
            float ls = (float) meta.getDouble("letterSpacing");
            int ascent = w.getInt("ascent");

            float total = 0;
            for (int i = 0; i < text.length(); i++) {
                total += (float) chars.getJSONObject(String.valueOf(text.charAt(i))).getDouble("advance");
                if (i > 0) total += ls;
            }
            int[] xs = new int[text.length()];
            float pen = CENTER_X - total / 2f;
            for (int i = 0; i < text.length(); i++) {
                xs[i] = Math.round(pen - pad);
                pen += (float) chars.getJSONObject(String.valueOf(text.charAt(i))).getDouble("advance") + ls;
            }
            int y = Math.round(baselineY - ascent - pad - regionTop); // in region coordinates

            out.eraseColor(0);
            Canvas co = new Canvas(out);
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            String dir = "glyph/w" + weight + "/";
            String[] files = new String[text.length()];
            for (int i = 0; i < text.length(); i++) {
                files[i] = dir + chars.getJSONObject(String.valueOf(text.charAt(i))).getString("file");
                drawAsset(co, files[i] + "_shadow.png", xs[i], y, p);
            }
            for (int i = 0; i < text.length(); i++) { // glass bodies
                Bitmap mask = decode(files[i] + "_mask.png"), disp = decode(files[i] + "_disp.png");
                int gw = mask.getWidth(), gh = mask.getHeight();
                int[] m = new int[gw * gh], d = new int[gw * gh], body = new int[gw * gh];
                mask.getPixels(m, 0, gw, 0, 0, gw, gh);
                disp.getPixels(d, 0, gw, 0, 0, gw, gh);
                mask.recycle();
                disp.recycle();
                for (int j = 0; j < gh; j++) {
                    float f = frost + 0.07f * (1 - j / (float) gh); // milkier toward the top
                    float keep = 1 - f, add = 255 * f;
                    int row = j * gw;
                    for (int k = 0; k < gw; k++) {
                        int a = m[row + k] >>> 24;
                        if (a == 0) continue;
                        int dv = d[row + k];
                        int sx = Math.round(xs[i] + k + (((dv >> 16) & 255) - 128) / dispScale);
                        int sy = Math.round(y + j + (((dv >> 8) & 255) - 128) / dispScale);
                        sx = sx < 0 ? 0 : sx >= bw ? bw - 1 : sx;
                        sy = sy < 0 ? 0 : sy >= bh ? bh - 1 : sy;
                        int c = backPx[sy * bw + sx];
                        int r = (int) (((c >> 16) & 255) * keep + add);
                        int g = (int) (((c >> 8) & 255) * keep + add);
                        int b = (int) ((c & 255) * keep + add);
                        body[row + k] = (a << 24) | (r << 16) | (g << 8) | b;
                    }
                }
                Bitmap bb = Bitmap.createBitmap(body, gw, gh, Bitmap.Config.ARGB_8888);
                co.drawBitmap(bb, xs[i], y, p);
                bb.recycle();
            }
            for (int i = 0; i < text.length(); i++) drawAsset(co, files[i] + "_over.png", xs[i], y, p);

            // Hand back only the part with digits in it: less for the GPU to blend every frame.
            JSONObject last = chars.getJSONObject(String.valueOf(text.charAt(text.length() - 1)));
            int l = Math.max(0, xs[0]);
            int rgt = Math.min(1920, xs[text.length() - 1] + last.getInt("w"));
            left = l;
            trimmed = Bitmap.createBitmap(out, l, 0, Math.max(1, rgt - l), regionH);
            return trimmed;
        } catch (Exception e) {
            out.eraseColor(0);
            left = 0;
            return out;
        }
    }

    private Bitmap decode(String path) throws Exception {
        try (InputStream is = assets.open(path)) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            o.inScaled = false;
            return BitmapFactory.decodeStream(is, null, o);
        }
    }

    private void drawAsset(Canvas c, String path, float x, float y, Paint p) throws Exception {
        Bitmap b = decode(path);
        c.drawBitmap(b, x, y, p);
        b.recycle();
    }
}
