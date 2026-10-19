package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The shows the viewer is working through: each one's catalogue (seasons and episodes, with the link that opens
 * an episode in its app, made by tools/pluto-catalog.py and pushed to <external files>/shows/), and where they
 * are in it (season, episode and how far into the episode), kept in SharedPreferences.
 */
final class ShowStore {
    private static final String TAG = "TvHome.Shows";
    static final String DIR_NAME = "shows";
    private static final String PREFS = "shows";

    static final class Episode {
        final int number;
        final String name;
        final String url;

        Episode(int number, String name, String url) {
            this.number = number;
            this.name = name;
            this.url = url;
        }
    }

    static final class Season {
        final int number;
        final List<Episode> episodes;

        Season(int number, List<Episode> episodes) {
            this.number = number;
            this.episodes = Collections.unmodifiableList(episodes);
        }
    }

    /** A place in a show: a season and an episode of it. */
    static final class Spot {
        final Season season;
        final Episode episode;

        Spot(Season season, Episode episode) {
            this.season = season;
            this.episode = episode;
        }

        /** "S13 E894" */
        String code() {
            return "S" + season.number + " E" + episode.number;
        }
    }

    static final class Show {
        final String id;
        final String title;
        final String pkg;
        final int order;   // position in the Shows list; ties sort by title
        final List<Season> seasons;

        Show(String id, String title, String pkg, int order, List<Season> seasons) {
            this.id = id;
            this.title = title;
            this.pkg = pkg;
            this.order = order;
            this.seasons = Collections.unmodifiableList(seasons);
        }

        int episodeCount() {
            int n = 0;
            for (Season s : seasons) n += s.episodes.size();
            return n;
        }

        Spot first() {
            return seasons.isEmpty() || seasons.get(0).episodes.isEmpty() ? null
                    : new Spot(seasons.get(0), seasons.get(0).episodes.get(0));
        }

        Spot find(int season, int episode) {
            for (Season s : seasons) {
                if (s.number != season) continue;
                for (Episode e : s.episodes) if (e.number == episode) return new Spot(s, e);
            }
            return null;
        }

        /** The episode after this one, rolling over into the next season; null at the very end. */
        Spot after(Spot at) {
            int i = at.season.episodes.indexOf(at.episode);
            if (i >= 0 && i + 1 < at.season.episodes.size()) return new Spot(at.season, at.season.episodes.get(i + 1));
            int s = seasons.indexOf(at.season);
            if (s >= 0 && s + 1 < seasons.size() && !seasons.get(s + 1).episodes.isEmpty()) {
                Season next = seasons.get(s + 1);
                return new Spot(next, next.episodes.get(0));
            }
            return null;
        }
    }

    /** How far the viewer got: the episode, and the time into it. */
    static final class Progress {
        final int season;
        final int episode;
        final long positionMs;

        Progress(int season, int episode, long positionMs) {
            this.season = season;
            this.episode = episode;
            this.positionMs = positionMs;
        }
    }

    private final SharedPreferences prefs;

    ShowStore(Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Every catalogue in the shows folder, by their order and then title. Unreadable files are skipped. */
    static List<Show> load(Context ctx) {
        List<Show> out = new ArrayList<>();
        File base = Config.dir(ctx);
        File[] files = base == null ? null : new File(base, DIR_NAME).listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) return out;
        Arrays.sort(files);
        for (File f : files) {
            try {
                out.add(parse(read(f)));
            } catch (IOException | JSONException e) {
                Log.w(TAG, "skipping " + f.getName() + ": " + e);
            }
        }
        Collections.sort(out, (a, b) -> a.order != b.order ? Integer.compare(a.order, b.order)
                : a.title.compareToIgnoreCase(b.title));
        return out;
    }

    private static Show parse(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        List<Season> seasons = new ArrayList<>();
        JSONArray ss = o.getJSONArray("seasons");
        for (int i = 0; i < ss.length(); i++) {
            JSONObject s = ss.getJSONObject(i);
            JSONArray es = s.getJSONArray("episodes");
            List<Episode> eps = new ArrayList<>();
            for (int j = 0; j < es.length(); j++) {
                JSONObject e = es.getJSONObject(j);
                eps.add(new Episode(e.getInt("n"), e.optString("name"), e.getString("url")));
            }
            seasons.add(new Season(s.getInt("n"), eps));
        }
        return new Show(o.getString("id"), o.optString("title", o.getString("id")), o.getString("package"),
                o.optInt("order", 0), seasons);
    }

    private static String read(File f) throws IOException {
        if (f.length() > 4 * 1024 * 1024) throw new IOException("too large");
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    /** Where the viewer is in a show, or null when they have not started. */
    Progress get(String showId) {
        if (!prefs.contains(showId + ".season")) return null;
        return new Progress(prefs.getInt(showId + ".season", 0), prefs.getInt(showId + ".episode", 0),
                prefs.getLong(showId + ".pos", 0));
    }

    void set(String showId, int season, int episode, long positionMs) {
        prefs.edit().putInt(showId + ".season", season).putInt(showId + ".episode", episode)
                .putLong(showId + ".pos", Math.max(0, positionMs)).apply();
    }

    void setPosition(String showId, long positionMs) {
        prefs.edit().putLong(showId + ".pos", Math.max(0, positionMs)).apply();
    }

    // ---- the show being watched right now (set when an episode is opened from the Shows screen) ----

    /** Marks a show as being watched: its app's playback now counts towards the viewer's place in it. */
    void startWatching(String showId) {
        prefs.edit().putString("watching.show", showId).putLong("watching.since", System.currentTimeMillis()).apply();
    }

    void stopWatching() {
        prefs.edit().remove("watching.show").remove("watching.since").apply();
    }

    String watchingShow() {
        String id = prefs.getString("watching.show", null);
        // A marker left over from long ago must not claim whatever the viewer plays next.
        return id != null && System.currentTimeMillis() - watchingSince() < 6L * 60 * 60 * 1000 ? id : null;
    }

    long watchingSince() {
        return prefs.getLong("watching.since", 0);
    }
}
