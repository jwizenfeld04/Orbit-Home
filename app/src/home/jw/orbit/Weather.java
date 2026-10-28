package home.jw.orbit;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Weather and location, straight from free services that need no account or key:
 * Open-Meteo (forecast + city search) and ip-api.com / ipapi.co (rough location from the internet
 * connection, only used to suggest a city during setup). Works without any home server.
 */
final class Weather {
    static final class Place {
        String name, region;
        double lat, lon;

        String label() {
            return region == null || region.isEmpty() ? name : name + ", " + region;
        }
    }

    interface Got<T> {
        void got(T value); // null when it failed
    }

    private static final String[] TEXT = new String[100];

    static {
        String[][] t = {{"0", "Clear"}, {"1", "Mostly clear"}, {"2", "Partly cloudy"}, {"3", "Cloudy"}, {"45", "Fog"},
                {"48", "Fog"}, {"51", "Drizzle"}, {"53", "Drizzle"}, {"55", "Drizzle"}, {"56", "Freezing drizzle"},
                {"57", "Freezing drizzle"}, {"61", "Light rain"}, {"63", "Rain"}, {"65", "Heavy rain"},
                {"66", "Freezing rain"}, {"67", "Freezing rain"}, {"71", "Light snow"}, {"73", "Snow"},
                {"75", "Heavy snow"}, {"77", "Snow"}, {"80", "Showers"}, {"81", "Showers"}, {"82", "Heavy showers"},
                {"85", "Snow showers"}, {"86", "Snow showers"}, {"95", "Thunderstorms"}, {"96", "Thunderstorms"},
                {"99", "Thunderstorms"}};
        for (String[] e : t) TEXT[Integer.parseInt(e[0])] = e[1];
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    /** Current conditions and today's high/low. */
    void forecast(double lat, double lon, boolean fahrenheit, Got<Glance.Weather> cb) {
        io.execute(() -> {
            Glance.Weather w = null;
            try {
                String url = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                        + "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min"
                        + "&temperature_unit=%s&timezone=auto&forecast_days=1", lat, lon, fahrenheit ? "fahrenheit" : "celsius");
                JSONObject o = new JSONObject(get(url));
                JSONObject cur = o.getJSONObject("current"), day = o.getJSONObject("daily");
                w = new Glance.Weather();
                w.temp = (int) Math.round(cur.getDouble("temperature_2m"));
                w.code = cur.getInt("weather_code");
                w.day = cur.optInt("is_day", 1) == 1;
                w.text = w.code >= 0 && w.code < TEXT.length && TEXT[w.code] != null ? TEXT[w.code] : "";
                w.hi = (int) Math.round(day.getJSONArray("temperature_2m_max").getDouble(0));
                w.lo = (int) Math.round(day.getJSONArray("temperature_2m_min").getDouble(0));
            } catch (Exception e) {
                w = null;
            }
            final Glance.Weather fw = w;
            main.post(() -> cb.got(fw));
        });
    }

    /** Cities matching what was typed, best first (up to 8). */
    void search(String query, Got<List<Place>> cb) {
        io.execute(() -> {
            List<Place> out = new ArrayList<>();
            try {
                JSONObject o = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=8&language=en&name="
                        + URLEncoder.encode(query.trim(), "UTF-8")));
                JSONArray rs = o.optJSONArray("results");
                for (int i = 0; rs != null && i < rs.length(); i++) {
                    JSONObject r = rs.getJSONObject(i);
                    Place p = new Place();
                    p.name = r.getString("name");
                    String admin = r.optString("admin1"), country = r.optString("country_code");
                    p.region = admin.isEmpty() ? country : admin + ("US".equals(country) || country.isEmpty() ? "" : ", " + country);
                    p.lat = r.getDouble("latitude");
                    p.lon = r.getDouble("longitude");
                    out.add(p);
                }
            } catch (Exception ignored) {
            }
            main.post(() -> cb.got(out));
        });
    }

    /** A rough guess of where the TV is, from its internet connection. */
    void guess(Got<Place> cb) {
        io.execute(() -> {
            Place p = null;
            try { // ip-api.com first (ipapi.co often answers "RateLimited" on shared connections)
                JSONObject o = new JSONObject(get("http://ip-api.com/json/?fields=status,city,regionName,lat,lon"));
                if ("success".equals(o.optString("status"))) {
                    p = new Place();
                    p.name = o.getString("city");
                    p.region = o.optString("regionName");
                    p.lat = o.getDouble("lat");
                    p.lon = o.getDouble("lon");
                }
            } catch (Exception ignored) {
                p = null;
            }
            if (p == null) {
                try {
                    JSONObject o = new JSONObject(get("https://ipapi.co/json/"));
                    p = new Place();
                    p.name = o.getString("city");
                    p.region = o.optString("region");
                    p.lat = o.getDouble("latitude");
                    p.lon = o.getDouble("longitude");
                } catch (Exception ignored) {
                    p = null;
                }
            }
            final Place fp = p;
            main.post(() -> cb.got(fp));
        });
    }

    static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "Orbit-TV-launcher");
        try (InputStream is = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while (is != null && (n = is.read(buf)) > 0) out.write(buf, 0, n);
            if (c.getResponseCode() >= 400) throw new Exception("HTTP " + c.getResponseCode());
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
