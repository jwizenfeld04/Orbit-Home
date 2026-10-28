package home.jw.orbit;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * NFL score alerts that pop up over any app (Netflix, YouTube TV, Spotify…): a small glass banner in the
 * top-right corner for your team's scores, kickoff, halftime and final, plus close finishes in any game.
 * Scores come from ESPN's public scoreboard (no account). Runs as a foreground service so Fire OS keeps it
 * alive while other apps are open; drawing over apps needs the "draw over other apps" permission, granted
 * once over ADB: adb shell appops set home.jw.orbit SYSTEM_ALERT_WINDOW allow
 */
public final class AlertService extends Service {
    static final String[] TEAM_IDS = {"off", "ARI", "ATL", "BAL", "BUF", "CAR", "CHI", "CIN", "CLE", "DAL", "DEN", "DET",
            "GB", "HOU", "IND", "JAX", "KC", "LV", "LAC", "LAR", "MIA", "MIN", "NE", "NO", "NYG", "NYJ", "PHI", "PIT",
            "SF", "SEA", "TB", "TEN", "WSH"};
    static final String[] TEAM_NAMES = {"Off", "Cardinals", "Falcons", "Ravens", "Bills", "Panthers", "Bears", "Bengals",
            "Browns", "Cowboys", "Broncos", "Lions", "Packers", "Texans", "Colts", "Jaguars", "Chiefs", "Raiders",
            "Chargers", "Rams", "Dolphins", "Vikings", "Patriots", "Saints", "Giants", "Jets", "Eagles", "Steelers",
            "49ers", "Seahawks", "Buccaneers", "Titans", "Commanders"};

    private static final String TAG = "OrbitAlerts";
    private static final String SCOREBOARD = "https://site.api.espn.com/apis/site/v2/sports/football/nfl/scoreboard";
    private static final String CHANNEL = "alerts";

    // ---- starting and stopping (from Orbit) ----

    static boolean wanted(Context c) {
        SharedPreferences s = c.getSharedPreferences("settings", MODE_PRIVATE);
        return !"off".equals(s.getString("alertTeam", "off")) || s.getBoolean("alertClose", false);
    }

    static boolean canShow(Context c) {
        return Settings.canDrawOverlays(c);
    }

    /** Start (or stop) the service to match the settings. */
    static void start(Context c) {
        Intent i = new Intent(c, AlertService.class);
        try {
            if (wanted(c)) c.startForegroundService(i);
            else c.stopService(i);
        } catch (Exception e) {
            Log.w(TAG, "start: " + e);
        }
    }

    static void test(Context c) {
        c.startForegroundService(new Intent(c, AlertService.class).setAction("test"));
    }

    /** After the TV restarts, start watching again (Orbit itself may not be opened for a while). */
    public static final class Boot extends android.content.BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            start(c);
        }
    }

    // ---- the service ----

    private HandlerThread thread;
    private Handler work;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Snap> seen = new HashMap<>();
    private final Map<String, Bitmap> logos = new HashMap<>();
    private final Runnable poll = this::poll;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Score alerts", NotificationManager.IMPORTANCE_MIN);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        Notification n = new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.star_on)
                .setContentTitle("Orbit score alerts").setContentText("Watching NFL scores").setOngoing(true).build();
        startForeground(1, n);
        thread = new HandlerThread("alerts");
        thread.start();
        work = new Handler(thread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "test".equals(intent.getAction())) work.post(this::testAlert);
        if (!wanted(this)) {
            if (intent == null || !"test".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
            main.postDelayed(() -> { if (!wanted(this)) stopSelf(); }, 15_000); // after the test banner
        }
        work.removeCallbacks(poll);
        work.post(poll);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        work.removeCallbacksAndMessages(null);
        thread.quitSafely();
        main.post(this::hideBanner);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---- watching the scoreboard ----

    /** What we last knew about a game. */
    private static final class Snap {
        int home, away, period;
        String state, status;
        boolean close, half, fin;
        long tdAt;      // when a touchdown was last seen (so the extra point that follows stays quiet)
        String tdBy = "";
    }

    /** One banner. */
    static final class Alert {
        String head, sub, detail, note;
        Side away, home;
        int accent = 0xFFFFFFFF;
    }

    static final class Side {
        String abbr, name, logo;
        int score;
        int color;
    }

    private void poll() {
        if (!wanted(this)) return;
        long next = 10 * 60_000L;
        try {
            SharedPreferences s = getSharedPreferences("settings", MODE_PRIVATE);
            String team = s.getString("alertTeam", "off");
            boolean closeOn = s.getBoolean("alertClose", false);
            JSONArray events = new JSONObject(Weather.get(SCOREBOARD)).getJSONArray("events");
            long now = System.currentTimeMillis();
            for (int i = 0; i < events.length(); i++) {
                JSONObject ev = events.getJSONObject(i);
                Side home = null, away = null;
                JSONObject comp = ev.getJSONArray("competitions").getJSONObject(0);
                JSONArray cs = comp.getJSONArray("competitors");
                for (int k = 0; k < cs.length(); k++) {
                    JSONObject co = cs.getJSONObject(k), t = co.getJSONObject("team");
                    Side sd = new Side();
                    sd.abbr = t.optString("abbreviation");
                    sd.name = t.optString("shortDisplayName", sd.abbr);
                    sd.logo = t.optString("logo");
                    sd.score = co.optInt("score", 0);
                    try { sd.color = 0xFF000000 | Integer.parseInt(t.optString("color", "888888"), 16); } catch (Exception e) { sd.color = 0xFF888888; }
                    if ("home".equals(co.optString("homeAway"))) home = sd; else away = sd;
                }
                if (home == null || away == null) continue;
                boolean mine = home.abbr.equals(team) || away.abbr.equals(team);
                if (!mine && !closeOn) continue;
                JSONObject st = ev.getJSONObject("status"), type = st.getJSONObject("type");
                String state = type.optString("state"), status = type.optString("name");
                int period = st.optInt("period");
                double clock = st.optDouble("clock", 0);
                String shortDetail = type.optString("shortDetail").replace(" - ", " · ");
                String lastPlay = comp.optJSONObject("situation") != null && comp.getJSONObject("situation").optJSONObject("lastPlay") != null
                        ? comp.getJSONObject("situation").getJSONObject("lastPlay").optString("text") : "";
                boolean closeNow = "in".equals(state) && Math.abs(home.score - away.score) <= 8
                        && (period >= 5 || (period == 4 && clock <= 300));

                if ("in".equals(state)) next = Math.min(next, 15_000L);
                else if ("pre".equals(state)) {
                    try {
                        long start = java.time.Instant.parse(ev.optString("date").replaceAll("T(\\d\\d:\\d\\d)Z$", "T$1:00Z")).toEpochMilli();
                        if (start - now < 20 * 60_000L) next = Math.min(next, 60_000L);
                        else next = Math.min(next, Math.max(60_000L, start - now - 15 * 60_000L));
                    } catch (Exception e) {
                        next = Math.min(next, 5 * 60_000L);
                    }
                }

                String id = ev.optString("id");
                Snap sn = seen.get(id);
                if (sn == null) { // first look: remember, don't replay what already happened
                    sn = new Snap();
                    sn.home = home.score; sn.away = away.score; sn.state = state; sn.status = status; sn.period = period;
                    sn.fin = "post".equals(state);
                    sn.half = period > 2 || "STATUS_HALFTIME".equals(status) || sn.fin;
                    seen.put(id, sn);
                    if (closeOn && closeNow) { sn.close = true; queue(alert("CLOSE GAME", null, away, home, shortDetail, null)); }
                    continue;
                }
                if (mine && "pre".equals(sn.state) && "in".equals(state))
                    queue(alert("KICKOFF", null, away, home, shortDetail, null));
                if (mine) {
                    scored(sn, away, sn.away, away, home, shortDetail, lastPlay);
                    scored(sn, home, sn.home, away, home, shortDetail, lastPlay);
                }
                if (mine && !sn.half && "STATUS_HALFTIME".equals(status)) {
                    sn.half = true;
                    queue(alert("HALFTIME", null, away, home, "Halftime", null));
                }
                if (closeOn && closeNow && !sn.close) {
                    sn.close = true;
                    queue(alert(period >= 5 ? "OVERTIME" : "CLOSE GAME", null, away, home, shortDetail, null));
                }
                if ("post".equals(state) && !sn.fin) {
                    sn.fin = true;
                    if (mine || sn.close) {
                        Side me = home.abbr.equals(team) ? home : away.abbr.equals(team) ? away : null;
                        Side win = home.score > away.score ? home : away.score > home.score ? away : null;
                        String sub = me == null ? (win == null ? "Tie" : win.name + " win")
                                : win == null ? "Tie" : (win == me ? me.name + " win" : me.name + " lose");
                        Alert a = alert("FINAL", sub, away, home, shortDetail, null);
                        if (me != null && win == me) a.accent = me.color;
                        queue(a);
                    }
                }
                sn.home = home.score; sn.away = away.score; sn.state = state; sn.status = status; sn.period = period;
            }
        } catch (Exception e) {
            Log.w(TAG, "poll: " + e);
            next = 60_000L;
        }
        work.postDelayed(poll, next);
    }

    /** A team's score went up: say what it was (by how many points). */
    private void scored(Snap sn, Side side, int before, Side away, Side home, String detail, String lastPlay) {
        int d = side.score - before;
        if (d <= 0) return;
        long now = SystemClock.elapsedRealtime();
        boolean afterTd = side.abbr.equals(sn.tdBy) && now - sn.tdAt < 5 * 60_000L;
        String head;
        if (d == 1) return;                          // extra point: the touchdown alert already went out
        else if (d >= 6 && d <= 8) head = "TOUCHDOWN";
        else if (d == 3) head = "FIELD GOAL";
        else if (d == 2) head = afterTd ? "2-PT CONVERSION" : "SAFETY";
        else head = "SCORE";
        if (head.equals("TOUCHDOWN")) { sn.tdBy = side.abbr; sn.tdAt = now; }
        String note = lastPlay != null && lastPlay.toUpperCase().contains(head.equals("2-PT CONVERSION") ? "TWO-POINT" : head) ? lastPlay : null;
        Alert a = alert(head, side.name, away, home, detail, note);
        a.accent = side.color;
        queue(a);
    }

    private Alert alert(String head, String sub, Side away, Side home, String detail, String note) {
        Alert a = new Alert();
        a.head = head; a.sub = sub; a.away = away; a.home = home; a.detail = detail; a.note = note;
        return a;
    }

    private void testAlert() {
        Side away = new Side(), home = new Side();
        away.abbr = "AWY"; away.name = "Away Team"; away.score = 21; away.color = 0xFF2E7DD7;
        away.logo = null;
        home.abbr = "HME"; home.name = "Home Team"; home.score = 17; home.color = 0xFFE0592A;
        home.logo = null;
        Alert a = alert("TOUCHDOWN", "Away Team", away, home, "4:12 · 3rd", "23-yard touchdown pass (test alert)");
        a.accent = away.color;
        logo(away);
        logo(home);
        main.post(() -> enqueue(a));
    }

    /** Fetch logos now (off the main thread), then show after the chosen delay so it doesn't spoil the TV. */
    private void queue(Alert a) {
        logo(a.away);
        logo(a.home);
        int delay = getSharedPreferences("settings", MODE_PRIVATE).getInt("alertDelay", 30);
        Log.i(TAG, "alert " + a.head + " " + a.away.abbr + " " + a.away.score + "-" + a.home.score + " " + a.home.abbr + " in " + delay + "s");
        main.postDelayed(() -> enqueue(a), delay * 1000L);
    }

    private void logo(Side s) {
        if (s == null || s.logo == null || s.logo.isEmpty() || logos.containsKey(s.abbr)) return;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(s.logo).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(10000);
            try (InputStream is = c.getInputStream()) {
                Bitmap b = BitmapFactory.decodeStream(is);
                if (b != null) logos.put(s.abbr, Bitmap.createScaledBitmap(b, 96, 96, true));
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            Log.w(TAG, "logo " + s.abbr + ": " + e);
        }
    }

    // ---- the banner (main thread) ----

    private final ArrayDeque<Alert> waiting = new ArrayDeque<>();
    private Banner banner;

    private void enqueue(Alert a) {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null && !pm.isInteractive()) return; // screen's off: nobody to tell
        if (!canShow(this)) { Log.w(TAG, "no overlay permission"); return; }
        waiting.add(a);
        if (banner == null) showNext();
    }

    private void showNext() {
        Alert a = waiting.poll();
        if (a == null) { hideBanner(); return; }
        if (banner == null) {
            float sc = getResources().getDisplayMetrics().widthPixels / 1920f;
            banner = new Banner(this, sc);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(Math.round(Banner.W * sc), Math.round(Banner.H * sc),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.END;
            lp.x = Math.round(40 * sc);
            lp.y = Math.round(36 * sc);
            try {
                getSystemService(WindowManager.class).addView(banner, lp);
            } catch (Exception e) {
                Log.w(TAG, "overlay: " + e);
                banner = null;
                return;
            }
        }
        banner.show(a, logos.get(a.away.abbr), logos.get(a.home.abbr), this::showNext);
    }

    private void hideBanner() {
        if (banner == null) return;
        try {
            getSystemService(WindowManager.class).removeView(banner);
        } catch (Exception ignored) {
        }
        banner = null;
    }

    /** The glass banner: headline, who scored, the score with both logos, the game clock, and the play. */
    static final class Banner extends View {
        static final int W = 660, H = 214;
        private static final long IN = 420, HOLD = 7500, OUT = 450;
        private final float sc;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG),
                bmp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Typeface t400, t500, t700;
        private final RectF r = new RectF();
        private Alert a;
        private Bitmap la, lh;
        private long at;
        private Runnable done;

        Banner(Context c, float sc) {
            super(c);
            this.sc = sc;
            t400 = font(c, 400);
            t500 = font(c, 500);
            t700 = font(c, 700);
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(1.5f);
        }

        private static Typeface font(Context c, int w) {
            return new Typeface.Builder(c.getAssets(), "fonts/InterTight-VF.ttf").setFontVariationSettings("'wght' " + w).build();
        }

        void show(Alert a, Bitmap la, Bitmap lh, Runnable done) {
            this.a = a; this.la = la; this.lh = lh; this.done = done;
            at = SystemClock.uptimeMillis();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (a == null) return;
            long t = SystemClock.uptimeMillis() - at;
            if (t > IN + HOLD + OUT) { a = null; post(done); return; }
            float p = t < IN ? ease(t / (float) IN) : t < IN + HOLD ? 1 : 1 - ease((t - IN - HOLD) / (float) OUT);
            if (t < IN || t > IN + HOLD) postInvalidateOnAnimation();
            else postInvalidateDelayed(IN + HOLD - t);
            c.save();
            c.scale(sc, sc);
            c.translate(0, -26 * (1 - p));
            float h = a.note != null ? H : H - 40;
            r.set(4, 4, W - 4, h - 4);
            // Dark glass (there's no wallpaper to blur over another app's video), a light rim on top.
            fill.setShader(new LinearGradient(0, 0, 0, h, Color.argb((int) (222 * p), 30, 34, 44), Color.argb((int) (222 * p), 14, 16, 22), Shader.TileMode.CLAMP));
            c.drawRoundRect(r, 34, 34, fill);
            fill.setShader(null);
            rim.setShader(new LinearGradient(0, 0, 0, 70, Color.argb((int) (150 * p), 255, 255, 255), Color.argb((int) (30 * p), 255, 255, 255), Shader.TileMode.CLAMP));
            c.drawRoundRect(r, 34, 34, rim);
            // accent bar in the scoring team's colour
            fill.setColor(lift(a.accent, p));
            r.set(30, 32, 36, 84);
            c.drawRoundRect(r, 3, 3, fill);

            text.setTypeface(t700);
            text.setTextSize(34);
            text.setLetterSpacing(0.06f);
            text.setColor(Color.argb((int) (255 * p), 255, 255, 255));
            c.drawText(a.head, 54, 68, text);
            float hx = 54 + text.measureText(a.head) + 16;
            text.setLetterSpacing(0);
            if (a.sub != null) {
                text.setTypeface(t500);
                text.setTextSize(30);
                text.setColor(Color.argb((int) (200 * p), 255, 255, 255));
                c.drawText(a.sub, hx, 67, text);
            }
            text.setTypeface(t400);
            text.setTextSize(22);
            text.setColor(Color.argb((int) (165 * p), 255, 255, 255));
            String d = a.detail == null ? "" : a.detail;
            c.drawText(d, W - 34 - text.measureText(d), 66, text);

            // score row: [logo] LAC 21     DEN 17 [logo]
            float y = 118, ls = 52;
            side(c, la, a.away, 34, y, ls, p, false);
            side(c, lh, a.home, W - 34, y, ls, p, true);
            text.setTypeface(t400);
            text.setTextSize(26);
            text.setColor(Color.argb((int) (120 * p), 255, 255, 255));
            text.setTextAlign(Paint.Align.CENTER);
            c.drawText("–", W / 2f, y + 9, text);
            text.setTextAlign(Paint.Align.LEFT);

            if (a.note != null) {
                text.setTypeface(t400);
                text.setTextSize(21);
                text.setColor(Color.argb((int) (170 * p), 255, 255, 255));
                c.drawText(fit(a.note, W - 68), 34, 184, text);
            }
            c.restore();
        }

        /** One team: logo, abbreviation and score, from the left edge or (mirrored) from the right. */
        private void side(Canvas c, Bitmap logo, Side s, float edge, float cy, float ls, float p, boolean right) {
            float x = right ? edge - ls : edge;
            if (logo != null) {
                bmp.setAlpha((int) (255 * p));
                r.set(x, cy - ls / 2, x + ls, cy + ls / 2);
                c.drawBitmap(logo, null, r, bmp);
            }
            text.setTypeface(t500);
            text.setTextSize(28);
            text.setColor(Color.argb((int) (190 * p), 255, 255, 255));
            String ab = s.abbr;
            float abw = text.measureText(ab);
            text.setTypeface(t700);
            text.setTextSize(42);
            String sc = String.valueOf(s.score);
            float scw = text.measureText(sc);
            float gap = 14;
            if (!right) {
                float tx = x + ls + gap;
                text.setTypeface(t500); text.setTextSize(28); text.setColor(Color.argb((int) (190 * p), 255, 255, 255));
                c.drawText(ab, tx, cy + 10, text);
                text.setTypeface(t700); text.setTextSize(42); text.setColor(Color.argb((int) (255 * p), 255, 255, 255));
                c.drawText(sc, tx + abw + gap, cy + 15, text);
            } else {
                float tx = x - gap;
                text.setTypeface(t500); text.setTextSize(28); text.setColor(Color.argb((int) (190 * p), 255, 255, 255));
                c.drawText(ab, tx - abw, cy + 10, text);
                text.setTypeface(t700); text.setTextSize(42); text.setColor(Color.argb((int) (255 * p), 255, 255, 255));
                c.drawText(sc, tx - abw - gap - scw, cy + 15, text);
            }
        }

        private String fit(String s, float max) {
            if (text.measureText(s) <= max) return s;
            int n = s.length();
            while (n > 1 && text.measureText(s, 0, n) + text.measureText("…") > max) n--;
            return s.substring(0, n) + "…";
        }

        /** Team colours can be very dark (navy, black): lighten them so the bar shows on dark glass. */
        private static int lift(int c, float p) {
            int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
            float lum = (0.3f * r + 0.59f * g + 0.11f * b) / 255f;
            if (lum < 0.35f) { float k = 0.45f; r += (int) ((255 - r) * k); g += (int) ((255 - g) * k); b += (int) ((255 - b) * k); }
            return Color.argb((int) (255 * p), r, g, b);
        }

        private static float ease(float x) {
            x = Math.max(0, Math.min(1, x));
            return 1 - (1 - x) * (1 - x) * (1 - x);
        }
    }
}
