package home.jw.orbit;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

/** Orbit: the home screen. On Fire TV, homewatch.sh (on the TV) opens this whenever Home is pressed. */
public class OrbitActivity extends Activity implements OrbitView.Host {
    private static final long GLANCE_EVERY_MS = 60_000;

    private Apps apps;
    private OrbitView view;
    private final Glance glance = new Glance();
    private final Weather weather = new Weather();
    private NowPlaying nowPlaying;
    private static final long WEATHER_EVERY_MS = 10 * 60_000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean resumed;

    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            view.tick();
            long now = System.currentTimeMillis();
            handler.postDelayed(this, 60_000 - (now % 60_000) + 50);
        }
    };

    private final Runnable glanceTick = new Runnable() {
        @Override
        public void run() {
            refreshGlance(); // schedules the next one when it lands
        }
    };

    private final Runnable weatherTick = new Runnable() {
        @Override
        public void run() {
            refreshWeather();
            handler.postDelayed(this, WEATHER_EVERY_MS);
        }
    };

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            apps.load();
            view.reloadApps();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        migrateInputs();
        apps = new Apps(this);
        apps.load();
        view = new OrbitView(this, this, apps);
        view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.addView(view);
        BarsView bars = new BarsView(this);
        root.addView(bars, new android.widget.FrameLayout.LayoutParams(52, 52));
        view.bars = bars;
        setContentView(root);
        applyConfig();
        nowPlaying = new NowPlaying(this, () -> view.musicChanged());
        view.nowPlaying = nowPlaying;
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packages, f);
        AlertService.start(this);
        if (getIntent().getBooleanExtra("idle", false)) { getIntent().removeExtra("idle"); view.idleOnResume = true; }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        view.resetHome();
        // homewatch.sh sends --ez idle true when it brings Orbit back after Spotify opened by itself
        if (intent.getBooleanExtra("idle", false)) { intent.removeExtra("idle"); view.idleOnResume = true; }
        if (intent.getBooleanExtra("testAlert", false)) { intent.removeExtra("testAlert"); AlertService.test(this); } // adb test hook
        maybeSnapshot(intent);
    }

    /**
     * Test hook: `am start -n home.jw.orbit/.OrbitActivity --es snapshot <name> [--es phase night] [--es photo <theme>/<id>]
     * [--ez expand true] [--ei focus 3] [--ez settings true] [--ez menu true] [--ez idleMode true]` draws the screen into
     * /sdcard/Android/data/home.jw.orbit/files/<name>.png (works with the TV screen off).
     */
    private void maybeSnapshot(Intent i) {
        final String name = i.getStringExtra("snapshot");
        if (name == null || !name.matches("[a-z0-9_-]{1,40}")) return;
        i.removeExtra("snapshot");
        final String phase = i.getStringExtra("phase"), photo = i.getStringExtra("photo");
        final boolean expand = i.getBooleanExtra("expand", false), settings = i.getBooleanExtra("settings", false),
                menu = i.getBooleanExtra("menu", false), goIdle = i.getBooleanExtra("idleMode", false);
        final int focus = i.getIntExtra("focus", 0);
        handler.postDelayed(() -> {
            view.debugState(phase, photo, expand, focus, settings, menu, goIdle);
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(1920, 1080, android.graphics.Bitmap.Config.ARGB_8888);
            view.layout(0, 0, 1920, 1080);
            view.draw(new android.graphics.Canvas(b));
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(getExternalFilesDir(null), name + ".png"))) {
                b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            } catch (java.io.IOException ignored) {
            }
            b.recycle();
        }, 2500);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        apps.load();
        view.reloadApps();
        handler.removeCallbacks(minuteTick);
        handler.removeCallbacks(glanceTick);
        handler.removeCallbacks(weatherTick);
        minuteTick.run();
        glanceTick.run();
        weatherTick.run();
        view.requestFocus();
        view.onResumed();
        nowPlaying.start();
        maybeSnapshot(getIntent());
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        view.onPaused();
        nowPlaying.stop();
        handler.removeCallbacks(weatherTick);
        handler.removeCallbacks(minuteTick);
        handler.removeCallbacks(glanceTick);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        int code = ev.getKeyCode();
        if (ev.getRepeatCount() == 0) // which buttons reach Orbit: adb logcat -s OrbitKey
            android.util.Log.i("OrbitKey", KeyEvent.keyCodeToString(code) + (ev.getAction() == KeyEvent.ACTION_DOWN ? " down" : " up"));
        // Let the system have Home, volume and media keys.
        if (code == KeyEvent.KEYCODE_HOME || code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE || code == KeyEvent.KEYCODE_POWER) {
            return super.dispatchKeyEvent(ev);
        }
        if (view.onKey(ev)) return true;
        if (code == KeyEvent.KEYCODE_BACK) return true; // the home screen has nowhere to go back to
        return super.dispatchKeyEvent(ev);
    }

    private void refreshGlance() {
        handler.removeCallbacks(glanceTick);
        glance.fetch(view.leagues(), (g, also) -> {
            if (!resumed) return;
            view.setGlance(g, also);
            handler.removeCallbacks(glanceTick);
            handler.postDelayed(glanceTick, view.liveNow() ? 20_000 : GLANCE_EVERY_MS); // faster while a game is on
        });
    }

    /**
     * Once: the old PlayStation / Switch tile apps (home.jw.playstation, home.jw.nswitch) become built-in
     * HDMI tiles, in the same spots on the shelf. The old apps are hidden until they're uninstalled.
     */
    private void migrateInputs() {
        android.content.SharedPreferences s = getSharedPreferences("settings", MODE_PRIVATE),
                o = getSharedPreferences("orbit", MODE_PRIVATE);
        if (s.getBoolean("inputsMigrated", false)) return;
        android.content.SharedPreferences.Editor se = s.edit().putBoolean("inputsMigrated", true);
        java.util.List<String> order = Apps.split(o.getString("order", "")), hidden = Apps.split(o.getString("hidden", "")),
                known = Apps.split(o.getString("known", ""));
        String[][] old = {{"home.jw.playstation", "3", "ps"}, {"home.jw.nswitch", "2", "switch"}};
        for (String[] m : old) {
            String pkg = m[0], key = "input_" + m[1], tile = Apps.INPUT + m[1];
            if (s.contains(key) || !installed(pkg)) continue;
            se.putString(key, m[2]);
            int at = order.indexOf(pkg);
            if (at < 0 || order.contains(tile)) continue;
            order.set(at, tile);
            known.add(tile);
            if (hidden.contains(pkg)) hidden.add(tile);
            else hidden.add(pkg);
        }
        se.apply();
        if (o.contains("order")) o.edit().putString("order", android.text.TextUtils.join(",", order))
                .putString("hidden", android.text.TextUtils.join(",", hidden)).putString("known", android.text.TextUtils.join(",", known)).apply();
    }

    private boolean installed(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Server address and home location from Settings / setup. */
    private void applyConfig() {
        android.content.SharedPreferences s = view.settings;
        glance.server = s.getString("server", "");
        try {
            Sun.LAT = Double.parseDouble(s.getString("lat", "40.7128"));
            Sun.LNG = Double.parseDouble(s.getString("lon", "-74.0060"));
        } catch (NumberFormatException ignored) {
        }
    }

    private void refreshWeather() {
        android.content.SharedPreferences s = view.settings;
        if (!s.getBoolean("weather", true) || !s.contains("lat")) {
            view.setWeather(null);
            return;
        }
        double lat, lon;
        try {
            lat = Double.parseDouble(s.getString("lat", ""));
            lon = Double.parseDouble(s.getString("lon", ""));
        } catch (NumberFormatException e) {
            return;
        }
        weather.forecast(lat, lon, !"C".equals(view.units()), w -> {
            if (w != null && resumed) view.setWeather(w); // on a failed fetch, keep showing the last one
        });
    }

    // ---- OrbitView.Host ----

    @Override
    public void configChanged() {
        applyConfig();
        apps.load(); // HDMI tiles depend on the server on Fire TV
        view.reloadApps();
        refreshGlance();
        refreshWeather();
    }

    @Override
    public void testServer(String url, Weather.Got<String> cb) {
        glance.test(url, cb);
    }

    /** A one-line text box (the TV's on-screen keyboard opens). */
    @Override
    public void askText(String title, String hint, String initial, Weather.Got<String> cb) {
        final android.widget.EditText in = new android.widget.EditText(this);
        in.setSingleLine(true);
        in.setHint(hint);
        in.setText(initial);
        in.setSelection(in.getText().length());
        in.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        box.setPadding(48, 16, 48, 0);
        box.addView(in);
        final android.app.AlertDialog d = new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title).setView(box)
                .setPositiveButton("OK", (x, y) -> cb.got(in.getText().toString().trim()))
                .setNegativeButton("Cancel", null).create();
        in.setOnEditorActionListener((v, action, ev) -> {
            d.dismiss();
            cb.got(in.getText().toString().trim());
            return true;
        });
        d.show();
        in.requestFocus();
    }

    @Override
    public void choose(String title, String[] items, Weather.Got<Integer> cb) {
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title).setItems(items, (x, which) -> cb.got(which)).setNegativeButton("Cancel", null).show();
    }

    @Override
    public void launchApp(Apps.App a) {
        if (Apps.isInput(a)) {
            switchInput(a);
            return;
        }
        Intent i = apps.launchIntent(a);
        if (i == null) {
            view.showToast("Can't open " + a.label);
            return;
        }
        try {
            startActivity(i);
        } catch (Exception e) {
            view.showToast("Can't open " + a.label);
        }
    }

    /** HDMI tile: ask the home server to switch (Fire TV), else switch directly (Android TV / Google TV). */
    private void switchInput(Apps.App a) {
        final String server = glance.server;
        final String fail = "Couldn't switch to " + a.label + " (HDMI " + a.port + ")";
        if (!server.isEmpty()) {
            view.showToast("Switching to " + a.label + "…");
            final int port = a.port;
            new Thread(() -> {
                boolean ok = false;
                try {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(server + "/hdmi/" + port).openConnection();
                    c.setRequestMethod("POST");
                    c.setConnectTimeout(3000);
                    c.setReadTimeout(20000); // the helper wakes a PlayStation first
                    ok = c.getResponseCode() == 200;
                    c.disconnect();
                } catch (Exception ignored) {
                }
                if (!ok) handler.post(() -> view.showToast(fail));
            }).start();
            return;
        }
        if (!passthrough(a.port)) view.showToast(fail);
    }

    /** Opens HDMI port n through the TV's own input service. Not on Fire TV (it refuses). */
    private boolean passthrough(int port) {
        try {
            android.media.tv.TvInputManager tim = (android.media.tv.TvInputManager) getSystemService(TV_INPUT_SERVICE);
            if (tim == null) return false;
            java.util.List<android.media.tv.TvInputInfo> hdmi = new java.util.ArrayList<>();
            for (android.media.tv.TvInputInfo i : tim.getTvInputList())
                if (i.getType() == android.media.tv.TvInputInfo.TYPE_HDMI && i.getParentId() == null) hdmi.add(i);
            hdmi.sort((x, y) -> x.getId().compareTo(y.getId()));
            android.media.tv.TvInputInfo pick = null;
            java.util.regex.Pattern num = java.util.regex.Pattern.compile("(?i)hdmi\\D{0,3}(\\d)");
            for (android.media.tv.TvInputInfo i : hdmi) { // "HDMI 3" in the label (or id) wins over list order
                java.util.regex.Matcher m = num.matcher(String.valueOf(i.loadLabel(this)));
                if (!m.find()) m = num.matcher(i.getId());
                if (m.find(0) && Integer.parseInt(m.group(1)) == port) { pick = i; break; }
            }
            if (pick == null && port <= hdmi.size()) pick = hdmi.get(port - 1);
            if (pick == null) return false;
            startActivity(new Intent(Intent.ACTION_VIEW, android.media.tv.TvContract.buildChannelUriForPassthroughInput(pick.getId()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void appInfo(Apps.App a) {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pkg))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            view.showToast("App info isn't available");
        }
    }

    @Override
    public void uninstall(Apps.App a) {
        try {
            startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            view.showToast("Can't uninstall " + a.label);
        }
    }

    @Override
    public void launchGame(Glance.Game g) {
        glance.launch(g.id, (ok, msg) -> {
            if (!ok) view.showToast("Couldn't open the game · " + msg);
        });
    }

    @Override
    public void openFLauncher() {
        try {
            startActivity(new Intent().setComponent(new ComponentName("me.efesser.flauncher", "me.efesser.flauncher.MainActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            view.showToast("FLauncher isn't installed");
        }
    }

    private boolean awake;

    /** Keeps the screen on while Orbit is showing (idle mode instead of the TV going to sleep), until "Screen sleeps after". */
    @Override
    public void keepAwake(boolean on) {
        if (on == awake) return;
        awake = on;
        if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    public void settingsChanged() {
        refreshGlance();
    }
}
