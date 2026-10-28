package home.jw.orbit;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The whole home screen, drawn on one canvas in a 1920x1080 design space (same coordinates as the
 * mockup). The TV renders its UI at 1920x1080, so the scale is 1.
 *
 * Glass: panels use a pre-blurred copy of the wallpaper (BitmapShader), the clock uses pre-rendered
 * layers (ClockRenderer). Nothing is blurred live, so it stays smooth on the TV's GPU.
 */
final class OrbitView extends View {
    interface Host {
        void launchApp(Apps.App a);
        void appInfo(Apps.App a);
        void uninstall(Apps.App a);
        void launchGame(Glance.Game g);
        void openFLauncher();
        void settingsChanged();
        void keepAwake(boolean on);
        void configChanged();                 // server / location / units changed
        void testServer(String url, Weather.Got<String> cb);
        void askText(String title, String hint, String initial, Weather.Got<String> cb);
        void choose(String title, String[] items, Weather.Got<Integer> cb);
    }

    // ---- layout (design px, from the mockup) ----
    private static final int COLS = 5, TILE_W = 268, TILE_H = 151, GAP = 28, ROW = 183, VISIBLE_ROWS = 3;
    private static final int GRID_X = 234, GRID_Y = 760, SHELF_X = 206, SHELF_Y = 736, SHELF_W = 1508, SHELF_H = TILE_H + 48;
    private static final float LAYER_UP = 470, PARALLAX = 90, PEEK = 87;
    private static final int GAME = -1, GEAR = -2, ALSO = -3;
    private static final float GEAR_CX = 1812, GEAR_CY = 92, GEAR_R = 34;

    private final Host host;
    private final Apps apps;
    private final ClockRenderer clockRenderer;
    final SharedPreferences settings;

    // ---- paints & fonts ----
    private final Typeface t200, t300, t400, t500, t600;
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
            stroke = new Paint(Paint.ANTI_ALIAS_FLAG), bmp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG),
            glassBlur = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Matrix wallMatrix = new Matrix(), blurMatrix = new Matrix();
    private final RectF r = new RectF();
    private Bitmap glow, shadow;

    // ---- content ----
    private String phase, loadedPhase;
    private Bitmap wall, prevWall, blur, clockBackdrop, clock;
    private String clockText = "", lastClockKey = "", dateText = "";
    private int clockLeft;
    // Reused every frame (no allocation while animating).
    private final java.util.HashMap<Bitmap, BitmapShader> bannerShaders = new java.util.HashMap<>();
    private final Matrix tileMatrix = new Matrix(), pillMatrix = new Matrix(), rimMatrix = new Matrix(), bannerMatrix = new Matrix();
    private final LinearGradient sheenShader = new LinearGradient(0, 0, TILE_W * 0.35f, TILE_H, Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP);
    private final LinearGradient rimShader = new LinearGradient(0, 0, 0, 60, Color.argb(150, 255, 255, 255), Color.argb(60, 255, 255, 255), Shader.TileMode.CLAMP);
    private List<Apps.App> visible = new ArrayList<>();
    private Glance.Game game;
    private Glance.Game alsoGame;
    // Themes from assets/themes.json, then custom themes from the TV's storage (see loadCustomThemes).
    private final List<String> themeIds = new ArrayList<>(), themeNames = new ArrayList<>();
    private final List<Photo> photos = new ArrayList<>();
    private final java.util.HashMap<String, Photo> photoByKey = new java.util.HashMap<>();

    private static final class Photo {
        String key, phase, caption, credit; // key = "<theme>/<id>"
        String theme;
        java.io.File dir; // custom theme: its folder on the TV; null = built into the app
    }
    private String caption = "", credit = "";
    private Glance.Weather weather;
    private String wxTemp = "", wxLine = "", wxKey = "";
    private WeatherGlass wxGlass;
    private Bitmap wxBitmap;
    private String scoreKey = "";
    private float scoreFlash;        // 1 right after the score changes, fades out
    private final Path moonPath = new Path(), boltPath = new Path();

    // ---- idle mode: just the photo, clock, date, weather and a live game ----
    private boolean idle;
    private float idleAmt, idleDx, idleDy;
    private long lastInput = SystemClock.uptimeMillis(), idlePhotoAt;
    private String idlePhoto;
    private static final long IDLE_PHOTO_MS = 120_000;
    private final java.util.Random rng = new java.util.Random();
    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            heartbeat();
            postDelayed(this, 5000);
        }
    };

    // ---- state & animation (values ease toward targets every frame) ----
    private boolean expanded;
    private float e;          // 0 = home, 1 = all apps
    private float fade = 1;   // wallpaper crossfade
    private float[] focusAmt = new float[0];
    private float gameAmt, gearAmt, alsoAmt, musicAmt;
    NowPlaying nowPlaying;                 // set by the activity
    BarsView bars;                         // set by the activity: the animated equalizer next to the song
    private boolean barsPlaced;
    private boolean ppDown, ppLong;        // ⏯ held = idle mode
    private String heldPhoto, heldFor;     // photo kept after leaving idle, until the next scheduled change
    private Bitmap artFor, artPrev;          // current cover, and the one it's fading in over
    private long artAt;                       // when the cover changed
    private final android.graphics.Paint artPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG),
            artPrevPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
    private final Matrix artMatrix = new Matrix();
    private static final long ART_FADE_MS = 350;
    private int focus = 0;
    private long lastFrame;

    private boolean moving;
    private List<Apps.App> moveBackup;
    private boolean okDown, okLong;

    private Apps.App menuApp;
    private List<String> menuItems = new ArrayList<>();
    private int menuSel;

    private boolean settingsOpen;
    private int setSel, setScroll;
    private boolean resetArmed;

    private String toast;
    private long toastUntil;

    OrbitView(Context ctx, Host host, Apps apps) {
        super(ctx);
        this.host = host;
        this.apps = apps;
        this.settings = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
        clockRenderer = new ClockRenderer(ctx.getAssets());
        wxGlass = new WeatherGlass(ctx.getAssets());
        loadThemes();
        String[] tn = new String[themeIds.size() + 1];
        Object[] tv = new Object[themeIds.size() + 1];
        for (int i = 0; i < themeIds.size(); i++) { tn[i] = themeNames.get(i); tv[i] = themeIds.get(i); }
        tn[tn.length - 1] = "Shuffle themes";
        tv[tv.length - 1] = "shuffle";
        rowsDef[0].names = tn;
        rowsDef[0].values = tv;
        t200 = font(200); t300 = font(300); t400 = font(400); t500 = font(500); t600 = font(600);
        setFocusable(true);
        setFocusableInTouchMode(true);
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        makeSoftBitmaps();
        Path cut = new Path();
        moonPath.addCircle(0, 0, 22, Path.Direction.CW);
        cut.addCircle(10, -8, 19, Path.Direction.CW);
        moonPath.op(cut, Path.Op.DIFFERENCE);
        boltPath.moveTo(3, 0);
        boltPath.lineTo(-7, 15);
        boltPath.lineTo(0, 15);
        boltPath.lineTo(-4, 28);
        boltPath.lineTo(9, 10);
        boltPath.lineTo(2, 10);
        boltPath.lineTo(7, 0);
        boltPath.close();
        reloadApps();
    }

    // ================= public =================

    void reloadApps() {
        boolean showHidden = settings.getBoolean("showHidden", false);
        visible = apps.visible(showHidden);
        focusAmt = new float[visible.size()];
        if (focus >= visible.size()) focus = Math.max(0, visible.size() - 1);
        invalidate();
    }

    void setWeather(Glance.Weather wx) {
        weather = wx;
        if (wx != null) {
            wxTemp = wx.temp + "°";
            wxLine = (wx.text.isEmpty() ? "" : wx.text + "  ·  ") + "H " + wx.hi + "°  L " + wx.lo + "°";
        }
        updateWeatherGlass();
        invalidate();
    }

    /** "F" or "C": the setting, else by the TV's region (°F in the US). */
    String units() {
        String def = "US".equals(Locale.getDefault().getCountry()) || "LR".equals(Locale.getDefault().getCountry()) ? "F" : "C";
        return settings.getString("units", def);
    }

    void setGlance(Glance.Game g, Glance.Game also) {
        boolean on = settings.getBoolean("gameLine", true);
        this.game = on ? g : null;
        if (game != null && scoreboard(game)) { // pulse the pill when the score changes
            String k = game.id + "|" + game.away.score + "|" + game.home.score;
            if (scoreKey.startsWith(game.id + "|") && !scoreKey.equals(k)) scoreFlash = 1;
            scoreKey = k;
        }
        this.alsoGame = on && g != null && settings.getBoolean("alsoLine", true) ? also : null;
        if (focus == ALSO && alsoGame == null) focus = hasGame() ? GAME : 0;
        if (focus == GAME && !hasGame()) focus = 0;
        invalidate();
    }

    /** A game is on: poll more often so the score keeps up. */
    boolean liveNow() {
        return game != null && game.live;
    }

    /** Set before resume to open straight into idle mode (e.g. after an Alexa request started music). */
    boolean idleOnResume;

    void onResumed() {
        lastInput = SystemClock.uptimeMillis();
        if (idle) exitIdle();
        if (idleOnResume) { idleOnResume = false; enterIdle(); }
        removeCallbacks(heartbeat);
        post(heartbeat);
    }

    void onPaused() {
        removeCallbacks(heartbeat);
    }

    /** Every few seconds: go idle after a while, change the idle photo, and decide whether to keep the screen awake. */
    private void heartbeat() {
        long now = SystemClock.uptimeMillis();
        int idleMin = settings.getInt("idleMin", 5), sleepMin = settings.getInt("sleepMin", 240);
        if (setupStep < 0 && !settings.getBoolean("setupDone", false)) startSetup();
        if (!idle && setupStep < 0 && idleMin > 0 && now - lastInput >= idleMin * 60_000L) enterIdle();
        if (idle && idlePhotoMs() > 0 && now - idlePhotoAt >= idlePhotoMs()) nextIdlePhoto(1);
        if (nowPlaying != null) nowPlaying.refresh();
        host.keepAwake(sleepMin == 0 || now - lastInput < sleepMin * 60_000L);
    }

    private void enterIdle() {
        if (moving) cancelMove();
        menuApp = null;
        settingsOpen = false;
        expanded = false;
        focus = 0;
        idle = true;
        idlePhoto = loadedPhase;
        idlePhotoAt = SystemClock.uptimeMillis();
        invalidate();
    }

    /** How often idle mode changes the photo ("Idle photos" setting; 0 = like the home screen). */
    private long idlePhotoMs() {
        int m = settings.getInt("idlePhotoMin", 2);
        if (m == 0) m = settings.getInt("photoEvery", 60);
        return m * 60_000L;
    }

    private void exitIdle() {
        idle = false;
        // Keep the photo that's showing; the home screen's own rotation takes over at its next change.
        heldPhoto = idlePhoto;
        heldFor = currentPhoto();
        idlePhoto = null;
        lastInput = SystemClock.uptimeMillis();
        tick();
    }

    /** The photos idle mode cycles through: all of this theme's (every theme's with "Shuffle themes"), shuffled daily. */
    private List<String> idlePhotos() {
        List<String> out = new ArrayList<>();
        for (Photo p : pool(null)) out.add(p.key);
        java.util.Collections.shuffle(out, new java.util.Random(Calendar.getInstance().get(Calendar.DAY_OF_YEAR)));
        return out;
    }

    private void nextIdlePhoto(int d) {
        List<String> list = idlePhotos();
        int i = list.indexOf(loadedPhase);
        idlePhoto = list.get(((i < 0 ? 0 : i) + d + list.size()) % list.size());
        idlePhotoAt = SystemClock.uptimeMillis();
        idleDx = rng.nextInt(25) - 12; // nudge the clock a little each time (screen burn-in)
        idleDy = rng.nextInt(17) - 8;
        tick();
    }

    /** Home pressed: back to the clock, first tile focused, overlays closed. */
    void resetHome() {
        if (idle) exitIdle();
        lastInput = SystemClock.uptimeMillis();
        if (moving) cancelMove();
        menuApp = null;
        settingsOpen = false;
        expanded = false;
        focus = 0;
        invalidate();
    }

    /** Called every minute and on resume. */
    void tick() {
        String sched = currentPhoto();
        if (heldPhoto != null && (sched == null || !sched.equals(heldFor))) heldPhoto = null; // its turn is over
        String want = idle && idlePhoto != null ? idlePhoto : heldPhoto != null ? heldPhoto : sched;
        if (want != null && !want.equals(loadedPhase)) loadPhase(want);
        Calendar now = Calendar.getInstance();
        boolean h24 = settings.getBoolean("hour24", false);
        int h = now.get(Calendar.HOUR_OF_DAY);
        clockText = (h24 ? String.valueOf(h) : String.valueOf(h % 12 == 0 ? 12 : h % 12)) + ":" + String.format(Locale.US, "%02d", now.get(Calendar.MINUTE));
        int weight = settings.getInt("weight", 600);
        if (weight < 500 || weight > 800) weight = 600; // older setting from the thin clock
        int strength = settings.getInt("glass", 1);
        float frost = strength == 0 ? 0.08f : strength == 2 ? 0.34f : 0.21f; // iOS default glass is milky
        String key = clockText + "|" + weight + "|" + loadedPhase + "|" + strength;
        if (!key.equals(lastClockKey)) {
            clock = clockRenderer.render(clockText, weight, clockBackdrop, frost);
            clockLeft = clockRenderer.left();
            lastClockKey = key;
        }
        dateText = new SimpleDateFormat("EEEE, MMMM d", Locale.US).format(new Date());
        updateWeatherGlass();
        invalidate();
    }

    /** Re-renders the glass temperature + icon when the weather, photo, clock weight or glass strength changes. */
    private void updateWeatherGlass() {
        if (weather == null) { wxBitmap = null; wxKey = ""; return; }
        int weight = settings.getInt("weight", 600);
        if (weight < 500 || weight > 800) weight = 600;
        int strength = settings.getInt("glass", 1);
        float frost = strength == 0 ? 0.08f : strength == 2 ? 0.34f : 0.21f;
        String icon = WeatherGlass.icon(weather);
        String key = wxTemp + "|" + icon + "|" + weight + "|" + strength + "|" + loadedPhase;
        if (key.equals(wxKey)) return;
        wxBitmap = wxGlass.render(wxTemp, icon, weight, blur, frost);
        wxKey = key;
    }

    String leagues() {
        StringBuilder sb = new StringBuilder();
        for (String l : new String[]{"nfl", "mlb", "nhl", "nba"}) {
            if (settings.getBoolean("league_" + l, true)) sb.append(sb.length() > 0 ? "," : "").append(l);
        }
        return sb.toString();
    }

    /** Test hook (adb): jump straight to a state with animations settled, for snapshots. */
    void debugState(String phaseOverride, String photo, boolean expand, int focusIndex, boolean openSettings, boolean openMenu, boolean goIdle) {
        if (photo != null && photoByKey.containsKey(photo)) { // show this photo until the next scheduled change
            heldPhoto = photo;
            heldFor = currentPhoto();
            lastClockKey = "";
            tick();
            fade = 1;
            if (prevWall != null) { prevWall.recycle(); prevWall = null; }
        } else if (phaseOverride != null) {
            List<Photo> pp = pool(phaseOverride);
            loadPhase(pp.isEmpty() ? loadedPhase : pp.get(0).key);
            fade = 1;
            if (prevWall != null) { prevWall.recycle(); prevWall = null; }
            lastClockKey = "";
            String saved = settings.getString("wallpaper", "auto");
            settings.edit().putString("wallpaper", phaseOverride).commit();
            tick();
            settings.edit().putString("wallpaper", saved).commit();
        }
        expanded = expand;
        e = expand ? 1 : 0;
        focus = Math.max(GEAR, Math.min(focusIndex, visible.size() - 1));
        if (focus == GAME && !hasGame()) focus = 0;
        for (int i = 0; i < focusAmt.length; i++) focusAmt[i] = i == focus ? 1 : 0;
        gameAmt = focus == GAME ? 1 : 0;
        gearAmt = focus == GEAR ? 1 : 0;
        settingsOpen = openSettings;
        if (openMenu && focus >= 0 && focus < visible.size()) longPress();
        if (goIdle && !idle) { enterIdle(); idleAmt = 1; } // settled, not mid-fade
    }

    private String appVersion() {
        try {
            return getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    void showToast(String msg) {
        toast = msg;
        toastUntil = SystemClock.uptimeMillis() + 3000;
        invalidate();
    }

    // ================= assets =================

    private Typeface font(int wght) {
        return new Typeface.Builder(getContext().getAssets(), "fonts/InterTight-VF.ttf")
                .setFontVariationSettings("'wght' " + wght).build();
    }

    private Bitmap asset(String path) {
        try (InputStream is = getContext().getAssets().open(path)) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeStream(is, null, o);
        } catch (Exception ex) {
            return null;
        }
    }

    private void loadThemes() {
        try (InputStream is = getContext().getAssets().open("themes.json")) {
            org.json.JSONArray themes = new org.json.JSONArray(readAll(is));
            for (int i = 0; i < themes.length(); i++) addTheme(themes.getJSONObject(i), null);
        } catch (Exception e) {
            android.util.Log.e("Orbit", "themes.json", e);
        }
        loadCustomThemes();
        if (themeIds.isEmpty()) { themeIds.add("orbit"); themeNames.add("Orbit"); }
    }

    /**
     * Custom themes live on the TV, not in the app: one folder per theme in
     * /sdcard/Android/data/home.jw.orbit/files/themes/<id>/ holding theme.json (one theme, same format as
     * assets/themes.json) and the photos made by tools/custom_theme.sh. Read when Orbit starts.
     */
    private void loadCustomThemes() {
        java.io.File root = getContext().getExternalFilesDir("themes");
        java.io.File[] dirs = root == null ? null : root.listFiles();
        if (dirs == null) return;
        java.util.Arrays.sort(dirs);
        for (java.io.File d : dirs) {
            java.io.File f = new java.io.File(d, "theme.json");
            if (!f.isFile()) continue;
            try (InputStream is = new java.io.FileInputStream(f)) {
                org.json.JSONObject t = new org.json.JSONObject(readAll(is));
                if (themeIds.contains(t.getString("id"))) continue; // never shadow a built-in theme
                addTheme(t, d);
            } catch (Exception e) {
                android.util.Log.e("Orbit", "custom theme " + d, e);
            }
        }
    }

    private void addTheme(org.json.JSONObject t, java.io.File dir) throws org.json.JSONException {
        String tid = t.getString("id");
        themeIds.add(tid);
        themeNames.add(t.getString("name"));
        org.json.JSONArray ps = t.getJSONArray("photos");
        for (int j = 0; j < ps.length(); j++) {
            org.json.JSONObject o = ps.getJSONObject(j);
            String pid = o.getString("id");
            if (dir != null && !pid.matches("[A-Za-z0-9_-]{1,60}")) continue; // custom theme: the id names a file in its folder, nothing else
            Photo ph = new Photo();
            ph.theme = tid;
            ph.dir = dir;
            ph.key = tid + "/" + pid;
            ph.phase = o.optString("phase", "day");
            ph.caption = o.optString("caption");
            ph.credit = o.optString("credit") + (o.optString("license").isEmpty() ? "" : " · " + o.optString("license"));
            photos.add(ph);
            photoByKey.put(ph.key, ph);
        }
    }

    private static String readAll(InputStream is) {
        java.util.Scanner sc = new java.util.Scanner(is, "UTF-8").useDelimiter("\\A");
        return sc.hasNext() ? sc.next() : "";
    }

    /** A photo's wallpaper file: suffix "" (the photo), "_blur" or "_clock". */
    private Bitmap wallFile(String key, String suffix) {
        Photo ph = photoByKey.get(key);
        if (ph == null || ph.dir == null) return asset("wall/" + key + suffix + ".jpg");
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeFile(new java.io.File(ph.dir, key.substring(ph.theme.length() + 1) + suffix + ".jpg").getPath(), o);
    }

    /** The photos to choose from: the chosen theme (every theme with "Shuffle themes"), matching the time of day if asked. */
    private List<Photo> pool(String phase) {
        String t = settings.getString("theme", "orbit");
        boolean all = t.equals("shuffle");
        if (!all && !themeIds.contains(t)) t = themeIds.get(0);
        List<Photo> out = new ArrayList<>();
        for (Photo p : photos) if ((all || p.theme.equals(t)) && (phase == null || p.phase.equals(phase))) out.add(p);
        if (out.isEmpty() && phase != null) return pool(null);
        if (out.isEmpty()) out.addAll(photos);
        return out;
    }

    /** null = any time of day. */
    private String wantedPhase() {
        String w = settings.getString("wallpaper", "auto");
        if (w.equals("any") || w.equals("shuffle")) return null;
        for (String p : Sun.PHASES) if (p.equals(w)) return p;
        return Sun.phaseNow();
    }

    /**
     * The photo to show now. With "Change photo" on, it moves to the next one every 5 / 15 / 60 minutes,
     * going through the whole pool in a shuffled order before any repeats.
     */
    private String currentPhoto() {
        List<Photo> p = pool(wantedPhase());
        if (p.isEmpty()) return null;
        int every = settings.getInt("photoEvery", 60);
        if (every <= 0 || p.size() == 1) return p.get(0).key;
        long slot = System.currentTimeMillis() / 60_000 / every;
        int n = p.size();
        List<Photo> order = new ArrayList<>(p);
        java.util.Collections.shuffle(order, new java.util.Random(slot / n * 7919 + n));
        return order.get((int) (slot % n)).key;
    }

    /** p = "<theme>/<phase>" */
    private void loadPhase(String p) {
        Bitmap next = wallFile(p, "");
        if (next == null) return;
        if (prevWall != null && prevWall != wall) prevWall.recycle();
        prevWall = wall;
        wall = next;
        fade = prevWall == null ? 1 : 0;
        blur = wallFile(p, "_blur");
        glassBlur.setShader(new BitmapShader(blur, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        clockBackdrop = wallFile(p, "_clock");
        loadedPhase = p;
        Photo ph = photoByKey.get(p);
        phase = ph != null ? ph.phase : "";
        caption = ph != null ? ph.caption : "";
        credit = ph != null ? ph.credit : "";
    }

    /** Soft glow / shadow sprites, blurred once in software (BlurMaskFilter needs a software canvas). */
    private void makeSoftBitmaps() {
        glow = soft(Color.WHITE, 30);
        shadow = soft(Color.BLACK, 22);
    }

    private Bitmap soft(int color, float radius) {
        int pad = 60;
        Bitmap b = Bitmap.createBitmap(TILE_W + 2 * pad, TILE_H + 2 * pad, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setMaskFilter(new BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL));
        c.drawRoundRect(new RectF(pad, pad, pad + TILE_W, pad + TILE_H), 22, 22, p);
        return b;
    }

    // ================= drawing =================

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        boolean animating = step(dt);

        float s = getWidth() / 1920f;
        barsPlaced = false;
        c.save();
        c.scale(s, s);
        drawWallpaper(c);
        float ty = -LAYER_UP * e - rowScroll() * e;

        float home = 1 - e;
        float ui = 1 - idleAmt; // everything idle mode hides
        if (home > 0.01f) {
            c.save();
            c.translate(idleDx * idleAmt, idleDy * idleAmt);
            if (clock != null) {
                bmp.setAlpha((int) (255 * home));
                c.drawBitmap(clock, clockLeft, clockRenderer.top() + ty, bmp);
                bmp.setAlpha(255);
            }
            float y = 524 + ty; // date, a little below the clock
            if (settings.getBoolean("showDate", true)) {
                textStyle(t400, 40, 0.0125f, alpha(0.9f * home));
                text.setShadowLayer(16, 0, 2, Color.argb((int) (115 * home), 0, 0, 0));
                drawCentered(c, dateText, 960, y);
                text.clearShadowLayer();
            }
            // Idle keeps a live game on screen (the score), and drops the rest.
            if (hasGame()) drawGamePill(c, 558 + ty, home * (game.live ? 1 : ui), home * ui);
            c.restore();
            boolean wx = drawWeather(c, home);
            drawCaption(c, home, wx ? 204 : 104);
            if (musicAmt > 0.01f) {
                drawMusicCard(c, musicAmt * idleAmt * home);
                drawMusicPill(c, musicAmt * ui * (1 - e));
            }
        }

        if (ui > 0.01f) {
            int layer = ui < 0.99f ? c.saveLayerAlpha(0, 0, 1920, 1080, (int) (255 * ui)) : -1;
            drawShelf(c, ty);
            if (e > 0.01f) {
                textStyle(t500, 30, 0.017f, alpha(0.85f * e));
                c.drawText("All apps", GRID_X + 2, 718 + ty, text);
            }
            drawTiles(c, ty);
            if (e > 0.01f) drawMiniHeader(c, e);
            if (rows() > 1) drawChevron(c);
            drawGearButton(c);
            if (layer >= 0) c.restoreToCount(layer);
        }

        if (menuApp != null) drawMenu(c);
        if (settingsOpen) drawSettings(c);
        if (setupStep >= 0) drawSetup(c);
        if (toast != null && now < toastUntil) {
            drawToast(c);
            animating = true;
        } else toast = null;
        c.restore();
        if (bars != null && !barsPlaced && bars.getAlpha() > 0) bars.setAlpha(0);
        if (animating || moving) postInvalidateOnAnimation();
        else {
            lastFrame = 0;
            if (musicAmt > 0.01f && nowPlaying != null && nowPlaying.playing) postInvalidateDelayed(1000); // progress bar
        }
    }

    /** Eases every animated value toward its target. Returns true while anything is still moving. */
    private boolean step(float dt) {
        boolean busy = false;
        float te = expanded ? 1 : 0;
        e = ease(e, te, dt, 7.5f);
        busy |= Math.abs(e - te) > 0.001f;
        if (Math.abs(e - te) <= 0.001f) e = te;
        fade = ease(fade, 1, dt, 1.4f);
        if (fade > 0.995f) {
            fade = 1;
            if (prevWall != null) { prevWall.recycle(); prevWall = null; }
        } else busy = true;
        for (int i = 0; i < focusAmt.length; i++) {
            float t = (i == focus && menuApp == null && !settingsOpen) ? 1 : 0;
            focusAmt[i] = ease(focusAmt[i], t, dt, 14);
            if (Math.abs(focusAmt[i] - t) > 0.002f) busy = true; else focusAmt[i] = t;
        }
        float tgear = focus == GEAR && menuApp == null && !settingsOpen ? 1 : 0;
        gearAmt = ease(gearAmt, tgear, dt, 14);
        if (Math.abs(gearAmt - tgear) > 0.002f) busy = true; else gearAmt = tgear;
        float ta = focus == ALSO && menuApp == null && !settingsOpen ? 1 : 0;
        alsoAmt = ease(alsoAmt, ta, dt, 14);
        if (Math.abs(alsoAmt - ta) > 0.002f) busy = true; else alsoAmt = ta;
        float tm = nowPlaying != null && nowPlaying.showing() && settings.getBoolean("nowPlaying", true) ? 1 : 0;
        musicAmt = ease(musicAmt, tm, dt, 4f);
        if (Math.abs(musicAmt - tm) > 0.002f) busy = true; else musicAmt = tm;
        float ti = idle ? 1 : 0;
        idleAmt = ease(idleAmt, ti, dt, 3f);
        if (Math.abs(idleAmt - ti) > 0.002f) busy = true; else idleAmt = ti;
        if (scoreFlash > 0) {
            scoreFlash = scoreFlash < 0.01f ? 0 : ease(scoreFlash, 0, dt, 0.7f);
            busy = true;
        }
        float tg = focus == GAME ? 1 : 0;
        gameAmt = ease(gameAmt, tg, dt, 14);
        if (Math.abs(gameAmt - tg) > 0.002f) busy = true; else gameAmt = tg;
        return busy;
    }

    private static float ease(float v, float target, float dt, float k) {
        return v + (target - v) * (1 - (float) Math.exp(-dt * k));
    }

    private void drawWallpaper(Canvas c) {
        float sc = 1 + (PARALLAX / 1000f) * e;
        wallMatrix.setScale(sc, sc, 960, 0);
        wallMatrix.postTranslate(0, -PARALLAX * e);
        if (prevWall != null && fade < 1) {
            bmp.setAlpha(255);
            c.drawBitmap(prevWall, wallMatrix, bmp);
            bmp.setAlpha((int) (255 * fade));
        }
        if (wall != null) c.drawBitmap(wall, wallMatrix, bmp);
        else c.drawColor(0xff010208);
        bmp.setAlpha(255);
        if ("night".equals(phase) && settings.getBoolean("dimNight", false)) c.drawColor(Color.argb(64, 0, 0, 0));
        blurMatrix.setScale(4, 4);
        blurMatrix.postConcat(wallMatrix);
    }

    /** Local-to-screen transform of whatever is being drawn (a scaled tile, the scaled game pill). */
    private Matrix localToWorld;
    private boolean smoked;
    private final Matrix shaderTmp = new Matrix(), inv = new Matrix();

    /** A liquid-glass panel: blurred wallpaper behind, light tint, thin bright rim, highlight on top. */
    private void glass(Canvas c, RectF rr, float rad, float alpha, float tintBoost) {
        if (alpha <= 0.01f) return;
        int strength = settings.getInt("glass", 1);
        float tint = (strength == 0 ? 0.05f : strength == 2 ? 0.14f : 0.08f) + tintBoost;
        Shader bs = glassBlur.getShader();
        if (bs != null) {
            // Keep the blurred wallpaper lined up with the real one even inside a scaled/rotated tile.
            shaderTmp.set(blurMatrix);
            if (localToWorld != null && localToWorld.invert(inv)) shaderTmp.postConcat(inv);
            bs.setLocalMatrix(shaderTmp);
            glassBlur.setAlpha((int) (255 * alpha));
            c.drawRoundRect(rr, rad, rad, glassBlur);
        }
        fill.setShader(null);
        if (smoked) { // menus and panels: dark glass, so text reads over a bright Earth too
            fill.setColor(Color.argb((int) (150 * alpha), 8, 10, 16));
            c.drawRoundRect(rr, rad, rad, fill);
            tint = 0.04f;
        }
        fill.setColor(Color.argb((int) (255 * tint * alpha), 255, 255, 255));
        c.drawRoundRect(rr, rad, rad, fill);
        stroke.setStrokeWidth(1.5f);
        rimMatrix.setScale(1, Math.min(60, rr.height()) / 60f);
        rimMatrix.postTranslate(0, rr.top);
        rimShader.setLocalMatrix(rimMatrix);
        stroke.setShader(rimShader);
        stroke.setColor(Color.WHITE);
        stroke.setAlpha((int) (255 * alpha));
        c.drawRoundRect(rr, rad, rad, stroke);
        stroke.setShader(null);
        stroke.setAlpha(255);
    }

    private boolean scoreboard(Glance.Game g) {
        return g.live && g.away != null && g.home != null && g.away.score >= 0 && g.home.score >= 0;
    }

    /** "Brewers 3 – 2 Rays": scores bold, the team that's behind dimmer. Returns the width. */
    private float drawScore(Canvas c, Glance.Game g, float x, float cy, float a, boolean draw) {
        int as = g.away.score, hs = g.home.score;
        float aw = as >= hs ? 1 : 0.6f, ah = hs >= as ? 1 : 0.6f, x0 = x;
        String sa = String.valueOf(as), sh = String.valueOf(hs);
        textStyle(t500, 34, 0, alpha(aw * a));
        if (draw) c.drawText(g.away.name, x, cy + 12, text);
        x += text.measureText(g.away.name) + 14;
        textStyle(t600, 42, 0, alpha(aw * a));
        if (draw) c.drawText(sa, x, cy + 15, text);
        x += text.measureText(sa) + 12;
        textStyle(t300, 34, 0, alpha(0.5f * a));
        if (draw) c.drawText("–", x, cy + 11, text);
        x += text.measureText("–") + 12;
        textStyle(t600, 42, 0, alpha(ah * a));
        if (draw) c.drawText(sh, x, cy + 15, text);
        x += text.measureText(sh) + 14;
        textStyle(t500, 34, 0, alpha(ah * a));
        if (draw) c.drawText(g.home.name, x, cy + 12, text);
        x += text.measureText(g.home.name);
        return x - x0;
    }

    private void drawGamePill(Canvas c, float top, float home, float chipA) {
        Glance.Game g = game;
        float f = gameAmt;
        boolean sb = scoreboard(g);
        if (home <= 0.01f) return;
        float h = 76, gap = 18;
        // measure
        textStyle(t600, 21, 0.07f, 0);
        float liveW = g.live ? 14 + 10 + 10 + text.measureText("LIVE") + 14 : 0;
        float starW = g.fav ? 26 : 0;
        textStyle(t600, 21, 0.095f, 0);
        float tagW = text.measureText(g.tag);
        textStyle(t500, 34, 0, 0);
        float titleW = sb ? drawScore(c, g, 0, 0, 1, false) : text.measureText(g.title);
        textStyle(t400, 28, 0, 0);
        float detW = text.measureText(g.detail);
        textStyle(t600, 20, 0.05f, 0);
        float netW = g.net.isEmpty() ? 0 : text.measureText(g.net) + 24;
        textStyle(t600, 21, 0, 0);
        float okW = text.measureText("OK · Watch") + 32;
        float w = 20 + 26;
        int parts = 0;
        for (float pw : new float[]{liveW, starW, tagW, titleW, detW, netW}) if (pw > 0) { w += pw; parts++; }
        w += gap * (parts - 1) + (okW + gap) * f;

        c.save();
        float sc = 1 + 0.05f * f + 0.04f * scoreFlash;
        Matrix pm = pillMatrix;
        pm.setScale(sc, sc, 960, top + h / 2);
        c.concat(pm);
        localToWorld = pm;
        r.set(960 - w / 2, top, 960 + w / 2, top + h);
        glass(c, r, h / 2, home, 0.1f * f);
        localToWorld = null;
        if (f > 0.01f) {
            stroke.setStrokeWidth(2);
            stroke.setColor(Color.argb((int) (242 * f * home), 255, 255, 255));
            c.drawRoundRect(r, h / 2, h / 2, stroke);
        }
        if (scoreFlash > 0.01f) { // the score just changed
            stroke.setStrokeWidth(4);
            stroke.setColor(Color.argb((int) (255 * scoreFlash * home), 255, 214, 10));
            c.drawRoundRect(r, h / 2, h / 2, stroke);
        }
        float x = r.left + 20, cy = top + h / 2;
        if (g.live) {
            fill.setColor(Color.argb((int) (235 * home), 255, 59, 48));
            r.set(x, cy - 18, x + liveW, cy + 18);
            c.drawRoundRect(r, 18, 18, fill);
            fill.setColor(alpha(home));
            c.drawCircle(x + 14 + 5, cy, 5, fill);
            textStyle(t600, 21, 0.07f, alpha(home));
            c.drawText("LIVE", x + 14 + 10 + 10, cy + 7.5f, text);
            x += liveW + gap;
        }
        if (g.fav) {
            drawStar(c, x + 13, cy, 13, Color.argb((int) (255 * home), 255, 214, 10));
            x += starW + gap;
        }
        textStyle(t600, 21, 0.095f, alpha(0.72f * home));
        c.drawText(g.tag, x, cy + 7.5f, text);
        x += tagW + gap;
        if (sb) drawScore(c, g, x, cy, home, true);
        else {
            textStyle(t500, 34, 0, alpha(home));
            c.drawText(g.title, x, cy + 12, text);
        }
        x += titleW + gap;
        textStyle(t400, 28, 0, alpha(0.78f * home));
        c.drawText(g.detail, x, cy + 10, text);
        x += detW + gap;
        if (netW > 0) {
            stroke.setStrokeWidth(1.2f);
            stroke.setColor(alpha(0.45f * home));
            r.set(x, cy - 16, x + netW, cy + 16);
            c.drawRoundRect(r, 10, 10, stroke);
            textStyle(t600, 20, 0.05f, alpha(home));
            c.drawText(g.net, x + 12, cy + 7, text);
            x += netW + gap;
        }
        if (f > 0.01f) {
            fill.setColor(Color.argb((int) (255 * f * home), 255, 255, 255));
            r.set(x, cy - 19, x + okW, cy + 19);
            c.drawRoundRect(r, 19, 19, fill);
            textStyle(t600, 21, 0, Color.argb((int) (255 * f * home), 11, 13, 20));
            c.drawText("OK · Watch", x + 16, cy + 7.5f, text);
        }
        c.restore();
        if (alsoGame != null && chipA > 0.01f) drawAlsoChip(c, top + h + 16, chipA);
    }

    /** The second game: a small glass chip under the game pill. Up from the apps lands here. */
    private void drawAlsoChip(Canvas c, float top, float home) {
        Glance.Game g = alsoGame;
        float f = alsoAmt, h = 50, gap = 12;
        textStyle(t600, 18, 0.1f, 0);
        float tagW = text.measureText("ALSO");
        textStyle(t500, 23, 0, 0);
        float titleW = text.measureText(g.title);
        String tail = g.detail + (g.net.isEmpty() ? "" : " · " + g.net);
        textStyle(t400, 21, 0, 0);
        float tailW = text.measureText(tail);
        float dotW = g.live ? 10 + gap : 0;
        textStyle(t600, 18, 0, 0);
        float okW = (text.measureText("OK · Watch") + 24 + gap) * f;
        float w = 22 + dotW + tagW + gap + titleW + gap + tailW + okW + 22;
        c.save();
        float sc = 1 + 0.06f * f;
        pillMatrix.setScale(sc, sc, 960, top + h / 2);
        c.concat(pillMatrix);
        localToWorld = pillMatrix;
        r.set(960 - w / 2, top, 960 + w / 2, top + h);
        glass(c, r, h / 2, home, 0.02f + 0.1f * f);
        localToWorld = null;
        if (f > 0.01f) {
            stroke.setStrokeWidth(2);
            stroke.setColor(Color.argb((int) (242 * f * home), 255, 255, 255));
            c.drawRoundRect(r, h / 2, h / 2, stroke);
        }
        float x = r.left + 22, cy = top + h / 2;
        if (g.live) {
            fill.setColor(Color.argb((int) (255 * home), 255, 59, 48));
            c.drawCircle(x + 5, cy, 5, fill);
            x += dotW;
        }
        textStyle(t600, 18, 0.1f, alpha(0.6f * home));
        c.drawText("ALSO", x, cy + 6.5f, text);
        x += tagW + gap;
        textStyle(t500, 23, 0, alpha(home));
        c.drawText(g.title, x, cy + 8, text);
        x += titleW + gap;
        textStyle(t400, 21, 0, alpha(0.75f * home));
        c.drawText(tail, x, cy + 7.5f, text);
        x += tailW + gap;
        if (f > 0.01f) {
            fill.setColor(Color.argb((int) (255 * f * home), 255, 255, 255));
            textStyle(t600, 18, 0, 0);
            float bw = text.measureText("OK · Watch") + 24;
            r.set(x, cy - 15, x + bw, cy + 15);
            c.drawRoundRect(r, 15, 15, fill);
            textStyle(t600, 18, 0, Color.argb((int) (255 * f * home), 11, 13, 20));
            c.drawText("OK · Watch", x + 12, cy + 6.5f, text);
        }
        c.restore();
    }

    /** Where the photo is from, small in the top-left corner (home view only). */
    private void drawCaption(Canvas c, float a, float y) {
        if (caption.isEmpty() || !settings.getBoolean("caption", true) || a <= 0.01f) return;
        textStyle(t400, 22, 0.01f, alpha(0.72f * a));
        text.setShadowLayer(8, 0, 1, Color.argb((int) (150 * a), 0, 0, 0));
        c.drawText(caption, 64, y, text);
        text.clearShadowLayer();
    }

    /** Top-left corner: icon, temperature, and conditions with today's high and low. */
    private boolean drawWeather(Canvas c, float a) {
        if (weather == null || !settings.getBoolean("weather", true) || a <= 0.01f) return false;
        int layer = c.saveLayerAlpha(0, 0, 760, 240, (int) (255 * a));
        text.setShadowLayer(14, 0, 2, Color.argb(120, 0, 0, 0));
        if (wxBitmap != null) c.drawBitmap(wxBitmap, 0, 0, bmp); // glass icon + temperature
        else {
            drawWxIcon(c, weather, 98, 90);
            textStyle(t300, 60, 0, alpha(1));
            c.drawText(wxTemp, 146, 112, text);
        }
        textStyle(t400, 24, 0.01f, alpha(0.82f));
        c.drawText(wxLine, 66, 158, text);
        text.clearShadowLayer();
        c.restoreToCount(layer);
        return true;
    }

    private void drawWxIcon(Canvas c, Glance.Weather w, float cx, float cy) {
        int code = w.code;
        boolean clear = code <= 1, partly = code == 2, fog = code == 45 || code == 48;
        boolean snow = (code >= 71 && code <= 77) || code == 85 || code == 86, storm = code >= 95;
        boolean rain = !snow && !storm && code >= 51;
        if (clear || partly) {
            float ox = partly ? cx - 10 : cx, oy = partly ? cy - 9 : cy, rad = partly ? 15 : 20;
            if (w.day) {
                fill.setColor(Color.WHITE);
                c.drawCircle(ox, oy, rad, fill);
                stroke.setStrokeWidth(4);
                stroke.setStrokeCap(Paint.Cap.ROUND);
                stroke.setColor(Color.WHITE);
                for (int i = 0; i < 8; i++) {
                    double t = i * Math.PI / 4;
                    float cs = (float) Math.cos(t), sn = (float) Math.sin(t);
                    c.drawLine(ox + cs * (rad + 6), oy + sn * (rad + 6), ox + cs * (rad + 12), oy + sn * (rad + 12), stroke);
                }
                stroke.setStrokeCap(Paint.Cap.BUTT);
            } else {
                c.save();
                c.translate(ox, oy);
                c.scale(rad / 20f, rad / 20f);
                fill.setColor(Color.WHITE);
                c.drawPath(moonPath, fill);
                c.restore();
            }
            if (clear) return;
        }
        fill.setColor(Color.WHITE);
        if (fog) {
            stroke.setStrokeWidth(5);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setColor(Color.argb(230, 255, 255, 255));
            for (int i = 0; i < 3; i++) c.drawLine(cx - 26 + i * 4, cy - 12 + i * 12, cx + 26 - i * 4, cy - 12 + i * 12, stroke);
            stroke.setStrokeCap(Paint.Cap.BUTT);
            return;
        }
        float ccx = cx + (partly ? 6 : 0), ccy = rain || snow || storm ? cy - 8 : cy + 2;
        c.drawCircle(ccx - 14, ccy + 3, 12, fill); // the cloud
        c.drawCircle(ccx + 2, ccy - 6, 17, fill);
        c.drawCircle(ccx + 17, ccy + 3, 12, fill);
        r.set(ccx - 26, ccy + 2, ccx + 29, ccy + 15);
        c.drawRoundRect(r, 7, 7, fill);
        if (rain) {
            stroke.setStrokeWidth(4);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setColor(Color.argb(220, 255, 255, 255));
            for (int i = 0; i < 3; i++) c.drawLine(ccx - 12 + i * 13, ccy + 22, ccx - 17 + i * 13, ccy + 33, stroke);
            stroke.setStrokeCap(Paint.Cap.BUTT);
        } else if (snow) {
            for (int i = 0; i < 3; i++) c.drawCircle(ccx - 13 + i * 13, ccy + 27 + (i % 2) * 5, 3.5f, fill);
        } else if (storm) {
            c.save();
            c.translate(ccx, ccy + 20); // below the cloud, all white
            fill.setColor(Color.WHITE);
            c.drawPath(boltPath, fill);
            c.restore();
        }
    }

    private int rows() {
        return Math.max(1, (visible.size() + COLS - 1) / COLS);
    }

    /** With more than three rows, scroll so the focused row stays on screen. */
    private float rowScroll() {
        int fr = focus < 0 ? 0 : focus / COLS;
        return Math.max(0, Math.min(fr - (VISIBLE_ROWS - 1), rows() - VISIBLE_ROWS)) * ROW;
    }

    private void drawShelf(Canvas c, float ty) {
        int n = Math.min(rows(), VISIBLE_ROWS);
        float hExp = n * TILE_H + (n - 1) * (ROW - TILE_H) + 48 + Math.max(0, rows() - VISIBLE_ROWS) * ROW;
        float h = SHELF_H + (hExp - SHELF_H) * e;
        r.set(SHELF_X, SHELF_Y + ty, SHELF_X + SHELF_W, SHELF_Y + ty + h);
        glass(c, r, 48, 1, 0);
    }

    private void drawTiles(Canvas c, float ty) {
        int n = visible.size();
        for (int pass = 0; pass < 2; pass++) { // focused tile last, so it sits on top
            for (int i = 0; i < n; i++) {
                boolean top = focusAmt[i] > 0.01f;
                if ((pass == 0) == top) continue;
                int row = i / COLS, col = i % COLS;
                float off = row > 0 ? PEEK + (row - 1) * 60 : 0;
                float rowAlpha = row == 0 ? 1 : row == 1 ? 0.6f : 0;
                float a = rowAlpha + (1 - rowAlpha) * e;
                if (a <= 0.01f) continue;
                float x = GRID_X + col * (TILE_W + GAP);
                float y = GRID_Y + ty + row * ROW + off * (1 - e);
                if (y > 1080 || y + TILE_H < 0) continue;
                drawTile(c, i, x, y, a, focusAmt[i]);
            }
        }
    }

    private void drawTile(Canvas c, int i, float x, float y, float a, float f) {
        Apps.App app = visible.get(i);
        c.save();
        float sc = 1 + 0.1f * f;
        float wig = moving && i == focus ? (float) Math.sin(SystemClock.uptimeMillis() / 90.0) * 1.4f : 0;
        Matrix tm = tileMatrix;
        tm.setTranslate(-TILE_W / 2f, -TILE_H / 2f);
        tm.postScale(sc, sc);
        tm.postRotate(wig);
        tm.postTranslate(x + TILE_W / 2f, y + TILE_H / 2f - 8 * f);
        c.concat(tm);
        // shadow and focus glow
        bmp.setAlpha((int) (255 * a * (0.55f + 0.35f * f)));
        c.drawBitmap(shadow, -60, -60 + 10 + 10 * f, bmp);
        if (f > 0.01f) {
            bmp.setAlpha((int) (110 * f * a));
            c.drawBitmap(glow, -60, -60, bmp);
        }
        bmp.setAlpha(255);
        r.set(0, 0, TILE_W, TILE_H);
        float dim = app.hidden ? 0.45f : 1;
        {
            BitmapShader sh = bannerShaders.get(app.banner);
            if (sh == null) {
                sh = new BitmapShader(app.banner, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                bannerMatrix.setScale(TILE_W / (float) app.banner.getWidth(), TILE_H / (float) app.banner.getHeight());
                sh.setLocalMatrix(bannerMatrix);
                bannerShaders.put(app.banner, sh);
            }
            fill.setShader(sh);
            fill.setAlpha((int) (255 * a * dim));
            c.drawRoundRect(r, 22, 22, fill);
            fill.setShader(null);
            // glass sheen + rim
            fill.setShader(sheenShader);
            fill.setColor(Color.WHITE);
            fill.setAlpha((int) ((36 + 46 * f) * a));
            c.drawRoundRect(r, 22, 22, fill);
            fill.setShader(null);
            fill.setAlpha(255);
            if (app.hidden) {
                textStyle(t600, 16, 0.08f, alpha(0.9f * a));
                fill.setColor(Color.argb((int) (170 * a), 0, 0, 0));
                RectF badge = new RectF(10, 10, 10 + text.measureText("HIDDEN") + 16, 36);
                c.drawRoundRect(badge, 8, 8, fill);
                c.drawText("HIDDEN", 18, 29, text);
            }
        }
        stroke.setStrokeWidth(f > 0.01f ? 3 : 1.2f);
        stroke.setColor(f > 0.01f ? Color.argb((int) (242 * f * a), 255, 255, 255) : Color.argb((int) (56 * a), 255, 255, 255));
        c.drawRoundRect(r, 22, 22, stroke);
        if (moving && i == focus) {
            textStyle(t500, 18, 0, alpha(0.9f));
            drawCentered(c, "◀ ▶ move · OK done", TILE_W / 2f, TILE_H + 34);
        }
        c.restore();
    }

    private void drawMiniHeader(Canvas c, float a) {
        float y = 64 + 74 - 24 * (1 - a);
        textStyle(t200, 92, -0.02f, alpha(a));
        text.setShadowLayer(14, 0, 2, Color.argb((int) (100 * a), 0, 0, 0));
        c.drawText(clockText, 120, y, text);
        float tw = text.measureText(clockText);
        textStyle(t400, 28, 0, alpha(0.8f * a));
        c.drawText(dateText, 120 + tw + 22, y, text);
        if (hasGame()) {
            textStyle(t400, 26, 0, alpha(0.72f * a));
            String tail = game.detail + (game.net.isEmpty() ? "" : " · " + game.net);
            float tailW = text.measureText(tail);
            c.drawText(tail, 1740 - tailW, y - 24, text);
            textStyle(t500, 26, 0, alpha(a));
            float titleW = text.measureText(game.title);
            c.drawText(game.title, 1740 - tailW - 14 - titleW, y - 24, text);
            if (game.live) {
                fill.setColor(Color.argb((int) (255 * a), 255, 59, 48));
                c.drawCircle(1740 - tailW - 14 - titleW - 20, y - 33, 6, fill);
            }
        }
        text.clearShadowLayer();
    }

    private void drawChevron(Canvas c) {
        float y = 952 + (852 - 952) * e + (rows() > VISIBLE_ROWS ? 160 * e : 0);
        r.set(932, y, 988, y + 56);
        glass(c, r, 28, 1, 0.04f);
        c.save();
        c.rotate(180 * e, 960, y + 28);
        stroke.setStrokeWidth(3);
        stroke.setColor(Color.WHITE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        Path p = new Path();
        p.moveTo(948, y + 22);
        p.lineTo(960, y + 34);
        p.lineTo(972, y + 22);
        c.drawPath(p, stroke);
        c.restore();
    }

    /** Settings: a round glass button in the top-right corner. */
    private void drawGearButton(Canvas c) {
        float f = gearAmt, sc = 1 + 0.15f * f;
        c.save();
        c.scale(sc, sc, GEAR_CX, GEAR_CY);
        pillMatrix.setScale(sc, sc, GEAR_CX, GEAR_CY);
        localToWorld = pillMatrix;
        r.set(GEAR_CX - GEAR_R, GEAR_CY - GEAR_R, GEAR_CX + GEAR_R, GEAR_CY + GEAR_R);
        glass(c, r, GEAR_R, 1, 0.04f + 0.12f * f);
        localToWorld = null;
        if (f > 0.01f) {
            stroke.setStrokeWidth(3);
            stroke.setColor(Color.argb((int) (242 * f), 255, 255, 255));
            c.drawCircle(GEAR_CX, GEAR_CY, GEAR_R, stroke);
        }
        drawGear(c, GEAR_CX, GEAR_CY, 14, alpha(0.75f + 0.25f * f));
        c.restore();
        if (f > 0.01f) {
            textStyle(t500, 22, 0, alpha(f));
            text.setShadowLayer(10, 0, 1, Color.argb((int) (128 * f), 0, 0, 0));
            drawCentered(c, "Settings", GEAR_CX, GEAR_CY + GEAR_R + 36);
            text.clearShadowLayer();
        }
    }

    private void drawMenu(Canvas c) {
        c.drawColor(Color.argb(90, 0, 0, 0));
        float w = 560, rowH = 66, h = 120 + menuItems.size() * rowH;
        r.set(960 - w / 2, 540 - h / 2, 960 + w / 2, 540 + h / 2);
        smoked = true;
        glass(c, r, 36, 1, 0.08f);
        smoked = false;
        textStyle(t500, 32, 0, Color.WHITE);
        c.drawText(menuApp.label, r.left + 36, r.top + 64, text);
        for (int i = 0; i < menuItems.size(); i++) {
            float top = r.top + 96 + i * rowH;
            if (i == menuSel) {
                fill.setColor(Color.argb(56, 255, 255, 255));
                c.drawRoundRect(new RectF(r.left + 16, top, r.right - 16, top + rowH - 8), 16, 16, fill);
            }
            textStyle(i == menuSel ? t500 : t400, 28, 0, alpha(i == menuSel ? 1 : 0.8f));
            c.drawText(menuItems.get(i), r.left + 40, top + 40, text);
        }
    }

    private void drawToast(Canvas c) {
        textStyle(t500, 26, 0, Color.WHITE);
        float w = text.measureText(toast) + 64;
        r.set(960 - w / 2, 960, 960 + w / 2, 1024);
        smoked = true;
        glass(c, r, 32, 1, 0.12f);
        smoked = false;
        drawCentered(c, toast, 960, 1001);
    }

    // ================= settings =================

    private static final class Row {
        final String label, key;
        String[] names;
        Object[] values;   // null: an action row
        Row(String label, String key, String[] names, Object[] values) {
            this.label = label; this.key = key; this.names = names; this.values = values;
        }
    }

    private static final String[] ON_OFF = {"On", "Off"};
    private static final Object[] TF = {true, false};
    private final Row[] rowsDef = {
            new Row("Theme", "theme", null, null), // filled from themes.json in the constructor
            new Row("Photos", "wallpaper", new String[]{"Match time of day", "Dawn", "Day", "Golden hour", "Dusk", "Night", "Any time"},
                    new Object[]{"auto", "dawn", "day", "golden", "dusk", "night", "any"}),
            new Row("Change photo", "photoEvery", new String[]{"Every 5 min", "Every 15 min", "Every hour", "Never"}, new Object[]{5, 15, 60, 0}),
            new Row("Photo caption", "caption", ON_OFF, TF),
            new Row("Weather", "weather", ON_OFF, TF),
            new Row("Weather location", "location", null, null),
            new Row("Temperature", "units", new String[]{"°F", "°C"}, new Object[]{"F", "C"}),
            new Row("Idle mode after (or press Play)", "idleMin", new String[]{"2 min", "5 min", "10 min", "15 min", "Never"}, new Object[]{2, 5, 10, 15, 0}),
            new Row("Idle mode photos", "idlePhotoMin", new String[]{"Every 2 min", "Every 5 min", "Every 15 min", "Like home screen"}, new Object[]{2, 5, 15, 0}),
            new Row("Now playing (music)", "nowPlaying", ON_OFF, TF),
            new Row("Song lyrics (idle mode)", "lyrics", ON_OFF, TF),
            new Row("Score alerts · NFL team", "alertTeam", AlertService.TEAM_NAMES, AlertService.TEAM_IDS),
            new Row("Score alerts · close NFL games", "alertClose", new String[]{"Off", "On"}, new Object[]{false, true}),
            new Row("Score alerts · delay (match your TV)", "alertDelay", new String[]{"None", "15 s", "30 s", "45 s", "1 min", "90 s"}, new Object[]{0, 15, 30, 45, 60, 90}),
            new Row("Score alerts · test", "alertTest", null, null),
            new Row("Screen sleeps after", "sleepMin", new String[]{"1 hour", "2 hours", "4 hours", "8 hours", "12 hours", "Never"}, new Object[]{60, 120, 240, 480, 720, 0}),
            new Row("Clock weight", "weight", new String[]{"Light", "Regular", "Bold", "Heavy"}, new Object[]{500, 600, 700, 800}),
            new Row("Glass strength", "glass", new String[]{"Clear", "Normal", "Frosted"}, new Object[]{0, 1, 2}),
            new Row("Clock", "hour24", new String[]{"12-hour", "24-hour"}, new Object[]{false, true}),
            new Row("Date under clock", "showDate", ON_OFF, TF),
            new Row("Game line", "gameLine", ON_OFF, TF),
            new Row("\"Also\" line", "alsoLine", ON_OFF, TF),
            new Row("NFL games", "league_nfl", ON_OFF, TF),
            new Row("MLB games", "league_mlb", ON_OFF, TF),
            new Row("NHL games", "league_nhl", ON_OFF, TF),
            new Row("NBA games", "league_nba", ON_OFF, TF),
            new Row("Dim wallpaper at night", "dimNight", new String[]{"Off", "On"}, new Object[]{false, true}),
            new Row("Show hidden apps", "showHidden", new String[]{"Off", "On"}, new Object[]{false, true}),
            new Row("Reset app order", "reset", null, null),
            new Row("TV input · HDMI 1", "input_1", Apps.DEVICE_NAMES, Apps.DEVICE_IDS),
            new Row("TV input · HDMI 2", "input_2", Apps.DEVICE_NAMES, Apps.DEVICE_IDS),
            new Row("TV input · HDMI 3", "input_3", Apps.DEVICE_NAMES, Apps.DEVICE_IDS),
            new Row("TV input · HDMI 4", "input_4", Apps.DEVICE_NAMES, Apps.DEVICE_IDS),
            new Row("Open the old launcher (FLauncher)", "flauncher", null, null),
            new Row("Home server (game line)", "server", null, null),
            new Row("Run setup again", "setup", null, null),
            new Row("This photo", "credit", null, null),
            new Row("About", "about", null, null),
    };

    private Object current(Row row) {
        if (row.key.equals("wallpaper")) { String w = settings.getString(row.key, "auto"); return w.equals("shuffle") ? "any" : w; }
        if (row.key.equals("photoEvery")) return settings.getInt(row.key, 60);
        if (row.key.equals("units")) return units();
        if (row.key.equals("idlePhotoMin")) return settings.getInt(row.key, 2);
        if (row.key.equals("theme")) return settings.getString(row.key, "orbit");
        if (row.key.equals("weight")) { int w = settings.getInt(row.key, 600); return w < 500 ? 600 : w; }
        if (row.key.equals("glass")) return settings.getInt(row.key, 1);
        if (row.key.equals("idleMin")) return settings.getInt(row.key, 5);
        if (row.key.equals("sleepMin")) return settings.getInt(row.key, 240);
        if (row.key.startsWith("input_")) return settings.getString(row.key, "off");
        if (row.key.equals("alertTeam")) return settings.getString(row.key, "off");
        if (row.key.equals("alertDelay")) return settings.getInt(row.key, 30);
        boolean defaultOn = row.key.startsWith("league_") || row.key.equals("showDate") || row.key.equals("gameLine") || row.key.equals("alsoLine") || row.key.equals("caption") || row.key.equals("weather") || row.key.equals("nowPlaying") || row.key.equals("lyrics");
        return settings.getBoolean(row.key, defaultOn);
    }

    private int currentIndex(Row row) {
        Object cur = current(row);
        for (int i = 0; i < row.values.length; i++) if (row.values[i].equals(cur)) return i;
        return 0;
    }

    private void setValue(Row row, int idx) {
        Object v = row.values[(idx + row.values.length) % row.values.length];
        SharedPreferences.Editor ed = settings.edit();
        if (v instanceof String) ed.putString(row.key, (String) v);
        else if (v instanceof Integer) ed.putInt(row.key, (Integer) v);
        else ed.putBoolean(row.key, (Boolean) v);
        ed.apply();
        if (row.key.equals("showHidden")) reloadApps();
        if (row.key.startsWith("input_")) inputChanged(row.key.charAt(6) - '0', (String) v);
        if (row.key.equals("units") || row.key.equals("weather")) host.configChanged();
        if (row.key.startsWith("alert")) AlertService.start(getContext());
        tick();
        host.settingsChanged();
    }

    /** An HDMI port got a device (or Off): rebuild the shelf; during setup, a new tile counts as picked. */
    private void inputChanged(int port, String dev) {
        apps.load();
        reloadApps();
        if (setupStep < 0 || picked.isEmpty()) return;
        String pkg = Apps.INPUT + port;
        if (dev.equals("off")) picked.remove(pkg);
        else if (!picked.contains(pkg)) picked.add(pkg);
    }

    private void drawSettings(Canvas c) {
        c.drawColor(Color.argb(110, 0, 0, 0));
        int visibleRows = 12;
        float rowH = 60, w = 980, h = 120 + visibleRows * rowH + 24;
        r.set(960 - w / 2, 540 - h / 2, 960 + w / 2, 540 + h / 2);
        smoked = true;
        glass(c, r, 40, 1, 0.1f);
        smoked = false;
        float left = r.left, top = r.top;
        textStyle(t500, 40, 0, Color.WHITE);
        c.drawText("Settings", left + 44, top + 72, text);
        textStyle(t400, 22, 0, alpha(0.6f));
        String hint = "◀ ▶ change · OK select · Back close";
        c.drawText(hint, left + w - 44 - text.measureText(hint), top + 70, text);
        if (setSel < setScroll) setScroll = setSel;
        if (setSel >= setScroll + visibleRows) setScroll = setSel - visibleRows + 1;
        for (int vi = 0; vi < visibleRows && setScroll + vi < rowsDef.length; vi++) {
            int i = setScroll + vi;
            Row row = rowsDef[i];
            float y = top + 112 + vi * rowH;
            boolean sel = i == setSel;
            if (sel) {
                fill.setColor(Color.argb(52, 255, 255, 255));
                c.drawRoundRect(new RectF(left + 18, y, left + w - 18, y + rowH - 6), 16, 16, fill);
            }
            textStyle(sel ? t500 : t400, 27, 0, alpha(sel ? 1 : 0.82f));
            c.drawText(row.label, left + 44, y + 38, text);
            String val;
            if (row.values != null) val = (sel ? "‹  " : "") + row.names[currentIndex(row)] + (sel ? "  ›" : "");
            else if (row.key.equals("reset")) val = resetArmed ? "Press OK again to reset" : "";
            else if (row.key.equals("about")) val = "Orbit " + appVersion() + " · photos: NASA, NPS, Wikimedia Commons";
            else if (row.key.equals("credit")) val = credit;
            else if (row.key.equals("location")) val = settings.getString("locName", "Not set") + (sel ? "  ·  OK to change" : "");
            else if (row.key.equals("server")) val = settings.getString("server", "").isEmpty() ? (sel ? "None  ·  OK to add" : "None")
                    : settings.getString("server", "").replace("http://", "");
            else val = sel ? "OK ›" : "";
            if (row.key.startsWith("input_") && sel && !apps.inputsWork()) val = "Needs a home server on Fire TV   " + val;
            if (row.key.equals("alertTest")) val = sel ? "OK to show one ›" : "";
            textStyle(t400, 26, 0, alpha(sel ? 0.95f : 0.65f));
            c.drawText(val, left + w - 44 - text.measureText(val), y + 38, text);
        }
    }

    private boolean settingsKey(int code) {
        Row row = rowsDef[setSel];
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                setSel = Math.max(0, setSel - 1); resetArmed = false; break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                setSel = Math.min(rowsDef.length - 1, setSel + 1); resetArmed = false; break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (row.values != null) setValue(row, currentIndex(row) - 1); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (row.values != null) setValue(row, currentIndex(row) + 1); break;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (row.values != null) setValue(row, currentIndex(row) + 1);
                else if (row.key.equals("reset")) {
                    if (resetArmed) { apps.resetOrder(); reloadApps(); resetArmed = false; showToast("App order reset"); }
                    else resetArmed = true;
                } else if (row.key.equals("flauncher")) { settingsOpen = false; host.openFLauncher(); }
                else if (row.key.equals("location")) searchLocation();
                else if (row.key.equals("server")) askServer();
                else if (row.key.equals("setup")) { settingsOpen = false; startSetup(); }
                else if (row.key.equals("alertTest")) {
                    if (!AlertService.canShow(getContext())) showToast("Orbit needs the \"draw over other apps\" permission (see README)");
                    else { settingsOpen = false; AlertService.test(getContext()); }
                }
                break;
            case KeyEvent.KEYCODE_BACK: case KeyEvent.KEYCODE_MENU:
                settingsOpen = false; resetArmed = false; break;
            default:
                return false;
        }
        invalidate();
        return true;
    }


    // ================= first-run setup =================
    // Six screens in the same smoked-glass panel as Settings: welcome, apps, TV inputs, weather, home server, look.
    // Runs on a fresh install (settings "setupDone" unset) and from Settings → "Run setup again".

    private int setupStep = -1, sSel, sScroll;
    private final List<String> picked = new ArrayList<>();
    private String serverStatus = "", setupNote = "";
    private boolean locating;
    private final Weather weatherApi = new Weather();

    private static final class SRow {
        final String label;
        final String value;
        final Runnable ok;
        final java.util.function.IntConsumer side; // ◀ ▶
        Bitmap icon;
        boolean header; // a section title, not selectable
        SRow(String label, String value, Runnable ok, java.util.function.IntConsumer side) {
            this.label = label; this.value = value; this.ok = ok; this.side = side;
        }
    }

    void startSetup() {
        if (moving) cancelMove();
        if (idle) exitIdle();
        menuApp = null;
        settingsOpen = false;
        expanded = false;
        setupStep = 0;
        sSel = sScroll = 0;
        invalidate();
    }

    private void goStep(int step) {
        setupStep = step;
        sSel = sScroll = 0;
        setupNote = "";
        if (step == 1 && picked.isEmpty()) for (Apps.App a : apps.visible(false)) picked.add(a.pkg);
        if (step == 3 && !settings.contains("lat") && !locating) {
            locating = true;
            weatherApi.guess(p -> {
                locating = false;
                if (p != null && !settings.contains("lat")) setPlace(p);
                else if (p == null) setupNote = "Couldn't work out where you are — pick your city.";
                invalidate();
            });
        }
        if (step == 4 && !settings.getString("server", "").isEmpty() && serverStatus.isEmpty()) testServer();
        invalidate();
    }

    private void finishSetup() {
        if (!picked.isEmpty()) apps.applyPicks(picked); // empty: skipped from the welcome screen, keep the defaults
        picked.clear();
        settings.edit().putBoolean("setupDone", true).apply();
        setupStep = -1;
        reloadApps();
        focus = 0;
        host.configChanged();
        tick();
        showToast("All set · hold OK on a tile to move or hide it");
    }

    private void setPlace(Weather.Place p) {
        settings.edit().putString("locName", p.label()).putString("lat", String.valueOf(p.lat))
                .putString("lon", String.valueOf(p.lon)).apply();
        host.configChanged();
        lastClockKey = "";
        tick();
    }

    /** Type a city, pick the right one if several match. */
    private void searchLocation() {
        host.askText("Your city or town", "e.g. Brooklyn, or Portland Oregon", "", q -> {
            if (q == null || q.isEmpty()) return;
            setupNote = "Searching…";
            invalidate();
            weatherApi.search(q, list -> {
                setupNote = "";
                if (list == null || list.isEmpty()) { showToast("No place called \"" + q + "\""); invalidate(); return; }
                if (list.size() == 1) { setPlace(list.get(0)); invalidate(); return; }
                String[] names = new String[list.size()];
                for (int i = 0; i < names.length; i++) names[i] = list.get(i).label();
                host.choose("Which one?", names, i -> { setPlace(list.get(i)); invalidate(); });
            });
        });
    }

    /** The Orbit helper's address, e.g. <server-ip>:8788 (http:// and :8788 are added if left out). */
    private void askServer() {
        String cur = settings.getString("server", "").replace("http://", "");
        host.askText("Home server address", "server IP, e.g. 192.168.0.20 (empty = none)", cur, t -> {
            String url = t == null ? "" : t.trim();
            if (!url.isEmpty()) {
                if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://" + url;
                if (!url.substring(url.indexOf("//") + 2).contains(":")) url += ":8788";
                while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
            }
            settings.edit().putString("server", url).apply();
            host.configChanged();
            serverStatus = "";
            if (!url.isEmpty()) testServer();
            invalidate();
        });
    }

    private void testServer() {
        String url = settings.getString("server", "");
        if (url.isEmpty()) { serverStatus = ""; return; }
        serverStatus = "Checking…";
        host.testServer(url, r -> { serverStatus = r; invalidate(); });
    }

    private Row row(String key) {
        for (Row r : rowsDef) if (r.key.equals(key)) return r;
        return null;
    }

    /** A Settings row, reused on the last setup screen. */
    private SRow settingRow(String key, String label) {
        Row r = row(key);
        return new SRow(label, r.names[currentIndex(r)], () -> setValue(r, currentIndex(r) + 1), d -> setValue(r, currentIndex(r) + d));
    }

    private String setupTitle() {
        switch (setupStep) {
            case 0: return "Welcome to Orbit";
            case 1: return "Choose your apps";
            case 2: return "TV inputs";
            case 3: return "Weather and sunrise";
            case 4: return "Live sports (optional)";
            default: return "Make it yours";
        }
    }

    private String setupText() {
        switch (setupStep) {
            case 0: return "A calm home screen for your TV: a glass clock over photos that follow the time of day, "
                    + "your apps on a glass shelf, and an idle mode that turns the TV into a photo frame. "
                    + "A few quick choices and you're done — everything can be changed later in Settings (the gear, top right).";
            case 1: return "OK adds or removes an app. They show up in the order you pick them. "
                    + "Later: hold OK on a tile to move or hide it.";
            case 2: return "What's plugged into the TV? Each one gets a tile on the shelf that switches the TV to it, "
                    + "like an app. Leave a port Off if nothing's there."
                    + (Apps.amazon() ? " Fire TV doesn't let apps switch inputs, so here the tiles need a home server "
                    + "running the Orbit helper (two steps on)." : "");
            case 3: return "Your location sets the weather in the corner and when the photos switch to dawn, golden hour and night. "
                    + "It's guessed from your internet connection — change it if it's off.";
            case 4: return "With a home server running Sports Remote and the Orbit helper, a line under the clock shows the best "
                    + "game on right now, with live scores, and OK starts it. No server? Skip this — everything else works without one.";
            default: return "Pick a photo theme and clock style. Settings has more: idle mode, how often photos change, glass strength.";
        }
    }

    private List<SRow> setupRows() {
        List<SRow> rows = new ArrayList<>();
        switch (setupStep) {
            case 0:
                rows.add(new SRow("Start", "OK ›", () -> goStep(1), null));
                rows.add(new SRow("Skip setup and use the defaults", "", this::finishSetup, null));
                break;
            case 1:
                List<Apps.App> every = apps.visible(true);
                rows.add(new SRow("Continue with " + picked.size() + " of " + every.size() + " apps", "OK ›", () -> goStep(2), null));
                // On the home screen (in pick order), then everything else installed, A to Z.
                List<Apps.App> on = new ArrayList<>(), off = new ArrayList<>();
                for (String pkg : picked) for (Apps.App a : every) if (a.pkg.equals(pkg)) on.add(a);
                for (Apps.App a : every) if (!picked.contains(a.pkg)) off.add(a);
                off.sort((x, y) -> x.label.compareToIgnoreCase(y.label));
                for (int part = 0; part < 2; part++) {
                    List<Apps.App> list = part == 0 ? on : off;
                    if (list.isEmpty()) continue;
                    SRow h = new SRow(part == 0 ? "On your home screen" : "Other apps on this TV · OK to add", "", null, null);
                    h.header = true;
                    rows.add(h);
                    for (Apps.App a : list) {
                        int n = picked.indexOf(a.pkg);
                        SRow sr = new SRow(a.label, n >= 0 ? String.valueOf(n + 1) : "", () -> {
                            if (picked.contains(a.pkg)) picked.remove(a.pkg); else picked.add(a.pkg);
                        }, null);
                        sr.icon = a.banner;
                        rows.add(sr);
                    }
                }
                break;
            case 2:
                for (int n = 1; n <= 4; n++) rows.add(settingRow("input_" + n, "HDMI " + n));
                rows.add(new SRow("Continue", "OK ›", () -> goStep(3), null));
                break;
            case 3:
                rows.add(new SRow("Location", locating ? "Finding…" : settings.getString("locName", "Not set"), this::searchLocation, null));
                Row u = row("units");
                rows.add(new SRow("Temperature", u.names[currentIndex(u)], () -> setValue(u, currentIndex(u) + 1), d -> setValue(u, currentIndex(u) + d)));
                rows.add(settingRow("weather", "Weather in the corner"));
                rows.add(new SRow("Continue", "OK ›", () -> goStep(4), null));
                break;
            case 4:
                String sv = settings.getString("server", "");
                rows.add(new SRow("Home server", sv.isEmpty() ? "None" : sv.replace("http://", ""), this::askServer, null));
                if (!sv.isEmpty()) {
                    rows.add(new SRow("Status", serverStatus, this::testServer, null));
                    rows.add(new SRow("Remove server", "", () -> {
                        settings.edit().putString("server", "").apply();
                        serverStatus = "";
                        host.configChanged();
                    }, null));
                }
                rows.add(new SRow(sv.isEmpty() ? "Skip" : "Continue", "OK ›", () -> goStep(5), null));
                break;
            default:
                rows.add(settingRow("theme", "Theme"));
                rows.add(settingRow("wallpaper", "Photos"));
                rows.add(settingRow("photoEvery", "Change photo"));
                rows.add(settingRow("hour24", "Clock"));
                rows.add(settingRow("weight", "Clock weight"));
                rows.add(new SRow("Finish", "OK ✓", this::finishSetup, null));
        }
        return rows;
    }

    private boolean setupKey(int code) {
        List<SRow> rows = setupRows();
        if (rows.isEmpty()) return true;
        sSel = Math.max(0, Math.min(sSel, rows.size() - 1));
        SRow cur = rows.get(sSel);
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: // skip section titles
                do sSel--; while (sSel > 0 && rows.get(sSel).header);
                sSel = Math.max(0, sSel);
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                int next = sSel;
                do next++; while (next < rows.size() && rows.get(next).header);
                if (next < rows.size()) sSel = next;
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT: if (cur.side != null) cur.side.accept(-1); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (cur.side != null) cur.side.accept(1); break;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (cur.ok != null) cur.ok.run();
                break;
            case KeyEvent.KEYCODE_BACK: // back a screen; on the first one, leave (only when run again from Settings)
                if (setupStep > 0) goStep(setupStep - 1);
                else if (settings.getBoolean("setupDone", false)) { setupStep = -1; picked.clear(); }
                break;
            default:
                return false;
        }
        invalidate();
        return true;
    }

    /** Word-wraps s to width w with the current text paint. */
    private List<String> wrap(String s, float w) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            String tryLine = line.length() == 0 ? word : line + " " + word;
            if (text.measureText(tryLine) > w && line.length() > 0) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(tryLine);
            }
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines;
    }

    private void drawSetup(Canvas c) {
        c.drawColor(Color.argb(130, 0, 0, 0));
        List<SRow> rows = setupRows();
        sSel = Math.max(0, Math.min(sSel, rows.size() - 1));
        float w = 1120, rowH = 62;
        textStyle(t400, 25, 0, 0);
        List<String> body = wrap(setupText(), w - 88);
        int visible = Math.min(rows.size(), Math.max(4, 11 - body.size()));
        float bodyH = body.size() * 36 + 24;
        float h = 112 + bodyH + visible * rowH + (setupNote.isEmpty() ? 28 : 64);
        r.set(960 - w / 2, 540 - h / 2, 960 + w / 2, 540 + h / 2);
        smoked = true;
        glass(c, r, 40, 1, 0.1f);
        smoked = false;
        float left = r.left, top = r.top, bottom = r.bottom;
        textStyle(t500, 42, 0, Color.WHITE);
        c.drawText(setupTitle(), left + 44, top + 74, text);
        textStyle(t400, 22, 0.02f, alpha(0.55f));
        String steps = "STEP " + (setupStep + 1) + " OF 6";
        c.drawText(steps, left + w - 44 - text.measureText(steps), top + 70, text);
        textStyle(t400, 25, 0, alpha(0.78f));
        for (int i = 0; i < body.size(); i++) c.drawText(body.get(i), left + 44, top + 124 + i * 36, text);
        float y0 = top + 112 + bodyH;
        if (sSel < sScroll) sScroll = sSel;
        if (sSel >= sScroll + visible) sScroll = sSel - visible + 1;
        for (int vi = 0; vi < visible && sScroll + vi < rows.size(); vi++) {
            int i = sScroll + vi;
            SRow row = rows.get(i);
            float y = y0 + vi * rowH;
            boolean sel = i == sSel;
            if (row.header) {
                textStyle(t600, 19, 0.1f, alpha(0.5f));
                c.drawText(row.label.toUpperCase(Locale.US), left + 44, y + 44, text);
                continue;
            }
            if (sel) {
                fill.setColor(Color.argb(56, 255, 255, 255));
                r.set(left + 18, y, left + w - 18, y + rowH - 6);
                c.drawRoundRect(r, 16, 16, fill);
            }
            float tx = left + 44;
            if (row.icon != null) { // app banner
                r.set(tx, y + 6, tx + 80, y + 51);
                c.drawBitmap(row.icon, null, r, bmp);
                tx += 100;
            }
            textStyle(sel ? t500 : t400, 27, 0, alpha(sel ? 1 : 0.84f));
            c.drawText(row.label, tx, y + 39, text);
            String val = row.side != null && sel ? "‹  " + row.value + "  ›" : row.value;
            boolean num = setupStep == 1 && i > 0 && !row.value.isEmpty();
            if (num) { // pick order badge
                fill.setColor(Color.WHITE);
                float bx = left + w - 64;
                c.drawCircle(bx, y + 29, 19, fill);
                textStyle(t600, 22, 0, Color.rgb(11, 13, 20));
                c.drawText(val, bx - text.measureText(val) / 2, y + 37, text);
            } else {
                textStyle(t400, 26, 0, alpha(sel ? 0.95f : 0.65f));
                c.drawText(val, left + w - 44 - text.measureText(val), y + 39, text);
            }
        }
        if (rows.size() > visible) { // more below / above
            textStyle(t400, 20, 0, alpha(0.5f));
            String more = (sScroll + visible < rows.size() ? "▼ more" : "") + (sScroll > 0 ? "   ▲" : "");
            c.drawText(more, left + w - 44 - text.measureText(more), y0 + visible * rowH + 18, text);
        }
        if (!setupNote.isEmpty()) {
            textStyle(t400, 23, 0, alpha(0.75f));
            c.drawText(setupNote, left + 44, bottom - 26, text);
        }
    }


    // ================= music (now playing) =================

    private boolean music() {
        return nowPlaying != null && nowPlaying.controllable() && settings.getBoolean("nowPlaying", true);
    }

    /**
     * Remote's media buttons. ⏯: play/pause the music when there is some, else idle mode on/off; hold ⏯ for
     * idle mode either way. ◀◀ ▶▶: previous/next song (in idle with no music: the photo).
     */
    private boolean mediaKey(KeyEvent ev) {
        int code = ev.getKeyCode();
        boolean pp = code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PLAY || code == KeyEvent.KEYCODE_MEDIA_PAUSE;
        boolean skip = code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || code == KeyEvent.KEYCODE_MEDIA_NEXT
                || code == KeyEvent.KEYCODE_MEDIA_REWIND || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS;
        boolean photo = code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_CHANNEL_DOWN
                || code == KeyEvent.KEYCODE_PAGE_UP || code == KeyEvent.KEYCODE_PAGE_DOWN;
        if (!pp && !skip && !photo) return false;
        if (photo) { // the up/down rocker next to volume: next / previous photo
            if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0 && setupStep < 0 && !settingsOpen && menuApp == null) {
                lastInput = SystemClock.uptimeMillis();
                stepPhoto(code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_PAGE_UP ? 1 : -1);
            }
            return true;
        }
        if (setupStep >= 0 || settingsOpen || menuApp != null) return true;
        lastInput = SystemClock.uptimeMillis();
        if (pp) {
            if (ev.getAction() == KeyEvent.ACTION_DOWN) {
                if (ev.getRepeatCount() == 0) { ppDown = true; ppLong = false; }
                else if (!ppLong) { ppLong = true; if (idle) exitIdle(); else enterIdle(); }
            } else if (ev.getAction() == KeyEvent.ACTION_UP) {
                if (ppDown && !ppLong) {
                    if (music()) nowPlaying.toggle();
                    else if (idle) exitIdle();
                    else enterIdle();
                }
                ppDown = false;
            }
            invalidate();
            return true;
        }
        if (ev.getAction() != KeyEvent.ACTION_DOWN || ev.getRepeatCount() > 0) return true;
        boolean fwd = code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD || code == KeyEvent.KEYCODE_MEDIA_NEXT;
        if (music()) { if (fwd) nowPlaying.next(); else nowPlaying.previous(); }
        invalidate();
        return true;
    }

    /** Next / previous photo by hand. On the home screen it stays until the next scheduled change. */
    private void stepPhoto(int d) {
        if (idle) { nextIdlePhoto(d); return; }
        List<String> list = new ArrayList<>();
        for (Photo p : pool(wantedPhase())) list.add(p.key);
        if (list.isEmpty()) return;
        int i = list.indexOf(loadedPhase);
        heldPhoto = list.get(((i < 0 ? 0 : i) + d + list.size()) % list.size());
        heldFor = currentPhoto();
        tick();
    }

    void musicChanged() {
        invalidate();
    }

    private static String clock(long ms) {
        long s = ms / 1000;
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    private String fit(String s, float max) {
        if (text.measureText(s) <= max) return s;
        int n = s.length();
        while (n > 1 && text.measureText(s, 0, n) + text.measureText("…") > max) n--;
        return s.substring(0, n) + "…";
    }

    /** Idle mode: album art, song, artist and progress in a glass card across the lower part of the screen. */
    private void drawMusicCard(Canvas c, float a) {
        if (a <= 0.01f || nowPlaying == null) return;
        NowPlaying np = nowPlaying;
        float ta = np.skipping() ? 0.5f : 1; // the song text dims while a skip is on its way
        if (np.skipping()) postInvalidateDelayed(250);
        float w = 860, h = 184, x = 960 - w / 2 + idleDx * idleAmt, y = 812 + idleDy * idleAmt;
        if (settings.getBoolean("lyrics", true) && !np.skipping()) drawLyrics(c, np, a, 960 + idleDx * idleAmt, y);
        r.set(x, y, x + w, y + h);
        glass(c, r, 40, a, 0.06f);
        float art = 136, ax = x + 24, ay = y + 24;
        if (np.art != null) {
            if (artFor != np.art) { // new cover: fade it in over the old one
                if (artFor != null) {
                    artPrev = artFor;
                    artPrevPaint.setShader(artPaint.getShader());
                }
                artPaint.setShader(new BitmapShader(np.art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                artFor = np.art;
                artAt = SystemClock.uptimeMillis();
            }
            float t = Math.min(1, (SystemClock.uptimeMillis() - artAt) / (float) ART_FADE_MS);
            r.set(ax, ay, ax + art, ay + art);
            if (t < 1 && artPrev != null) {
                drawArt(c, artPrevPaint, artPrev, ax, ay, art, a);
                postInvalidateOnAnimation();
            }
            drawArt(c, artPaint, np.art, ax, ay, art, a * (artPrev == null ? 1 : t));
            if (t >= 1) artPrev = null;
        } else {
            fill.setColor(Color.argb((int) (60 * a), 255, 255, 255));
            r.set(ax, ay, ax + art, ay + art);
            c.drawRoundRect(r, 18, 18, fill);
            drawBars(c, ax + art / 2 - 18, ay + art / 2 + 22, 44, a);
        }
        float tx = ax + art + 30, tw = x + w - 30 - tx;
        textStyle(t500, 36, 0, alpha(a * ta));
        c.drawText(fit(np.title, tw), tx, y + 70, text);
        textStyle(t400, 27, 0, alpha(0.72f * a * ta));
        c.drawText(fit(np.artist, tw), tx, y + 108, text);
        if (np.duration > 0) { // progress
            float py = y + 140, frac = np.position() / (float) np.duration;
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeWidth(5);
            stroke.setColor(Color.argb((int) (70 * a), 255, 255, 255));
            c.drawLine(tx, py, tx + tw, py, stroke);
            stroke.setColor(Color.argb((int) (235 * a), 255, 255, 255));
            c.drawLine(tx, py, tx + tw * Math.max(0.005f, Math.min(1, frac)), py, stroke);
            stroke.setStrokeCap(Paint.Cap.BUTT);
            textStyle(t400, 20, 0, alpha(0.6f * a));
            c.drawText(clock(np.position()), tx, py + 30, text);
            String left = "-" + clock(Math.max(0, np.duration - np.position())); // time left, counting down like Spotify
            c.drawText(left, tx + tw - text.measureText(left), py + 30, text);
        }
    }

    private int lyricLine = -2;
    private long lyricAt;
    private static final int LYRIC_MS = 380;

    /**
     * Idle mode: the line being sung, big, just above the music card, with the next line dimmed under it.
     * When the line changes, the old one drifts up and fades while the next one grows into its place.
     */
    private void drawLyrics(Canvas c, NowPlaying np, float a, float cx, float cardTop) {
        Lyrics ly = np.lyrics;
        if (!ly.has()) { lyricLine = -2; return; }
        long pos = np.position();
        int i = ly.index(pos);
        long now = SystemClock.uptimeMillis();
        if (i != lyricLine) {
            lyricAt = Math.abs(i - lyricLine) == 1 ? now : now - LYRIC_MS; // a jump (seek, new song): no animation
            lyricLine = i;
        }
        float t = Math.min(1, (now - lyricAt) / (float) LYRIC_MS);
        t = 1 - (1 - t) * (1 - t) * (1 - t); // ease out
        if (t < 1) postInvalidateOnAnimation();
        else if (np.playing) {
            long wait = ly.untilNext(pos);
            if (wait > 0) postInvalidateDelayed(Math.min(wait, 1000));
        }
        float curY = cardTop - 102, nextY = cardTop - 36, max = 1640;
        String cur = line(ly, i), next = line(ly, i + 1), prev = line(ly, i - 1);
        text.setShadowLayer(18, 0, 2, Color.argb((int) (150 * a), 0, 0, 0));
        if (t < 1 && prev != null) { // the old line leaving
            float out = Math.min(1, t * 1.6f); // gone before the new line arrives
            textStyle(t600, 50, 0, alpha(a * (1 - out)));
            drawCentered(c, fit(prev, max), cx, curY - 70 * out);
        }
        if (cur != null) { // the current line, growing from the "next" spot
            float size = 34 + 16 * t;
            textStyle(t600, size, 0, alpha(a * (0.5f + 0.5f * t)));
            drawCentered(c, fit(cur, max), cx, nextY + (curY - nextY) * t);
        }
        if (next != null) {
            textStyle(t500, 34, 0, alpha(a * 0.5f * Math.max(0, (t - 0.6f) / 0.4f))); // fades in once the spot is free
            drawCentered(c, fit(next, max), cx, nextY);
        }
        text.clearShadowLayer();
    }

    /** A lyric line to show, "♪" for an instrumental gap, null past either end. */
    private static String line(Lyrics ly, int i) {
        if (i < 0 || i >= ly.lines.length) return null;
        return ly.lines[i].isEmpty() ? "♪" : ly.lines[i];
    }

    private void drawArt(Canvas c, android.graphics.Paint p, Bitmap b, float ax, float ay, float size, float a) {
        float sc = size / Math.min(b.getWidth(), b.getHeight());
        artMatrix.setScale(sc, sc);
        artMatrix.postTranslate(ax, ay);
        p.getShader().setLocalMatrix(artMatrix);
        p.setAlpha((int) (255 * a));
        r.set(ax, ay, ax + size, ay + size);
        c.drawRoundRect(r, 18, 18, p);
    }

    /** Home screen: a small "song · artist" pill top right, next to the gear. */
    private void drawMusicPill(Canvas c, float a) {
        if (a <= 0.01f || nowPlaying == null) return;
        NowPlaying np = nowPlaying;
        if (np.skipping()) { a *= 0.6f; postInvalidateDelayed(250); }
        textStyle(t500, 24, 0, 0);
        String t = fit(np.title, 360);
        float tw = text.measureText(t);
        textStyle(t400, 22, 0, 0);
        String ar = fit(np.artist, 260);
        float aw = text.measureText(ar);
        float h = 56, w = 20 + 26 + 14 + tw + (aw > 0 ? 14 + aw : 0) + 24;
        float right = GEAR_CX - GEAR_R - 22, top = GEAR_CY - h / 2;
        r.set(right - w, top, right, top + h);
        glass(c, r, h / 2, a, 0.04f);
        float x = r.left + 20;
        if (bars != null) { // the bars are their own layer (smooth animation); just move and fade it
            float sc = getWidth() / 1920f;
            if (bars.getLayoutParams() == null || bars.getLayoutParams().width != Math.round(26 * sc)) {
                android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(Math.round(26 * sc), Math.round(26 * sc));
                bars.setLayoutParams(lp);
            }
            bars.setX(x * sc);
            bars.setY((top + h / 2 + 11 - 26) * sc);
            if (Math.abs(bars.getAlpha() - a) > 0.01f) bars.setAlpha(a);
            if (bars.playing != np.playing) { bars.playing = np.playing; bars.invalidate(); }
            else if (np.playing && a > 0.01f) bars.postInvalidateOnAnimation();
            barsPlaced = true;
        } else drawBars(c, x, top + h / 2 + 11, 26, a);
        x += 26 + 14;
        textStyle(t500, 24, 0, alpha(a));
        c.drawText(t, x, top + h / 2 + 8.5f, text);
        x += tw + 14;
        textStyle(t400, 22, 0, alpha(0.7f * a));
        c.drawText(ar, x, top + h / 2 + 8, text);
    }

    /** A little equalizer: three bars that move while music plays. */
    private void drawBars(Canvas c, float x, float base, float size, float a) {
        long t = SystemClock.uptimeMillis();
        boolean on = nowPlaying != null && nowPlaying.playing;
        fill.setColor(alpha(0.9f * a));
        float bw = size / 5;
        for (int i = 0; i < 3; i++) {
            float k = on ? 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(t / (260.0 + i * 70) + i * 1.7)) : 0.35f;
            float bh = size * k;
            r.set(x + i * bw * 2, base - bh, x + i * bw * 2 + bw, base);
            c.drawRoundRect(r, bw / 2, bw / 2, fill);
        }
    }

    // ================= keys =================

    boolean onKey(KeyEvent ev) {
        int code = ev.getKeyCode();
        if (mediaKey(ev)) return true;
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (ev.getAction() == KeyEvent.ACTION_UP) {
            if (ok && okDown) {
                okDown = false;
                if (!okLong) activate();
                okLong = false;
                invalidate();
                return true;
            }
            return code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU;
        }
        if (ev.getAction() != KeyEvent.ACTION_DOWN) return false;
        lastInput = SystemClock.uptimeMillis();
        if (idle) { // in idle: any button (other than media / photo buttons) wakes the home screen
            exitIdle();
            invalidate();
            return true;
        }

        if (setupStep >= 0) {
            if (ok) { if (ev.getRepeatCount() == 0) { okDown = true; okLong = true; setupKey(code); } return true; }
            setupKey(code);
            invalidate();
            return true;
        }
        if (settingsOpen) {
            if (ok) { if (ev.getRepeatCount() == 0) { okDown = true; okLong = true; settingsKey(code); } return true; }
            return settingsKey(code) || true;
        }
        if (menuApp != null) {
            if (ok) { if (ev.getRepeatCount() == 0) { okDown = true; okLong = true; menuPick(); } return true; }
            if (code == KeyEvent.KEYCODE_DPAD_UP) menuSel = Math.max(0, menuSel - 1);
            else if (code == KeyEvent.KEYCODE_DPAD_DOWN) menuSel = Math.min(menuItems.size() - 1, menuSel + 1);
            else if (code == KeyEvent.KEYCODE_BACK) menuApp = null;
            invalidate();
            return true;
        }
        if (ok) {
            if (ev.getRepeatCount() == 0) { okDown = true; okLong = false; }
            else if (!okLong && ev.getRepeatCount() >= 1 && !moving) { okLong = true; longPress(); }
            return true;
        }
        if (moving) return moveKey(code);
        switch (code) {
            case KeyEvent.KEYCODE_MENU:
                settingsOpen = true; setSel = 0; setScroll = 0; break;
            case KeyEvent.KEYCODE_BACK:
                if (expanded) { expanded = false; if (focus >= COLS) focus = focus % COLS; }
                break;
            case KeyEvent.KEYCODE_DPAD_UP: up(); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: down(); break;
            case KeyEvent.KEYCODE_DPAD_LEFT: side(-1); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: side(1); break;
            default: return false;
        }
        invalidate();
        return true;
    }

    private int tileCount() {
        return visible.size();
    }

    private boolean hasGame() {
        return game != null;
    }

    private void up() {
        if (focus == GEAR) return;
        if (focus == GAME) { focus = GEAR; return; }
        if (focus == ALSO) { focus = GAME; return; }
        int row = focus / COLS;
        if (row == 0) {
            if (expanded) expanded = false;
            else focus = alsoGame != null ? ALSO : hasGame() ? GAME : GEAR;
        } else {
            focus -= COLS;
        }
    }

    private void down() {
        if (focus == GEAR) { focus = hasGame() ? GAME : Math.min(COLS - 1, tileCount() - 1); return; }
        if (focus == GAME && alsoGame != null) { focus = ALSO; return; }
        if (focus == GAME || focus == ALSO) { focus = Math.min(2, tileCount() - 1); return; } // middle of the row
        int next = focus + COLS;
        int lastRow = (tileCount() - 1) / COLS;
        if (focus / COLS >= lastRow) return;
        focus = Math.min(next, tileCount() - 1);
        expanded = true;
    }

    private void side(int d) {
        if (focus == GAME && d > 0) { focus = GEAR; return; }
        if (focus < 0) { if (focus == GEAR && d < 0) focus = hasGame() ? GAME : COLS - 1; return; } // ALSO: nothing sideways
        int next = focus + d;
        if (next < 0 || next >= tileCount()) return;
        if (!expanded && next / COLS != 0) return; // home: stay on the first row
        focus = next;
    }

    private void activate() {
        if (moving) { finishMove(); return; }
        if (focus == GAME) {
            if (game != null) { showToast("Opening " + game.title + "…"); host.launchGame(game); }
        } else if (focus == ALSO) {
            if (alsoGame != null) { showToast("Opening " + alsoGame.title + "…"); host.launchGame(alsoGame); }
        } else if (focus == GEAR) {
            settingsOpen = true; setSel = 0; setScroll = 0;
        } else if (focus >= 0 && focus < visible.size()) {
            host.launchApp(visible.get(focus));
        }
    }

    private void longPress() {
        if (focus == GEAR) { settingsOpen = true; setSel = 0; return; }
        if (focus < 0 || focus >= visible.size()) return;
        menuApp = visible.get(focus);
        menuItems = new ArrayList<>();
        menuItems.add("Move");
        menuItems.add(menuApp.hidden ? "Unhide" : "Hide");
        if (!Apps.isInput(menuApp)) menuItems.add("App info");
        if (!menuApp.system) menuItems.add("Uninstall");
        menuSel = 0;
        invalidate();
    }

    private void menuPick() {
        Apps.App a = menuApp;
        String item = menuItems.get(menuSel);
        menuApp = null;
        switch (item) {
            case "Move":
                moving = true;
                moveBackup = new ArrayList<>(apps.all);
                break;
            case "Hide":
                apps.setHidden(a, true);
                reloadApps();
                showToast(a.label + " hidden · Settings › Show hidden apps to bring it back");
                break;
            case "Unhide":
                apps.setHidden(a, false);
                reloadApps();
                break;
            case "App info":
                host.appInfo(a);
                break;
            case "Uninstall":
                host.uninstall(a);
                break;
        }
        invalidate();
    }

    private boolean moveKey(int code) {
        int d;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: d = -1; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: d = 1; break;
            case KeyEvent.KEYCODE_DPAD_UP: d = -COLS; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: d = COLS; break;
            case KeyEvent.KEYCODE_BACK: cancelMove(); invalidate(); return true;
            default: return true;
        }
        int to = focus + d;
        if (to < 0 || to >= visible.size()) return true; // never past the Settings tile
        Apps.App a = visible.get(focus), b = visible.get(to);
        // move a to b's position, shifting the apps in between
        List<Apps.App> order = new ArrayList<>(apps.all);
        order.remove(a);
        order.add(order.indexOf(b) + (d > 0 ? 1 : 0), a);
        apps.all.clear();
        apps.all.addAll(order);
        visible = apps.visible(settings.getBoolean("showHidden", false));
        focus = to;
        if (to / COLS > 0) expanded = true;
        invalidate();
        return true;
    }

    private void finishMove() {
        moving = false;
        apps.setOrder(new ArrayList<>(apps.all));
        reloadApps();
        showToast("Saved");
    }

    private void cancelMove() {
        moving = false;
        if (moveBackup != null) apps.setOrder(moveBackup);
        reloadApps();
    }

    // ================= small helpers =================

    private void textStyle(Typeface tf, float size, float spacingEm, int color) {
        text.setTypeface(tf);
        text.setTextSize(size);
        text.setLetterSpacing(spacingEm);
        text.setColor(color);
    }

    private static int alpha(float a) {
        return Color.argb((int) (255 * Math.max(0, Math.min(1, a))), 255, 255, 255);
    }

    private void drawCentered(Canvas c, String s, float cx, float baseline) {
        c.drawText(s, cx - text.measureText(s) / 2, baseline, text);
    }

    private void drawStar(Canvas c, float cx, float cy, float rad, int color) {
        Path p = new Path();
        for (int i = 0; i < 10; i++) {
            double ang = Math.PI / 2 + i * Math.PI / 5;
            float rr = i % 2 == 0 ? rad : rad * 0.45f;
            float x = cx + (float) (Math.cos(ang) * rr), y = cy - (float) (Math.sin(ang) * rr);
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        p.close();
        fill.setColor(color);
        c.drawPath(p, fill);
    }

    private void drawGear(Canvas c, float cx, float cy, float rad, int color) {
        fill.setColor(color);
        c.save();
        for (int i = 0; i < 8; i++) {
            c.rotate(45, cx, cy);
            c.drawRoundRect(new RectF(cx - 4.5f, cy - rad - 6, cx + 4.5f, cy - rad + 8), 2.5f, 2.5f, fill);
        }
        c.restore();
        stroke.setStrokeWidth(7);
        stroke.setColor(color);
        c.drawCircle(cx, cy, rad - 3, stroke);
        stroke.setStrokeWidth(3);
        c.drawCircle(cx, cy, 6, stroke);
    }
}
