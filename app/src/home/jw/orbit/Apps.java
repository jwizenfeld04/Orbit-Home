package home.jw.orbit;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The TV's launchable apps, in the household's order, with their banners. Order and hidden set persist. */
final class Apps {
    static final class App {
        final String pkg;
        final String label;
        final Bitmap banner;
        final boolean system;
        boolean tvApp = true; // false: a phone-style app with no TV banner
        boolean hidden;
        boolean off;          // an HDMI tile that's switched off or can't work here: never shown, keeps its place
        int port;             // HDMI tiles: 1-4, else 0

        App(String pkg, String label, Bitmap banner, boolean system) {
            this.pkg = pkg;
            this.label = label;
            this.banner = banner;
            this.system = system;
        }
    }

    /** First-run order for well-known apps; any other TV app follows in name order. */
    private static final List<String> DEFAULT_ORDER = Arrays.asList(
            "com.amazon.firetv.youtube.tv", "com.plexapp.android", "com.netflix.ninja", "com.espn.gtv",
            "com.amazon.firebat", "com.hulu.plus", "com.hbo.hbonow",
            "com.disney.disneyplus", "com.peacock.peacockfiretv", "com.cbs.ott", "com.apple.atve.amazon.appletv",
            "com.bamnetworks.mobile.android.gameday.atbat", "com.fox.foxone");
    /** Hidden on first run (FLauncher hid the same). */
    private static final List<String> DEFAULT_HIDDEN = Arrays.asList(
            "home.jw.orbit", "me.efesser.flauncher", "io.github.toolicious.homeonfire",
            "com.amazon.spiderpork", "com.apple.airplay.amazon.airplay");

    /** HDMI tiles: what's plugged into each port (settings "input_1".."input_4"). */
    static final String[] DEVICE_IDS = {"off", "ps", "xbox", "switch", "appletv", "cable", "bluray", "pc", "other"};
    static final String[] DEVICE_NAMES = {"Off", "PlayStation", "Xbox", "Nintendo Switch", "Apple TV", "Cable box", "Blu-ray", "PC", "Other"};
    static final String INPUT = "input:";

    /** Phone-style launcher entries that are system plumbing, not something to open from a home screen. */
    private static final Set<String> PLUMBING = new HashSet<>(Arrays.asList(
            "com.amazon.tv.launcher", "com.amazon.ftv.profilepicker", "com.amazon.ftv.screensaver", "com.amazon.hedwig",
            "com.amazon.vizzini.ftvcds", "com.amazon.ssm", "com.android.documentsui", "com.android.providers.downloads.ui",
            "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx", "com.android.tv.settings"));

    private final Context ctx;
    private final SharedPreferences prefs, settings;
    private final Map<String, Bitmap> bannerCache = new HashMap<>();
    final List<App> all = new ArrayList<>(); // in order, hidden included

    Apps(Context ctx) {
        this.ctx = ctx;
        this.prefs = ctx.getSharedPreferences("orbit", Context.MODE_PRIVATE);
        this.settings = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    static boolean amazon() {
        return "Amazon".equalsIgnoreCase(android.os.Build.MANUFACTURER);
    }

    /** Fire OS won't let apps switch inputs, so there the tiles need the home server. */
    boolean inputsWork() {
        return !amazon() || !settings.getString("server", "").isEmpty();
    }

    static boolean isInput(App a) {
        return a.pkg.startsWith(INPUT);
    }

    static String deviceName(String id, int port) {
        if ("other".equals(id)) return "HDMI " + port;
        int i = Arrays.asList(DEVICE_IDS).indexOf(id);
        return i > 0 ? DEVICE_NAMES[i] : "HDMI " + port;
    }

    /** One tile per HDMI port that has a device; a port that's off (or can't work) keeps its slot if it had one. */
    private void addInputs(Map<String, App> found, List<String> savedOrder) {
        boolean works = inputsWork();
        for (int n = 1; n <= 4; n++) {
            String pkg = INPUT + n, dev = settings.getString("input_" + n, "off");
            boolean on = !dev.equals("off") && works;
            if (!on && !savedOrder.contains(pkg)) continue;
            App a = new App(pkg, deviceName(dev, n), dev.equals("off") ? null : inputBanner(dev, n), true);
            a.port = n;
            a.off = !on;
            found.put(pkg, a);
        }
    }
    void load() {
        PackageManager pm = ctx.getPackageManager();
        Map<String, App> found = new HashMap<>();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        // TV apps first (they have proper banners), then phone-style apps the TV can still open.
        List<ResolveInfo> entries = new ArrayList<>(pm.queryIntentActivities(q, 0));
        int tvCount = entries.size();
        for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0))
            if (!PLUMBING.contains(ri.activityInfo.packageName)) entries.add(ri);
        for (int i = 0; i < entries.size(); i++) {
            ResolveInfo ri = entries.get(i);
            String pkg = ri.activityInfo.packageName;
            if (found.containsKey(pkg) || pkg.equals(ctx.getPackageName())) continue;
            String label = String.valueOf(ri.loadLabel(pm));
            boolean system = (ri.activityInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            App a = new App(pkg, label, banner(pm, ri, pkg, label), system);
            a.tvApp = i < tvCount;
            found.put(pkg, a);
        }

        boolean firstRun = !prefs.contains("order");
        List<String> order = new ArrayList<>(firstRun ? DEFAULT_ORDER : split(prefs.getString("order", "")));
        addInputs(found, order);
        Set<String> hidden = firstRun ? new HashSet<>(DEFAULT_HIDDEN) : new HashSet<>(split(prefs.getString("hidden", "")));
        Set<String> known = new HashSet<>(split(prefs.getString("known", "")));

        List<String> extras = new ArrayList<>();
        for (String pkg : found.keySet()) if (!order.contains(pkg)) extras.add(pkg);
        extras.sort((a, b) -> found.get(a).label.compareToIgnoreCase(found.get(b).label));
        // Phone-style apps (no TV version) only show when picked. Once: hide any that got shown automatically.
        boolean fixPhoneApps = !prefs.getBoolean("phoneAppsHidden", false);
        if (fixPhoneApps) for (App a : found.values()) if (!a.tvApp && !DEFAULT_ORDER.contains(a.pkg)) hidden.add(a.pkg);
        for (String pkg : extras) {
            order.add(pkg);
            // First run: every TV app shows (well-known ones first, then A to Z); setup narrows it down.
            if (!firstRun && !known.contains(pkg) && found.get(pkg).tvApp) hidden.remove(pkg); // newly installed TV app: show it
            if (!found.get(pkg).tvApp && !known.contains(pkg)) hidden.add(pkg);
        }
        if (fixPhoneApps) prefs.edit().putBoolean("phoneAppsHidden", true).apply();

        all.clear();
        for (String pkg : order) {
            App a = found.get(pkg);
            if (a == null) continue;
            a.hidden = hidden.contains(pkg);
            all.add(a);
        }
        save();
    }

    List<App> visible(boolean includeHidden) {
        List<App> out = new ArrayList<>();
        for (App a : all) if (!a.off && (includeHidden || !a.hidden)) out.add(a);
        return out;
    }

    void move(App a, App before) {
        all.remove(a);
        int i = before == null ? all.size() : all.indexOf(before);
        all.add(Math.max(0, i), a);
        save();
    }

    void swap(App a, App b) {
        int i = all.indexOf(a), j = all.indexOf(b);
        if (i < 0 || j < 0) return;
        all.set(i, b);
        all.set(j, a);
    }

    void setOrder(List<App> order) {
        all.clear();
        all.addAll(order);
        save();
    }

    void setHidden(App a, boolean hidden) {
        a.hidden = hidden;
        save();
    }

    /** Setup's app picker: these apps, in this order, shown; everything else hidden (still in the list). */
    void applyPicks(List<String> picked) {
        List<App> order = new ArrayList<>();
        for (String pkg : picked) for (App a : all) if (a.pkg.equals(pkg)) { a.hidden = false; order.add(a); }
        for (App a : all) if (!order.contains(a)) { if (!a.off) a.hidden = true; order.add(a); }
        setOrder(order);
    }

    void resetOrder() {
        prefs.edit().remove("order").remove("hidden").remove("known").apply();
        load();
    }

    void save() {
        List<String> order = new ArrayList<>(), hidden = new ArrayList<>(), known = new ArrayList<>();
        for (App a : all) {
            order.add(a.pkg);
            known.add(a.pkg);
            if (a.hidden) hidden.add(a.pkg);
        }
        prefs.edit().putString("order", TextUtils.join(",", order)).putString("hidden", TextUtils.join(",", hidden))
                .putString("known", TextUtils.join(",", known)).apply();
    }

    /** Banners for HDMI tiles: the real logos for PlayStation and Switch, else a colour card with the name. */
    private Bitmap inputBanner(String dev, int port) {
        String key = INPUT + dev + port;
        Bitmap cached = bannerCache.get(key);
        if (cached != null) return cached;
        Bitmap b = null;
        if (dev.equals("ps") || dev.equals("switch")) {
            try (java.io.InputStream is = ctx.getAssets().open("banners/input_" + dev + ".png")) {
                b = android.graphics.BitmapFactory.decodeStream(is);
            } catch (java.io.IOException ignored) {
            }
        }
        if (b == null) b = drawInputBanner(dev, port);
        bannerCache.put(key, b);
        return b;
    }

    private Typeface face(int wght) {
        try {
            return new Typeface.Builder(ctx.getAssets(), "fonts/InterTight-VF.ttf").setFontVariationSettings("'wght' " + wght).build();
        } catch (Exception e) {
            return Typeface.DEFAULT_BOLD;
        }
    }

    private Bitmap drawInputBanner(String dev, int port) {
        int top, bottom, wght = 700;
        String word;
        float size = 54, spacing = 0;
        switch (dev) {
            case "xbox": top = 0xff1f9a1f; bottom = 0xff0b4f0b; word = "XBOX"; spacing = 0.18f; wght = 800; break;
            case "appletv": top = 0xff3a3a3f; bottom = 0xff0c0c0e; word = "Apple TV"; wght = 600; break;
            case "cable": top = 0xff3f5a78; bottom = 0xff17222f; word = "Cable box"; wght = 600; break;
            case "bluray": top = 0xff1d6ff2; bottom = 0xff07296b; word = "Blu-ray"; break;
            case "pc": top = 0xff6a4fd8; bottom = 0xff26186a; word = "PC"; size = 72; wght = 800; break;
            default: top = 0xff3a3f4c; bottom = 0xff161920; word = "HDMI " + port; wght = 600; break; // "Other"
        }
        Bitmap b = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 320, 180, top, bottom, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 320, 180, p);
        p.setShader(new android.graphics.RadialGradient(90, 20, 260, 0x30ffffff, 0x00ffffff, Shader.TileMode.CLAMP)); // soft light, top left
        c.drawRect(0, 0, 320, 180, p);
        p.setShader(null);
        p.setColor(0xffffffff);
        p.setTypeface(face(wght));
        p.setLetterSpacing(spacing);
        p.setTextSize(size);
        p.setTextAlign(Paint.Align.CENTER);
        while (p.measureText(word) > 270) p.setTextSize(p.getTextSize() - 2);
        boolean sub = !dev.equals("other");
        float y = 90 - (p.descent() + p.ascent()) / 2 - (sub ? 10 : 0);
        c.drawText(word, 160, y, p);
        if (sub) { // which port, small and quiet
            p.setTypeface(face(500));
            p.setLetterSpacing(0.12f);
            p.setTextSize(19);
            p.setColor(0x99ffffff);
            c.drawText("HDMI " + port, 160, 152, p);
        }
        return b;
    }

    Intent launchIntent(App a) {
        PackageManager pm = ctx.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(a.pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(a.pkg);
        if (i != null) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return i;
    }

    private Bitmap banner(PackageManager pm, ResolveInfo ri, String pkg, String label) {
        Bitmap cached = bannerCache.get(pkg);
        if (cached != null) return cached;
        Drawable d = ri.activityInfo.loadBanner(pm);
        if (d == null) d = ri.activityInfo.applicationInfo.loadBanner(pm);
        Bitmap b = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        if (d != null) {
            d.setBounds(0, 0, 320, 180);
            d.draw(c);
        } else { // no TV banner: the icon and name on a dark card
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(new LinearGradient(0, 0, 0, 180, 0xff2a2f3a, 0xff12151c, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, 320, 180, p);
            Drawable icon = ri.loadIcon(pm);
            icon.setBounds(124, 26, 196, 98);
            icon.draw(c);
            p.setShader(null);
            p.setColor(0xffffffff);
            p.setTextSize(26);
            p.setTextAlign(Paint.Align.CENTER);
            String name = label.length() > 22 ? label.substring(0, 21) + "…" : label;
            c.drawText(name, 160, 142, p);
        }
        bannerCache.put(pkg, b);
        return b;
    }

    static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        out.addAll(new LinkedHashSet<>(Arrays.asList(s.split(","))));
        return out;
    }
}
