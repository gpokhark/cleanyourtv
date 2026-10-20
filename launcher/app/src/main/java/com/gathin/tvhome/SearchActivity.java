package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Search for a movie or series and see which of the viewer's own services carry it at no extra cost.
 * One request finds the titles; one more per result (cached for days) says where each can be watched.
 * The data source is a {@link TitleSearch.Provider}; which services count comes from `my_services`.
 */
public class SearchActivity extends Activity {
    private static final int GREEN = 0xFF1FA84F;   // readable on both the black row and the white focused row

    private static final class Row {
        final TitleSearch.Title title;
        boolean done;
        boolean failed;
        List<MyServices.Hit> hits = new ArrayList<>();

        Row(TitleSearch.Title title) {
            this.title = title;
        }

        boolean watchable() {
            return done && !hits.isEmpty();
        }
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final List<Row> rows = new ArrayList<>();
    private final List<Row> shown = new ArrayList<>();

    private EditText query;
    private TextView filter;
    private TextView status;
    private ResultAdapter adapter;
    private MyServices mine;
    private TitleSearch.Provider provider;
    private int maxResults;
    private int generation;       // bumps on every search so answers to an older one are dropped
    private boolean onlyMine = true;
    private boolean searched;
    private boolean problem;      // the status line is showing an error for this search; refresh() leaves it alone
    private int checked;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_search);
        query = findViewById(R.id.query);
        filter = findViewById(R.id.filter);
        status = findViewById(R.id.status);

        Config cfg = Config.load(this);
        useSearchKeyboard(cfg.searchKeyboard);
        addExtras(cfg.searchExtras);
        mine = MyServices.parse(cfg.myServices);
        maxResults = cfg.searchMaxResults;
        try {
            provider = SearchProviders.create(this, cfg.searchProvider, cfg.searchRegion);
        } catch (TitleSearch.Failure f) {
            status.setText(message(f));
        }

        RecyclerView list = findViewById(R.id.results);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ResultAdapter();
        list.setAdapter(adapter);

        query.setOnEditorActionListener((v, action, e) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH
                    || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER && e.getAction() == KeyEvent.ACTION_DOWN)) {
                hideKeyboard();
                search(query.getText().toString().trim());
                return true;
            }
            return false;
        });
        filter.setOnClickListener(v -> {
            onlyMine = !onlyMine;
            refresh();
        });
        refresh();
        query.requestFocus();
    }

    /** One button per `search_extras` entry, sending the typed text to that app's own search. */
    private void addExtras(List<String> entries) {
        android.widget.LinearLayout box = findViewById(R.id.extras);
        for (String entry : entries) {
            String[] p = entry.split("\\|", 3);
            if (p.length < 3 || p[0].trim().isEmpty() || p[2].trim().isEmpty()) continue;
            final String label = p[0].trim(), pkg = p[1].trim(), template = p[2].trim();
            TextView b = (TextView) getLayoutInflater().inflate(R.layout.button_search_extra, box, false);
            b.setText(getString(R.string.search_on_app, label));
            b.setNextFocusUpId(R.id.query);
            b.setNextFocusDownId(R.id.results);
            b.setOnClickListener(v -> searchInApp(label, pkg, template));
            box.addView(b);
        }
    }

    private void searchInApp(String label, String pkg, String template) {
        String q = query.getText().toString().trim();
        if (q.isEmpty()) {
            Toast.makeText(this, R.string.search_type_first, Toast.LENGTH_SHORT).show();
            query.requestFocus();
            return;
        }
        hideKeyboard();
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(template.replace("{q}", Uri.encode(q))))
                .setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, getString(R.string.cant_open, label), Toast.LENGTH_SHORT).show();
        }
    }

    /** Makes `keyboard` the default input method if the TV has switched to another (needs WRITE_SECURE_SETTINGS). */
    private void useSearchKeyboard(String keyboard) {
        if (keyboard.isEmpty()) return;
        try {
            String now = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            if (!keyboard.equals(now)) {
                Settings.Secure.putString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD, keyboard);
            }
        } catch (SecurityException e) {
            // Not granted: the TV's own keyboard is used as it is.
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        InputKeyService.pause(this);   // the on-screen keyboard follows the remote only with it off
    }

    @Override
    protected void onPause() {
        InputKeyService.resume(this);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        generation++;
        pool.shutdownNow();
        super.onDestroy();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(query.getWindowToken(), 0);
    }

    private void search(String q) {
        if (q.isEmpty() || provider == null) return;
        final int gen = ++generation;
        rows.clear();
        searched = true;
        problem = false;
        checked = 0;
        status.setText(R.string.search_searching);
        refresh();
        pool.execute(() -> {
            try {
                List<TitleSearch.Title> titles = provider.search(q);
                if (titles.size() > maxResults) titles = titles.subList(0, maxResults);
                final List<TitleSearch.Title> found = titles;
                ui.post(() -> {
                    if (gen != generation) return;
                    if (found.isEmpty()) {
                        status.setText(R.string.search_none);
                        return;
                    }
                    for (TitleSearch.Title t : found) rows.add(new Row(t));
                    refresh();
                    for (Row r : new ArrayList<>(rows)) lookUp(gen, r);
                });
            } catch (TitleSearch.Failure f) {
                ui.post(() -> {
                    if (gen != generation) return;
                    problem = true;
                    status.setText(message(f));
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (gen != generation) return;
                    problem = true;
                    status.setText(R.string.search_error);
                });
            }
        });
    }

    private void lookUp(int gen, Row row) {
        pool.execute(() -> {
            List<MyServices.Hit> hits = null;
            TitleSearch.Failure failure = null;
            try {
                hits = mine.match(provider.sources(row.title));
            } catch (TitleSearch.Failure f) {
                failure = f;
            } catch (Exception e) {
                failure = new TitleSearch.Failure(TitleSearch.Failure.Kind.OTHER, "");
            }
            final List<MyServices.Hit> got = hits;
            final TitleSearch.Failure failed = failure;
            ui.post(() -> {
                if (gen != generation) return;
                row.done = true;
                if (got != null) row.hits = got;
                else row.failed = true;
                checked++;
                // A limit or key problem will hit every row the same way: say it once, in the status line.
                if (failed != null && failed.kind != TitleSearch.Failure.Kind.OTHER && !problem) {
                    problem = true;
                    status.setText(message(failed));
                }
                refresh();
            });
        });
    }

    /** Rebuilds what is on screen from the rows and the filter. */
    private void refresh() {
        filter.setText(onlyMine ? R.string.search_filter_on : R.string.search_filter_off);
        shown.clear();
        int watchable = 0;
        for (Row r : rows) {
            if (r.watchable()) watchable++;
            if (!onlyMine || r.watchable()) shown.add(r);
        }
        adapter.notifyDataSetChanged();
        if (!searched || problem || rows.isEmpty()) return;
        if (checked < rows.size()) {
            status.setText(getString(R.string.search_checking, checked, rows.size()));
        } else if (onlyMine && watchable == 0) {
            status.setText(getString(R.string.search_none_mine, rows.size()));
            // Nothing to select in the list, so rest on the filter: OK there shows the rest.
            if (query.hasFocus()) filter.requestFocus();
        } else if (onlyMine) {
            status.setText(getString(R.string.search_count, watchable, rows.size()));
        } else {
            status.setText(getString(R.string.search_all, rows.size()));
        }
    }

    private String message(TitleSearch.Failure f) {
        switch (f.kind) {
            case NO_KEY: return getString(R.string.search_no_key);
            case REJECTED_KEY: return getString(R.string.search_bad_key);
            case LIMIT: return getString(R.string.search_limit);
            case OFFLINE: return getString(R.string.search_offline);
            default: return f.getMessage() == null || f.getMessage().isEmpty() ? getString(R.string.search_error) : f.getMessage();
        }
    }

    private void onPick(Row row) {
        if (row.hits.isEmpty()) {
            Toast.makeText(this, R.string.search_not_included, Toast.LENGTH_SHORT).show();
        } else if (row.hits.size() == 1) {
            openService(row.hits.get(0).service);
        } else {
            String[] labels = new String[row.hits.size()];
            for (int i = 0; i < labels.length; i++) {
                MyServices.Hit h = row.hits.get(i);
                labels[i] = h.free ? h.service.label + " (" + getString(R.string.search_free_suffix) + ")" : h.service.label;
            }
            new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle(row.title.name)
                    .setItems(labels, (d, which) -> openService(row.hits.get(which).service))
                    .show();
        }
    }

    /** Opens the app; if it isn't installed, its Play Store page. There is no link to the title itself. */
    private void openService(MyServices.Service s) {
        Intent i = getPackageManager().getLaunchIntentForPackage(s.pkg);
        if (i == null) {
            i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(s.pkg);
            if (getPackageManager().resolveActivity(i, 0) == null) i = null;
        }
        try {
            if (i != null) {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else {
                Toast.makeText(this, getString(R.string.search_not_installed, s.label), Toast.LENGTH_LONG).show();
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + s.pkg))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, getString(R.string.cant_open, s.label), Toast.LENGTH_SHORT).show();
        }
    }

    private final class ResultAdapter extends RecyclerView.Adapter<ResultAdapter.Holder> {
        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_result, parent, false));
        }

        @Override
        public void onBindViewHolder(Holder h, int position) {
            Row r = shown.get(position);
            h.title.setText(r.title.name);
            h.meta.setText(r.title.year > 0 ? r.title.kind + " · " + r.title.year : r.title.kind);
            if (!r.done) {
                h.where.setText(R.string.search_checking_row);
                h.where.setTextColor(0x99FFFFFF);
            } else if (r.failed) {
                h.where.setText(R.string.search_row_failed);
                h.where.setTextColor(0x99FFFFFF);
            } else if (r.hits.isEmpty()) {
                h.where.setText(R.string.search_pay);
                h.where.setTextColor(0x99FFFFFF);
            } else {
                StringBuilder sb = new StringBuilder();
                for (MyServices.Hit hit : r.hits) {
                    if (sb.length() > 0) sb.append("  ·  ");
                    sb.append(hit.service.label);
                    if (hit.free) sb.append(" (").append(getString(R.string.search_free_suffix)).append(')');
                }
                h.where.setText(sb);
                h.where.setTextColor(GREEN);
            }
            h.itemView.setOnClickListener(v -> onPick(r));
        }

        @Override
        public int getItemCount() {
            return shown.size();
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
