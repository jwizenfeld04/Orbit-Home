package home.jw.orbit;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The game line (optional). A home server running the Orbit helper (tv-input-helper) picks the game via
 * Sports Remote and launches it. With no server set ("Home server" in Settings), there's no game line.
 */
final class Glance {
    /** e.g. "http://<server-ip>:8788"; empty = standalone, no game line. */
    volatile String server = "";

    static final class Game {
        String id, tag, title, detail, net;
        boolean live, fav;
        Team away, home;  // with scores while live
    }

    static final class Team {
        String name, abbr;
        int score = -1;   // -1: no score yet
    }

    /** Filled by Weather.forecast (kept here so the view has one model package). */
    static final class Weather {
        int temp, code, hi, lo;
        boolean day;
        String text;
    }

    interface Listener {
        void onGlance(Game game, Game alsoGame);
    }

    interface Done {
        void onDone(boolean ok, String message);
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    void fetch(String leagues, Listener l) {
        io.execute(() -> {
            Game g = null, also = null;
            String base = server;
            if (!base.isEmpty()) {
                try {
                    JSONObject o = new JSONObject(http("GET", base + "/glance?leagues=" + leagues, null));
                    g = parse(o.optJSONObject("game"));
                    also = parse(o.optJSONObject("alsoGame"));
                } catch (Exception ignored) {
                    // server unreachable: no game line
                }
            }
            final Game fg = g, fa = also;
            main.post(() -> l.onGlance(fg, fa));
        });
    }

    private static Game parse(JSONObject j) {
        if (j == null) return null;
        Game g = new Game();
        g.id = j.optString("id");
        g.tag = j.optString("tag");
        g.title = j.optString("title");
        g.detail = j.optString("detail");
        g.net = j.optString("net");
        g.live = j.optBoolean("live");
        g.fav = j.optBoolean("fav");
        g.away = team(j.optJSONObject("away"));
        g.home = team(j.optJSONObject("home"));
        return g;
    }

    private static Team team(JSONObject j) {
        if (j == null) return null;
        Team t = new Team();
        t.name = j.optString("name");
        t.abbr = j.optString("abbr");
        t.score = j.isNull("score") ? -1 : j.optInt("score", -1);
        return t;
    }

    void launch(String gameId, Done d) {
        io.execute(() -> {
            boolean ok = false;
            String msg = "Couldn't reach the home server";
            try {
                JSONObject body = new JSONObject().put("gameId", gameId);
                if (server.isEmpty()) throw new IllegalStateException("no server");
                JSONObject r = new JSONObject(http("POST", server + "/glance/launch", body.toString()));
                ok = r.optBoolean("ok");
                msg = r.optString("message", ok ? "" : "Couldn't open the game");
            } catch (Exception ignored) {
            }
            final boolean fok = ok;
            final String fm = msg;
            main.post(() -> d.onDone(fok, fm));
        });
    }

    /** Setup's "test the server": does it answer like the Orbit helper? */
    void test(String base, home.jw.orbit.Weather.Got<String> cb) {
        io.execute(() -> {
            String result;
            try {
                JSONObject o = new JSONObject(http("GET", base + "/glance?leagues=nfl,mlb,nhl,nba", null));
                result = o.has("error") && "no_pin".equals(o.optString("error")) ? "Connected · Sports Remote PIN missing on the server"
                        : o.has("game") ? "Connected ✓" : "Answered, but not like the Orbit helper";
            } catch (Exception e) {
                result = "Can't reach it";
            }
            final String r = result;
            main.post(() -> cb.got(r));
        });
    }

    private static String http(String method, String url, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(3000);
        c.setReadTimeout(method.equals("POST") ? 70000 : 10000);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = c.getResponseCode();
        try (InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while (is != null && (n = is.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
