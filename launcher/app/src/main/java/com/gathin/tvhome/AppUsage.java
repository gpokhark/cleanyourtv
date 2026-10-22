package com.gathin.tvhome;

import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** When each app was last used, from the usage-statistics permission (granted once over adb, see docs/MEMORY.md). */
final class AppUsage {
    private AppUsage() {
    }

    static boolean allowed(Context ctx) {
        AppOpsManager ops = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.getPackageName())
                == AppOpsManager.MODE_ALLOWED;
    }

    /** Package to the time it was last used, for apps used within {@code withinMs}; empty without the permission. */
    static Map<String, Long> lastUsed(Context ctx, long withinMs) {
        Map<String, Long> last = new HashMap<>();
        if (!allowed(ctx)) return last;
        UsageStatsManager usm = (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
        long now = System.currentTimeMillis();
        List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - withinMs, now);
        if (stats == null) return last;
        for (UsageStats u : stats) {
            if (u.getLastTimeUsed() <= 0) continue;
            Long old = last.get(u.getPackageName());
            if (old == null || u.getLastTimeUsed() > old) last.put(u.getPackageName(), u.getLastTimeUsed());
        }
        return last;
    }
}
