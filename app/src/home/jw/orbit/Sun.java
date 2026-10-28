package home.jw.orbit;

import java.util.Calendar;
import java.util.TimeZone;

/** Which wallpaper fits right now, from sunrise and sunset at home (NOAA sunrise equation). */
final class Sun {
    static final String[] PHASES = {"dawn", "day", "golden", "dusk", "night"};
    // Home location (Settings → Weather location; New York until set). Used for sunrise and sunset.
    static volatile double LAT = 40.7128, LNG = -74.0060;

    /** Minutes after local midnight, or NaN when the sun doesn't rise/set that day. */
    static double event(Calendar day, boolean rising) {
        int n = day.get(Calendar.DAY_OF_YEAR);
        double lngHour = LNG / 15.0;
        double t = n + ((rising ? 6 : 18) - lngHour) / 24.0;
        double m = 0.9856 * t - 3.289;
        double l = norm(m + 1.916 * sin(m) + 0.020 * sin(2 * m) + 282.634);
        double ra = norm(Math.toDegrees(Math.atan(0.91764 * tan(l))));
        ra = (ra + (Math.floor(l / 90) * 90 - Math.floor(ra / 90) * 90)) / 15.0;
        double sinDec = 0.39782 * sin(l);
        double cosDec = Math.cos(Math.asin(sinDec));
        double cosH = (cos(90.833) - sinDec * sin(LAT)) / (cosDec * cos(LAT));
        if (cosH > 1 || cosH < -1) return Double.NaN;
        double h = (rising ? 360 - Math.toDegrees(Math.acos(cosH)) : Math.toDegrees(Math.acos(cosH))) / 15.0;
        double localMean = h + ra - 0.06571 * t - 6.622;
        double ut = ((localMean - lngHour) % 24 + 24) % 24;
        TimeZone tz = day.getTimeZone();
        double offset = tz.getOffset(day.getTimeInMillis()) / 3600000.0;
        return (((ut + offset) % 24 + 24) % 24) * 60.0;
    }

    /** dawn: sunrise -50..+60 min; day; golden: sunset -90..+5; dusk: until +70; then night. */
    static String phaseNow() {
        Calendar now = Calendar.getInstance();
        double m = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        double rise = event(now, true), set = event(now, false);
        if (Double.isNaN(rise) || Double.isNaN(set)) return "night";
        if (m < rise - 50) return "night";
        if (m < rise + 60) return "dawn";
        if (m < set - 90) return "day";
        if (m < set + 5) return "golden";
        if (m < set + 70) return "dusk";
        return "night";
    }

    private static double sin(double d) { return Math.sin(Math.toRadians(d)); }
    private static double cos(double d) { return Math.cos(Math.toRadians(d)); }
    private static double tan(double d) { return Math.tan(Math.toRadians(d)); }
    private static double norm(double d) { return ((d % 360) + 360) % 360; }
}
