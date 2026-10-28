package home.jw.orbit;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What's playing on the TV (Spotify via Spotify Connect, or any music app), from Android's media sessions:
 * song, artist, album art, position, and play/pause/skip. Needs MediaListener's permission; without it
 * there's simply no now-playing card.
 */
final class NowPlaying {
    interface Listener {
        void changed();
    }

    String title = "", artist = "", album = "";
    /** Synced lyrics for the current song (looked up when the song changes). */
    final Lyrics lyrics;
    Bitmap art;
    long duration;
    boolean playing;
    boolean allowed = true;

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private MediaSessionManager msm;
    private MediaController ctl;
    private PlaybackState state;
    private long pausedAt;
    private String artKey = "";

    private final MediaSessionManager.OnActiveSessionsChangedListener sessions = this::pick;

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState s) {
            read();
        }

        @Override
        public void onMetadataChanged(MediaMetadata m) {
            read();
        }

        @Override
        public void onSessionDestroyed() {
            pick(null);
        }
    };

    NowPlaying(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
        this.lyrics = new Lyrics(listener::changed);
    }

    void start() {
        try {
            msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            ComponentName cn = new ComponentName(ctx, MediaListener.class);
            msm.addOnActiveSessionsChangedListener(sessions, cn, main);
            pick(msm.getActiveSessions(cn));
            allowed = true;
        } catch (SecurityException e) {
            allowed = false; // permission not granted yet (see MediaListener)
        }
    }

    void stop() {
        try {
            if (msm != null) msm.removeOnActiveSessionsChangedListener(sessions);
        } catch (Exception ignored) {
        }
        if (ctl != null) ctl.unregisterCallback(callback);
        ctl = null; // re-pick (and re-listen) on the next start, or song changes stop arriving
    }

    /** Safety net: re-read the session now and then in case a change notification was missed. */
    void refresh() {
        if (ctl != null) read();
    }

    /** Something to show: a song that's playing, or paused (the card stays, bars stop). */
    boolean showing() {
        return controllable();
    }

    /** Something the remote can control: playing, or paused in the last 30 minutes. */
    boolean controllable() {
        return ctl != null && !title.isEmpty() && (playing || SystemClock.elapsedRealtime() - pausedAt < 30 * 60_000L);
    }

    void toggle() {
        if (ctl == null) return;
        if (playing) ctl.getTransportControls().pause();
        else ctl.getTransportControls().play();
    }

    /** Set when a skip is sent; the card dims until the new song arrives (or 3 s pass). */
    long skippedAt;

    void next() {
        if (ctl == null) return;
        skippedAt = SystemClock.uptimeMillis();
        ctl.getTransportControls().skipToNext();
    }

    /**
     * Previous song (apps restart the current one if it's past the first few seconds). Apps that can't skip
     * back (Alexa's player) restart the song instead: seek to 0:00 if they allow it, else send them the
     * remote's Previous button and let them decide.
     */
    void previous() {
        if (ctl == null) return;
        long actions = state != null ? state.getActions() : 0;
        if ((actions & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0) {
            skippedAt = SystemClock.uptimeMillis();
            ctl.getTransportControls().skipToPrevious();
        } else if ((actions & PlaybackState.ACTION_SEEK_TO) != 0) {
            ctl.getTransportControls().seekTo(0);
        } else {
            for (int a : new int[]{android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP})
                ctl.dispatchMediaButtonEvent(new android.view.KeyEvent(a, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS));
        }
    }

    boolean skipping() {
        return skippedAt > 0 && SystemClock.uptimeMillis() - skippedAt < 3000;
    }

    /** Current position in ms, moving along between updates. */
    long position() {
        if (state == null) return 0;
        long p = state.getPosition();
        if (playing && state.getLastPositionUpdateTime() > 0)
            p += (long) ((SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime()) * state.getPlaybackSpeed());
        return duration > 0 ? Math.max(0, Math.min(duration, p)) : Math.max(0, p);
    }

    /** Prefer a session that's playing music; else one that's paused. */
    private void pick(List<MediaController> list) {
        MediaController best = null;
        if (list != null) {
            for (MediaController c : list) {
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING && c.getMetadata() != null) { best = c; break; }
            }
            if (best == null) for (MediaController c : list) if (c.getMetadata() != null) { best = c; break; }
        }
        if (ctl != null && (best == null || !best.getSessionToken().equals(ctl.getSessionToken()))) ctl.unregisterCallback(callback);
        if (best != null && (ctl == null || !best.getSessionToken().equals(ctl.getSessionToken()))) best.registerCallback(callback, main);
        ctl = best;
        read();
    }

    private void read() {
        if (ctl == null) {
            title = artist = album = "";
            lyrics.song("", "", "", 0);
            art = null;
            artKey = "";
            playing = false;
            listener.changed();
            return;
        }
        state = ctl.getPlaybackState();
        boolean was = playing;
        playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;
        if (was && !playing) pausedAt = SystemClock.elapsedRealtime();
        MediaMetadata m = ctl.getMetadata();
        if (m != null) {
            String t = noTag(str(m, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE));
            if (!t.equals(title)) skippedAt = 0; // the new song is here
            title = t;
            artist = str(m, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            duration = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
            album = noTag(str(m, MediaMetadata.METADATA_KEY_ALBUM, MediaMetadata.METADATA_KEY_ALBUM));
            lyrics.song(title, artist, album, duration);
            loadArt(m);
        }
        listener.changed();
    }

    /**
     * Spotify sends its logo as a stand-in cover while it switches songs or pauses. Album covers have lots
     * of colors; the logo has a handful (flat green/black/white). Sample a grid and count distinct colors.
     */
    private static boolean placeholder(Bitmap b) {
        java.util.HashSet<Integer> colors = new java.util.HashSet<>();
        int w = b.getWidth(), h = b.getHeight();
        for (int y = 0; y < 16; y++)
            for (int x = 0; x < 16; x++) {
                int c = b.getPixel(x * (w - 1) / 15, y * (h - 1) / 15);
                colors.add(c & 0xF0F0F0); // ignore tiny differences
            }
        return colors.size() <= 6;
    }

    /** "Bodak Yellow [Explicit]" → "Bodak Yellow" (Amazon Music and Spotify tag titles and albums). */
    private static String noTag(String s) {
        return s.replaceAll("(?i)\\s*[\\(\\[](explicit|clean)[\\)\\]]", "").trim();
    }

    private static String str(MediaMetadata m, String a, String b) {
        CharSequence s = m.getText(a);
        if (s == null || s.length() == 0) s = m.getText(b);
        return s == null ? "" : s.toString();
    }

    /**
     * Apps often send a new song's title first and its album art a moment later (Spotify does), so the
     * art is keyed on the picture itself, not the song: every new picture or URL replaces the old one.
     */
    private void loadArt(MediaMetadata m) {
        Bitmap b = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        String uri = null;
        for (String k : new String[]{MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI}) {
            String u = m.getString(k);
            if (u != null && u.startsWith("http")) { uri = u; break; }
        }
        if (b != null && placeholder(b)) {
            if (art != null || uri == null) return; // keep the cover we have; the real one follows in ~0.1 s
            b = null;                                 // nothing yet: fetch it from the web address instead
        }
        String key = b != null ? "bmp:" + b.getGenerationId() + "@" + System.identityHashCode(b) + "/" + title
                : uri != null ? "uri:" + uri : "none:" + title + "|" + artist;
        if (key.equals(artKey)) return;
        artKey = key;
        if (b != null) {
            Bitmap scaled = Bitmap.createScaledBitmap(b, 256, 256 * b.getHeight() / Math.max(1, b.getWidth()), true);
            if (art == null || !art.sameAs(scaled)) art = scaled; // the same cover sent again: keep it (no flicker)
            return;
        }
        // Keep showing the old cover until the new one has loaded; the view crossfades to it.
        if (uri == null) { art = null; return; }
        final String fu = uri, fk = key;
        io.execute(() -> { // album art from the web (Spotify gives a URL)
            Bitmap got = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(fu).openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(10000);
                try (InputStream is = c.getInputStream()) {
                    Bitmap full = BitmapFactory.decodeStream(is);
                    if (full != null) got = Bitmap.createScaledBitmap(full, 256, 256 * full.getHeight() / Math.max(1, full.getWidth()), true);
                } finally {
                    c.disconnect();
                }
            } catch (Exception ignored) {
            }
            final Bitmap fb = got;
            main.post(() -> {
                if (fk.equals(artKey) && fb != null && (art == null || !art.sameAs(fb))) {
                    art = fb;
                    listener.changed();
                }
            });
        });
    }
}
