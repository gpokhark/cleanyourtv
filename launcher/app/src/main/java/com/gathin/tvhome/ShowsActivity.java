package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Where the viewer is in each show they are working through, with one press to carry on: resume the episode at the
 * spot they stopped, play the next one, or pick any season and episode. The show catalogues come from
 * tools/pluto-catalog.py; the place is kept by {@link ShowStore} and, while an episode plays, updated by
 * {@link MediaWatcher}.
 */
public class ShowsActivity extends Activity {
    private static final class Row {
        final String title;
        final String meta;
        final String right;
        final Runnable action;
        final Runnable longAction;   // hold OK; may be null

        Row(String title, String meta, String right, Runnable action) {
            this(title, meta, right, action, null);
        }

        Row(String title, String meta, String right, Runnable action, Runnable longAction) {
            this.title = title;
            this.meta = meta;
            this.right = right;
            this.action = action;
            this.longAction = longAction;
        }
    }

    private enum Mode { SHOWS, DETAIL, SEASONS, EPISODES, YOUTUBE }

    private final List<Row> rows = new ArrayList<>();
    private List<ShowStore.Show> shows = new ArrayList<>();
    private ShowStore store;
    private Mode mode = Mode.SHOWS;
    private ShowStore.Show show;
    private ShowStore.Season season;
    private int youtubeFocus;   // where the cursor goes after a video is removed
    private String youtubePkg = "";   // empty = no "Last on YouTube" entry
    private boolean soleShow;   // with a single show the list level is skipped
    private TextView title, sub, kicker;
    private RecyclerView list;
    private RowAdapter adapter;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_shows);
        title = findViewById(R.id.title);
        sub = findViewById(R.id.sub);
        kicker = findViewById(R.id.kicker);
        list = findViewById(R.id.rows);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        store = new ShowStore(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        shows = ShowStore.load(this);
        youtubePkg = Config.load(this).youtubePackage;
        soleShow = shows.size() == 1 && youtubePkg.isEmpty();
        // Come back to the same show after watching: pick it up again from the reloaded catalogue.
        if (show != null) {
            ShowStore.Show again = null;
            for (ShowStore.Show s : shows) if (s.id.equals(show.id)) again = s;
            show = again;
            if (show == null) mode = Mode.SHOWS;
            else season = null;
            if (show != null && (mode == Mode.SEASONS || mode == Mode.EPISODES)) mode = Mode.DETAIL;
        } else if (soleShow) {
            show = shows.get(0);
            mode = Mode.DETAIL;
        }
        render();
    }

    @Override
    public void onBackPressed() {
        switch (mode) {
            case YOUTUBE:
                mode = Mode.SHOWS;
                render();
                return;
            case EPISODES:
                mode = Mode.SEASONS;
                render();
                return;
            case SEASONS:
                mode = Mode.DETAIL;
                render();
                return;
            case DETAIL:
                if (!soleShow) {
                    mode = Mode.SHOWS;
                    show = null;
                    render();
                    return;
                }
                break;
            default:
                break;
        }
        super.onBackPressed();
    }

    // ---- screens ----

    private void render() {
        rows.clear();
        int focus = 0;
        if (shows.isEmpty() && youtubePkg.isEmpty()) {
            kicker.setText(R.string.shows);
            title.setText(R.string.shows_none);
            sub.setText("");
        } else if (mode == Mode.YOUTUBE) {
            focus = renderYoutube();
        } else if (mode == Mode.SHOWS || show == null) {
            kicker.setText(R.string.shows);
            title.setText(R.string.shows);
            sub.setText("");
            if (!youtubePkg.isEmpty()) {
                List<ShowStore.Video> seen = store.youtube();
                rows.add(new Row(getString(R.string.shows_youtube),
                        seen.isEmpty() ? getString(R.string.shows_youtube_none) : seen.get(0).title, "", () -> {
                            mode = Mode.YOUTUBE;
                            render();
                        }));
                if (!shows.isEmpty()) focus = 1;   // the shows come first when the cursor lands
            }
            for (ShowStore.Show s : shows) {
                rows.add(new Row(s.title, progressLine(s), "", () -> {
                    show = s;
                    mode = Mode.DETAIL;
                    render();
                }));
            }
        } else if (mode == Mode.DETAIL) {
            focus = renderDetail();
        } else if (mode == Mode.SEASONS) {
            focus = renderSeasons();
        } else {
            focus = renderEpisodes();
        }
        adapter.notifyDataSetChanged();
        final int at = focus;
        // With a "Last on YouTube" row above the shows, the cursor sits on the first show but the row stays in view.
        list.scrollToPosition(mode == Mode.SHOWS && at == 1 ? 0 : at);
        list.post(() -> {
            RecyclerView.LayoutManager lm = list.getLayoutManager();
            View v = lm == null ? null : lm.findViewByPosition(at);
            if (v == null && lm != null && lm.getChildCount() > 0) v = lm.getChildAt(0);
            if (v != null) v.requestFocus();
        });
    }

    /** The list of videos; the cursor goes where a removal left it, or to the top. Returns that position. */
    private int renderYoutube() {
        kicker.setText(R.string.shows);
        title.setText(R.string.shows_youtube);
        List<ShowStore.Video> seen = store.youtube();
        sub.setText(getString(seen.isEmpty() ? R.string.shows_youtube_empty : R.string.shows_youtube_hint));
        for (ShowStore.Video v : seen) {
            rows.add(new Row(v.title, v.channel.isEmpty() ? getString(R.string.shows_youtube_row_bare)
                    : getString(R.string.shows_youtube_row, v.channel),
                    DateUtils.getRelativeTimeSpanString(v.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                    () -> openYoutube(v), () -> removeYoutube(v, seen.indexOf(v))));
        }
        int at = Math.max(0, Math.min(youtubeFocus, seen.size() - 1));
        youtubeFocus = 0;
        return at;
    }

    /** Hold OK on a video: takes it off the list and keeps the cursor on the next one. */
    private void removeYoutube(ShowStore.Video v, int position) {
        store.removeYoutube(v.title, v.channel);
        youtubeFocus = position;
        Toast.makeText(this, R.string.shows_youtube_removed, Toast.LENGTH_SHORT).show();
        render();
    }

    /** YouTube's own search for the video: the app picks up where you stopped, as it does for any video you reopen. */
    private void openYoutube(ShowStore.Video v) {
        String q = Uri.encode((v.title + " " + v.channel).trim());
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + q))
                    .setPackage(youtubePkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, getString(R.string.live_not_installed, youtubePkg), Toast.LENGTH_LONG).show();
        }
    }

    private int renderDetail() {
        kicker.setText(R.string.shows);
        title.setText(show.title);
        ShowStore.Progress p = store.get(show.id);
        ShowStore.Spot here = p == null ? null : show.find(p.season, p.episode);
        sub.setText(progressLine(show));
        if (here == null) {
            ShowStore.Spot first = show.first();
            if (first != null) {
                rows.add(new Row(getString(R.string.shows_play), episodeLine(first), "", () -> play(first, 0)));
            }
        } else {
            boolean mid = p.positionMs >= 15_000;
            rows.add(new Row(getString(mid ? R.string.shows_resume : R.string.shows_play), episodeLine(here),
                    mid ? getString(R.string.shows_at, clock(p.positionMs)) : "", () -> play(here, mid ? p.positionMs : 0)));
            ShowStore.Spot next = show.after(here);
            if (next != null) {
                rows.add(new Row(getString(R.string.shows_next), episodeLine(next), "", () -> play(next, 0)));
            }
        }
        rows.add(new Row(getString(R.string.shows_choose), getString(R.string.shows_choose_meta), "", () -> {
            mode = Mode.SEASONS;
            render();
        }));
        if (here != null) {
            ShowStore.Spot next = show.after(here);
            if (next != null) {
                rows.add(new Row(getString(R.string.shows_done), getString(R.string.shows_done_meta, next.code()), "", () -> {
                    store.stopWatching();
                    store.set(show.id, next.season.number, next.episode.number, 0);
                    render();
                }));
            }
            ShowStore.Spot first = show.first();
            if (first != null) {
                rows.add(new Row(getString(R.string.shows_restart), first.code(), "", () -> {
                    store.stopWatching();
                    store.set(show.id, first.season.number, first.episode.number, 0);
                    render();
                }));
            }
        }
        return 0;
    }

    private int renderSeasons() {
        kicker.setText(show.title);
        title.setText(R.string.shows_choose);
        sub.setText(getString(R.string.shows_choose_meta));
        ShowStore.Progress p = store.get(show.id);
        int focus = 0;
        for (int i = 0; i < show.seasons.size(); i++) {
            ShowStore.Season s = show.seasons.get(i);
            boolean current = p != null && p.season == s.number;
            if (current) focus = i;
            ShowStore.Episode a = s.episodes.get(0), z = s.episodes.get(s.episodes.size() - 1);
            rows.add(new Row("Season " + s.number, "E" + a.number + " to E" + z.number + " · "
                    + getString(R.string.shows_episodes, s.episodes.size()),
                    current ? getString(R.string.shows_you_are_here) : "", () -> {
                        season = s;
                        mode = Mode.EPISODES;
                        render();
                    }));
        }
        return focus;
    }

    private int renderEpisodes() {
        kicker.setText(show.title + " · Season " + season.number);
        title.setText("Season " + season.number);
        sub.setText(getString(R.string.shows_episodes_hold, season.episodes.size()));
        ShowStore.Progress p = store.get(show.id);
        int focus = 0;
        for (int i = 0; i < season.episodes.size(); i++) {
            ShowStore.Episode e = season.episodes.get(i);
            boolean current = p != null && p.season == season.number && p.episode == e.number;
            if (current) focus = i;
            ShowStore.Spot spot = new ShowStore.Spot(season, e);
            rows.add(new Row("E" + e.number + " · " + e.name, getString(R.string.shows_episode_hint),
                    current ? getString(R.string.shows_you_are_here) : "", () -> play(spot, 0), () -> watchedUpTo(spot)));
        }
        return focus;
    }

    // ---- text ----

    private String progressLine(ShowStore.Show s) {
        ShowStore.Progress p = store.get(s.id);
        ShowStore.Spot here = p == null ? null : s.find(p.season, p.episode);
        if (here == null) return getString(R.string.shows_not_started);
        return p.positionMs >= 15_000
                ? getString(R.string.shows_progress, here.code(), clock(p.positionMs))
                : here.code();
    }

    private String episodeLine(ShowStore.Spot s) {
        return getString(R.string.shows_place, s.code(), s.episode.name);
    }

    private static String clock(long ms) {
        long sec = ms / 1000;
        long h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, s) : String.format(Locale.US, "%d:%02d", m, s);
    }

    /** Hold OK on an episode: everything up to and including it is watched, so the place moves to the next one. */
    private void watchedUpTo(ShowStore.Spot spot) {
        ShowStore.Spot next = show.after(spot);
        if (next == null) {
            Toast.makeText(this, R.string.shows_the_end, Toast.LENGTH_LONG).show();
            return;
        }
        store.stopWatching();
        store.set(show.id, next.season.number, next.episode.number, 0);
        Toast.makeText(this, getString(R.string.shows_place_set, spot.code(), next.code()), Toast.LENGTH_LONG).show();
        mode = Mode.DETAIL;
        render();
    }

    // ---- playing ----

    /** Opens an episode in its app and records it as the place; {@code startMs} is where to jump to once it plays. */
    private void play(ShowStore.Spot spot, long startMs) {
        store.set(show.id, spot.season.number, spot.episode.number, startMs);
        store.startWatching(show.id);
        if (!listenerEnabled()) Toast.makeText(this, R.string.shows_listener_hint, Toast.LENGTH_LONG).show();
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(spot.episode.url)).setPackage(show.pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, getString(R.string.live_not_installed, show.title), Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + show.pkg))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException ignored) {
                // No Play Store: the toast is all there is.
            }
        }
    }

    private boolean listenerEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return enabled != null && enabled.contains(getPackageName());
    }

    private final class RowAdapter extends RecyclerView.Adapter<RowAdapter.Holder> {
        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_result, parent, false));
        }

        @Override
        public void onBindViewHolder(Holder h, int position) {
            Row r = rows.get(position);
            h.title.setText(r.title);
            h.meta.setText(r.meta);
            h.meta.setVisibility(r.meta.isEmpty() ? View.GONE : View.VISIBLE);
            h.where.setText(r.right);
            h.where.setTextColor(0xFF1FA84F);
            h.itemView.setOnClickListener(v -> r.action.run());
            h.itemView.setOnLongClickListener(r.longAction == null ? null : v -> {
                r.longAction.run();
                return true;
            });        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final TextView title, meta, where;

            Holder(View v) {
                super(v);
                title = v.findViewById(R.id.title);
                meta = v.findViewById(R.id.meta);
                where = v.findViewById(R.id.where);
            }
        }
    }
}
