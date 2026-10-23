package com.gathin.tvhome;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lists the apps used recently, and closes the ones the viewer picks (or all). A 2 GB TV fills its memory with
 * apps left running, then swaps and every keyboard and menu lags.
 *
 * Android hides which processes are running (and their size) from ordinary apps, so "recently used" stands in for
 * "probably still in memory": it comes from the usage-statistics permission, granted once over adb. Closing uses
 * killBackgroundProcesses, which stops an app's background processes the way the system does under memory
 * pressure and leaves anything on screen. How much that freed is measured from the TV's free RAM before and after.
 */
public class MemoryActivity extends Activity {
    private static final long RECENT_MS = 24L * 60 * 60 * 1000;

    private static final class App {
        final String pkg;
        final String label;
        final Drawable icon;
        long lastUsed;
        boolean closed;   // closed from this screen and not opened since
        boolean selected;

        App(String pkg, String label, Drawable icon, long lastUsed) {
            this.pkg = pkg;
            this.label = label;
            this.icon = icon;
            this.lastUsed = lastUsed;
        }
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final List<App> apps = new ArrayList<>();
    private final Set<String> protect = new HashSet<>();

    private TextView header;
    private TextView status;
    private TextView closeSelected;
    private AppAdapter adapter;
    private RecyclerView list;
    private boolean destroyed;
    private boolean adb;   // close apps through the TV's own adb (config memory_adb)
    private Set<String> launchableCache;
    private final Map<String, Long> closedAt = new LinkedHashMap<>();   // when each app was closed here

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Config cfg = Config.load(this);
        Appearance.apply(this, cfg);
        setContentView(R.layout.activity_memory);
        header = findViewById(R.id.header);
        status = findViewById(R.id.status);
        closeSelected = findViewById(R.id.closeSelected);
        list = findViewById(R.id.apps);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AppAdapter();
        list.setAdapter(adapter);

        adb = cfg.memoryAdb;
        protect.addAll(cfg.memoryProtect);
        protect.add(getPackageName());
        closeSelected.setOnClickListener(v -> closeApps(true));
        findViewById(R.id.closeAll).setOnClickListener(v -> closeApps(false));
        refreshHeader();
        updateButtons();
        load();
        findViewById(R.id.closeAll).requestFocus();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        pool.shutdownNow();
        super.onDestroy();
    }

    /** Free RAM and free swap, one line each, from the framework and /proc/meminfo (both readable without permissions). */
    private void refreshHeader() {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mi);
        long total = mi.totalMem / 1024, swapTotal = 0, swapFree = 0, available = mi.availMem / 1024;
        try (BufferedReader r = new BufferedReader(new java.io.FileReader("/proc/meminfo"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("MemTotal:")) total = kb(line);
                else if (line.startsWith("SwapTotal:")) swapTotal = kb(line);
                else if (line.startsWith("SwapFree:")) swapFree = kb(line);
                else if (line.startsWith("MemAvailable:")) available = kb(line);
            }
        } catch (IOException ignored) {
            // No swap figure: shown as none.
        }
        // MemAvailable is the kernel's figure, the one adb tools and free-memory.sh print; availMem counts more as reclaimable.
        String ram = getString(R.string.memory_ram, mb(available), capacity(total), percent(available, total));
        String swap = swapTotal > 0
                ? getString(R.string.memory_swap, mb(swapFree), capacity(swapTotal), percent(swapFree, swapTotal))
                : getString(R.string.memory_swap_none);
        header.setText(ram + "\n" + swap);
    }

    /** A total as the size it is sold as: 1023 MB of swap is "1 GB", 2097152 kB of RAM is "2 GB", 1.8 GB stays "1.8 GB". */
    private static String capacity(long kb) {
        if (kb < 900 * 1024) return mb(kb);
        float gb = Math.round(kb / 1024f / 1024f * 10) / 10f;
        return gb == Math.round(gb) ? Math.round(gb) + " GB" : String.format(Locale.US, "%.1f GB", gb);
    }

    private static int percent(long part, long whole) {
        return whole > 0 ? Math.round(part * 100f / whole) : 0;
    }

    private static long kb(String meminfoLine) {
        Matcher m = Pattern.compile("(\\d+)").matcher(meminfoLine);
        return m.find() ? Long.parseLong(m.group(1)) : 0;
    }

    private static String mb(long kb) {
        return kb >= 1024 * 1024 ? String.format(Locale.US, "%.1f GB", kb / 1024f / 1024f) : (kb / 1024) + " MB";
    }

    private boolean usageAllowed() {
        AppOpsManager ops = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), getPackageName())
                == AppOpsManager.MODE_ALLOWED;
    }

    private void load() {
        if (!usageAllowed()) {
            apps.clear();
            adapter.notifyDataSetChanged();
            status.setText(R.string.memory_needs_permission);
            return;
        }
        status.setText(R.string.memory_loading);
        pool.execute(() -> {
            final List<App> found = recentApps();
            ui.post(() -> {
                if (destroyed) return;
                apps.clear();
                apps.addAll(found);
                adapter.notifyDataSetChanged();
                refreshHeader();
                updateButtons();
                status.setText(found.isEmpty() ? R.string.memory_none : R.string.memory_hint);
            });
        });
    }

    /** Apps with a launcher entry that were used in the last day, most recent first. */
    private List<App> recentApps() {
        PackageManager pm = getPackageManager();
        UsageStatsManager usm = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
        long now = System.currentTimeMillis();
        List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - RECENT_MS, now);
        Map<String, Long> last = new LinkedHashMap<>();
        if (stats != null) {
            for (UsageStats u : stats) {
                if (u.getLastTimeUsed() <= 0) continue;
                Long old = last.get(u.getPackageName());
                if (old == null || u.getLastTimeUsed() > old) last.put(u.getPackageName(), u.getLastTimeUsed());
            }
        }
        List<App> out = new ArrayList<>();
        for (Map.Entry<String, Long> e : last.entrySet()) {
            String pkg = e.getKey();
            if (protect.contains(pkg) || !launchable(pm).contains(pkg)) continue;
            try {
                CharSequence label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
                out.add(new App(pkg, label.toString(), pm.getApplicationIcon(pkg), e.getValue()));
            } catch (PackageManager.NameNotFoundException ignored) {
                // Uninstalled since: skip.
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(b.lastUsed, a.lastUsed));
        for (App a : out) {
            Long at = closedAt.get(a.pkg);
            a.closed = at != null && a.lastUsed <= at;
        }
        return out;
    }

    /** Every app the viewer can open from the home screen, so system services are never touched. */
    private Set<String> launchable(PackageManager pm) {
        if (launchableCache == null) {
            Set<String> pkgs = new HashSet<>();
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) pkgs.add(ri.activityInfo.packageName);
            launchableCache = pkgs;
        }
        return launchableCache;
    }

    private void updateButtons() {
        int n = 0;
        for (App a : apps) if (a.selected) n++;
        closeSelected.setText(getString(R.string.memory_close_selected, n));
        closeSelected.setAlpha(n > 0 ? 1f : 0.5f);
    }

    private void closeApps(boolean onlySelected) {
        List<String> targets = new ArrayList<>();
        if (onlySelected) {
            for (App a : apps) if (a.selected) targets.add(a.pkg);
        } else {
            // All: every app that can be opened, not only the ones used today.
            for (String pkg : launchable(getPackageManager())) if (!protect.contains(pkg)) targets.add(pkg);
        }
        if (targets.isEmpty()) return;
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        final long before = availKb(am);
        if (adb) {
            closeThroughAdb(targets, am, before);
            return;
        }
        long now = System.currentTimeMillis();
        for (String pkg : targets) {
            am.killBackgroundProcesses(pkg);
            closedAt.put(pkg, now);
        }
        status.setText(R.string.memory_loading);
        measureAfter(targets, am, before, 3500);
    }

    /**
     * Asks the TV's own adb to force-stop the apps (LocalAdb). The first run makes the TV show "Allow USB debugging?",
     * so the screen says to accept it; later runs are silent. Runs off the main thread: it waits on a socket.
     */
    private void closeThroughAdb(List<String> targets, ActivityManager am, long before) {
        final Context app = getApplicationContext();
        status.setText(LocalAdb.authorized(app) ? R.string.memory_loading : R.string.memory_adb_waiting);
        pool.execute(() -> {
            AdbClient.Result result = LocalAdb.forceStop(app, targets, true);
            ui.post(() -> {
                if (destroyed) return;
                if (result != AdbClient.Result.OK) {
                    int message = result == AdbClient.Result.ADB_OFF ? R.string.memory_adb_off
                            : result == AdbClient.Result.NOT_ALLOWED ? R.string.memory_adb_allow : R.string.memory_adb_failed;
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                    load();
                    return;
                }
                long now = System.currentTimeMillis();
                for (String pkg : targets) closedAt.put(pkg, now);
                status.setText(R.string.memory_loading);
                measureAfter(targets, am, before, 2000);
            });
        });
    }

    /** Give the system a moment to tear the processes down, then say how much memory came back and refresh. */
    private void measureAfter(List<String> targets, ActivityManager am, long before, long delayMs) {
        ui.postDelayed(() -> {
            if (destroyed) return;
            long gained = (availKb(am) - before) / 1024;
            if (gained <= 20) {
                // Nothing came back: the apps were small, or this TV ignores the request. Do not call them closed.
                for (String pkg : targets) closedAt.remove(pkg);
            }
            Toast.makeText(this, gained > 20 ? getString(R.string.memory_freed, (int) gained) : getString(R.string.memory_closed),
                    Toast.LENGTH_LONG).show();
            load();
        }, delayMs);
    }

    private static long availKb(ActivityManager am) {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.availMem / 1024;
    }

    private final class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {
        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_memory, parent, false));
        }

        @Override
        public void onBindViewHolder(Holder h, int position) {
            App a = apps.get(position);
            h.name.setText(a.label);
            h.size.setText(a.closed ? getString(R.string.memory_state_closed)
                    : DateUtils.getRelativeTimeSpanString(a.lastUsed, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS));
            h.icon.setImageDrawable(a.icon);
            h.check.setSelected(a.selected);
            h.check.setText(a.selected ? "✓" : "");
            h.itemView.setOnClickListener(v -> {
                a.selected = !a.selected;
                h.check.setSelected(a.selected);
                h.check.setText(a.selected ? "✓" : "");
                updateButtons();
            });
        }

        @Override
        public int getItemCount() {
            return apps.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final TextView check, name, size;
            final ImageView icon;

            Holder(View v) {
                super(v);
                check = v.findViewById(R.id.check);
                name = v.findViewById(R.id.name);
                size = v.findViewById(R.id.size);
                icon = v.findViewById(R.id.icon);
            }
        }
    }
}
