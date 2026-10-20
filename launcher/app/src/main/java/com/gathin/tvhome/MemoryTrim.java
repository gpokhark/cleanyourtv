package com.gathin.tvhome;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.util.Log;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * Closes idle apps when Home is pressed and free memory is low, so a 2 GB TV does not slide into swap. It does what
 * the Memory pill's "close all" does (killBackgroundProcesses), with three limits: it only runs below the free-memory
 * threshold, it runs at most once in COOLDOWN_MS, and it keeps the app just left (for a quick return), anything
 * playing, and the memory_protect list. Off unless auto_trim is on; needs the usage-statistics permission.
 */
final class MemoryTrim {
    private static final String TAG = "TvHome.Trim";
    private static final String PREFS = "trim";
    private static final long COOLDOWN_MS = 10L * 60 * 1000;
    private static final long RECENT_MS = 24L * 60 * 60 * 1000;
    private static final long DELAY_MS = 2000;   // after Home has drawn, so the screen is not held up

    private MemoryTrim() {
    }

    static void onHome(Context ctx, Config cfg) {
        if (!cfg.autoTrim) return;
        final Context app = ctx.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (now - prefs.getLong("last", 0) < COOLDOWN_MS) return;
        ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
        if (availMb(am) >= cfg.autoTrimBelowMb) return;
        if (!AppUsage.allowed(app)) {
            Log.w(TAG, "usage access not granted: appops set " + app.getPackageName() + " GET_USAGE_STATS allow");
            return;
        }
        prefs.edit().putLong("last", now).apply();
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                Thread.sleep(DELAY_MS);
            } catch (InterruptedException e) {
                return;
            }
            trim(app, am, cfg);
        });
    }

    private static void trim(Context ctx, ActivityManager am, Config cfg) {
        long before = availMb(am);
        Set<String> keep = new HashSet<>(cfg.memoryProtect);
        keep.add(ctx.getPackageName());
        keep.addAll(playing(ctx));
        // The app used just before Home stays: coming straight back to it should be quick.
        String latest = null;
        long latestAt = 0;
        for (Map.Entry<String, Long> e : AppUsage.lastUsed(ctx, RECENT_MS).entrySet()) {
            if (!e.getKey().equals(ctx.getPackageName()) && e.getValue() > latestAt) {
                latest = e.getKey();
                latestAt = e.getValue();
            }
        }
        if (latest != null) keep.add(latest);
        int closed = 0;
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : ctx.getPackageManager().queryIntentActivities(probe, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (keep.contains(pkg)) continue;
            am.killBackgroundProcesses(pkg);
            closed++;
        }
        Log.i(TAG, "closed " + closed + " apps, kept " + keep.size() + ", free " + before + " MB before");
    }

    /** Apps with a playing media session. Needs the notification listener; without it nothing is known. */
    private static Set<String> playing(Context ctx) {
        Set<String> out = new HashSet<>();
        try {
            MediaSessionManager msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> sessions = msm.getActiveSessions(new ComponentName(ctx, MediaWatcher.class));
            for (MediaController c : sessions) {
                PlaybackState s = c.getPlaybackState();
                if (s != null && (s.getState() == PlaybackState.STATE_PLAYING || s.getState() == PlaybackState.STATE_BUFFERING)) {
                    out.add(c.getPackageName());
                }
            }
        } catch (SecurityException e) {
            Log.w(TAG, "cannot see media sessions (notification listener off): " + e);
        }
        return out;
    }

    private static long availMb(ActivityManager am) {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.availMem / (1024 * 1024);
    }
}
