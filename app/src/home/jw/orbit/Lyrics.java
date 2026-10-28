package home.jw.orbit;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Time-synced song lyrics from LRCLIB (lrclib.net: free, no account). Android only tells Orbit the song's
 * title, artist, album and position, so the lyrics are looked up by those; the timing comes from the
 * music app's own position, so lines change in step with what's playing.
 */
final class Lyrics {
    interface Listener {
        void changed();
    }

    /** Line start times (ms) and text, sorted. Empty text = an instrumental gap. */
    long[] times = new long[0];
    String[] lines = new String[0];

    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private String key = "";
    private final Map<String, Object[]> cache = new LinkedHashMap<String, Object[]>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Object[]> e) {
            return size() > 30;
        }
    };

    Lyrics(Listener listener) {
        this.listener = listener;
    }

    boolean has() {
        return times.length > 0;
    }

    /** The song changed (or might have): look up its lyrics once. */
    void song(String title, String artist, String album, long durationMs) {
        String k = title + "\u0001" + artist;
        if (k.equals(key)) return;
        key = k;
        times = new long[0];
        lines = new String[0];
        listener.changed();
        if (title.isEmpty()) return;
        Object[] hit = cache.get(k);
        if (hit != null) { set(hit); return; }
        final String fk = k;
        io.execute(() -> {
            Object[] got = fetch(title, artist, album, durationMs);
            main.post(() -> {
                if (got != null) cache.put(fk, got);
                if (fk.equals(key) && got != null) set(got);
            });
        });
    }

    private void set(Object[] got) {
        times = (long[]) got[0];
        lines = (String[]) got[1];
        listener.changed();
    }

    /** Index of the line being sung at this position (-1 before the first line). */
    int index(long posMs) {
        int i = Arrays.binarySearch(times, posMs + 150); // a hair early reads better than late
        return i >= 0 ? i : -i - 2;
    }

    /** ms until the next line starts (for scheduling a redraw), or -1. */
    long untilNext(long posMs) {
        int i = index(posMs) + 1;
        return i < times.length ? Math.max(20, times[i] - (posMs + 150)) : -1;
    }

    // ---- lookup ----

    private static Object[] fetch(String title, String artist, String album, long durationMs) {
        // Amazon Music (and sometimes Spotify) add "[Explicit]" / "(Clean)" to titles and albums; LRCLIB doesn't.
        title = title.replaceAll("(?i)\\s*[\\(\\[](explicit|clean)[\\)\\]]", "").trim();
        album = album.replaceAll("(?i)\\s*[\\(\\[](explicit|clean)[\\)\\]]", "").trim();
        String first = artist.split(",| & | feat\\. | x ")[0].trim();
        String clean = title.replaceAll("(?i)\\s*[\\(\\[](feat\\.?|with|ft\\.?)[^\\)\\]]*[\\)\\]]", "")
                .replaceAll("(?i)\\s+-\\s+.*(remaster|version|edit|live|mono|stereo|mix).*$", "").trim();
        int secs = (int) Math.round(durationMs / 1000.0);
        try { // 1) exact match on title, artist, album and length
            StringBuilder u = new StringBuilder("https://lrclib.net/api/get?track_name=").append(enc(title))
                    .append("&artist_name=").append(enc(artist));
            if (!album.isEmpty()) u.append("&album_name=").append(enc(album));
            if (secs > 0) u.append("&duration=").append(secs);
            Object[] r = parse(new JSONObject(Weather.get(u.toString())));
            if (r != null) return r;
        } catch (Exception ignored) {
        }
        try { // 2) search with a cleaned-up title and the main artist; pick the closest length
            JSONArray rs = new JSONArray(Weather.get("https://lrclib.net/api/search?track_name=" + enc(clean)
                    + "&artist_name=" + enc(first)));
            JSONObject best = null;
            double bestOff = 1e9;
            for (int i = 0; i < rs.length(); i++) {
                JSONObject o = rs.getJSONObject(i);
                if (o.optString("syncedLyrics", "").isEmpty()) continue;
                double off = secs > 0 ? Math.abs(o.optDouble("duration", 0) - secs) : 0;
                if (off < bestOff) { bestOff = off; best = o; }
            }
            if (best != null && bestOff <= 4) return parse(best);
        } catch (Exception ignored) {
        }
        return null;
    }

    private static final Pattern STAMP = Pattern.compile("\\[(\\d+):(\\d+(?:\\.\\d+)?)\\]");

    private static Object[] parse(JSONObject o) {
        String lrc = o.optString("syncedLyrics", "");
        if (o.optBoolean("instrumental") || lrc.isEmpty() || "null".equals(lrc)) return null;
        List<long[]> at = new ArrayList<>();
        List<String> text = new ArrayList<>();
        for (String raw : lrc.split("\n")) {
            Matcher m = STAMP.matcher(raw);
            List<Long> ts = new ArrayList<>();
            int end = 0;
            while (m.find() && m.start() == end) {
                ts.add((long) (Integer.parseInt(m.group(1)) * 60_000L + Double.parseDouble(m.group(2)) * 1000));
                end = m.end();
            }
            String t = raw.substring(end).trim();
            for (long ms : ts) { at.add(new long[]{ms, text.size()}); text.add(t); }
        }
        if (at.isEmpty()) return null;
        at.sort((a, b) -> Long.compare(a[0], b[0]));
        long[] times = new long[at.size()];
        String[] lines = new String[at.size()];
        for (int i = 0; i < at.size(); i++) { times[i] = at.get(i)[0]; lines[i] = text.get((int) at.get(i)[1]); }
        return new Object[]{times, lines};
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s.trim(), "UTF-8");
    }
}
