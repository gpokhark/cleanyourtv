package com.gathin.tvhome;

import android.content.ComponentName;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps the Shows screen's place in a show up to date while the viewer watches it in another app.
 *
 * An app may only read other apps' playback sessions through a notification listener, so this is one; it does
 * nothing until the viewer enables it (cmd notification allow_listener, see docs/SHOWS.md) and, even then, acts
 * only while an episode opened from the Shows screen is marked as being watched. It saves how far into the episode
 * the viewer is, and notices when the app moves on to the next episode by itself (autoplay) so the place moves on
 * too. It does not try to seek: Pluto TV ignores seek requests from other apps, and resumes by itself on reopening. The episode itself is never read: apps such as
 * Pluto TV publish no title, so the place is what this launcher opened, plus the autoplay rule.
 */
public class MediaWatcher extends NotificationListenerService {
    private static final String TAG = "TvHome.Watcher";
    private static final long TICK_MS = 5_000;
    private static final long AUTOPLAY_MIN_WATCH_MS = 8 * 60 * 1000;    // an episode has to have run this long
    private static final long AUTOPLAY_RESET_MS = 120 * 1000;           // back to the start by more than this
    private static final long GONE_MS = 3 * 60 * 1000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<MediaSession.Token, MediaController> watched = new HashMap<>();
    private final Map<MediaSession.Token, MediaController.Callback> callbacks = new HashMap<>();
    private ShowStore store;
    private ShowStore.Show show;       // the catalogue of the show being watched, loaded when watching starts
    private long showLoadedFor;        // watchingSince the catalogue was loaded for
    private long lastSeen;             // wall-clock time a session of the watched app last existed

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            checkAll();
            handler.postDelayed(this, TICK_MS);
        }
    };

    @Override
    public void onListenerConnected() {
        store = new ShowStore(this);
        try {
            MediaSessionManager msm = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            ComponentName me = new ComponentName(this, MediaWatcher.class);
            msm.addOnActiveSessionsChangedListener(this::sync, me, handler);
            sync(msm.getActiveSessions(me));
        } catch (SecurityException e) {
            Log.w(TAG, "not allowed to read media sessions: " + e);
            return;
        }
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, TICK_MS);
    }

    @Override
    public void onListenerDisconnected() {
        handler.removeCallbacks(tick);
        for (Map.Entry<MediaSession.Token, MediaController> e : watched.entrySet()) {
            MediaController.Callback cb = callbacks.get(e.getKey());
            if (cb != null) e.getValue().unregisterCallback(cb);
        }
        watched.clear();
        callbacks.clear();
    }

    private void sync(List<MediaController> controllers) {
        if (controllers == null) return;
        for (MediaController c : controllers) {
            MediaSession.Token token = c.getSessionToken();
            if (watched.containsKey(token)) continue;
            MediaController.Callback cb = new MediaController.Callback() {
                @Override
                public void onPlaybackStateChanged(PlaybackState state) {
                    handle(c);
                }
            };
            c.registerCallback(cb, handler);
            watched.put(token, c);
            callbacks.put(token, cb);
        }
        checkAll();
    }

    private void checkAll() {
        if (store == null) return;
        String showId = store.watchingShow();
        if (showId == null) return;
        loadShow(showId);
        if (show == null) return;
        boolean present = false;
        for (MediaController c : watched.values()) {
            if (c.getPackageName().equals(show.pkg)) {
                present = true;
                handle(c);
            }
        }
        long now = System.currentTimeMillis();
        if (present) {
            lastSeen = now;
        } else if (now - Math.max(lastSeen, store.watchingSince()) > GONE_MS) {
            // The app has been silent for a while, counted from the later of its last session and the moment the
            // episode was opened (it can take a minute to start): what plays next is not this show.
            store.stopWatching();
        }
    }

    private void loadShow(String showId) {
        long since = store.watchingSince();
        if (show != null && show.id.equals(showId) && showLoadedFor == since) return;
        show = null;
        for (ShowStore.Show s : ShowStore.load(this)) {
            if (s.id.equals(showId)) show = s;
        }
        showLoadedFor = since;
    }

    private void handle(MediaController c) {
        if (store == null || show == null || !c.getPackageName().equals(show.pkg)) return;
        if (!show.id.equals(store.watchingShow())) return;
        PlaybackState s = c.getPlaybackState();
        if (s == null) return;
        long pos = positionNow(s);
        if (s.getState() == PlaybackState.STATE_PLAYING) {
            detectAutoplay(s, pos);
            if (pos > 3_000) store.setPosition(show.id, pos);
        } else {
            int st = s.getState();
            if (st == PlaybackState.STATE_PAUSED || st == PlaybackState.STATE_STOPPED
                    || st == PlaybackState.STATE_NONE || st == PlaybackState.STATE_ERROR) {
                if (pos > 3_000) store.setPosition(show.id, pos);
            }
        }
    }

    /** Where the player is now: the last reported position plus the time that has passed since, if it is playing. */
    private static long positionNow(PlaybackState s) {
        long p = s.getPosition();
        if (s.getState() == PlaybackState.STATE_PLAYING) {
            p += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
        }
        return Math.max(0, p);
    }

    /** The app started the next episode on its own: the position fell back to the start after a long watch. */
    private void detectAutoplay(PlaybackState s, long pos) {
        ShowStore.Progress p = store.get(show.id);
        if (p == null) return;
        boolean watchedLong = System.currentTimeMillis() - store.watchingSince() > AUTOPLAY_MIN_WATCH_MS;
        if (watchedLong && pos < AUTOPLAY_RESET_MS && p.positionMs > pos + AUTOPLAY_RESET_MS) {
            ShowStore.Spot at = show.find(p.season, p.episode);
            ShowStore.Spot next = at == null ? null : show.after(at);
            if (next != null) store.set(show.id, next.season.number, next.episode.number, pos);
        }
    }
}
