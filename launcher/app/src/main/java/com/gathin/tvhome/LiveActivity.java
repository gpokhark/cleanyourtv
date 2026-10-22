package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * A short list of favourite live channels. Free live TV lives inside separate apps (Pluto TV, Tubi, Plex, Xumo...)
 * and none of them lets another app browse its channels, so each favourite is a shortcut from the `live_channels`
 * config: "Name|android.package|link". With a link the app is asked to open that channel; with none (or if the
 * app does not understand the link) the app itself opens. An app that is not installed opens its Play Store page.
 */
public class LiveActivity extends Activity {
    private static final class Channel {
        final String name;
        final String pkg;
        final String link;   // empty = just open the app
        final String service;
        final Drawable icon;

        Channel(String name, String pkg, String link, String service, Drawable icon) {
            this.name = name;
            this.pkg = pkg;
            this.link = link;
            this.service = service;
            this.icon = icon;
        }
    }

    private final List<Channel> channels = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Config cfg = Config.load(this);
        Appearance.apply(this, cfg);
        setContentView(R.layout.activity_live);
        PackageManager pm = getPackageManager();
        for (String entry : cfg.liveChannels) {
            String[] p = entry.split("\\|", 3);
            if (p.length < 2 || p[0].trim().isEmpty() || p[1].trim().isEmpty()) continue;
            String pkg = p[1].trim();
            String service = "";
            Drawable icon = null;
            try {
                service = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
                icon = pm.getApplicationIcon(pkg);
            } catch (PackageManager.NameNotFoundException e) {
                service = pkg;   // not installed: still listed, opens the Play Store
            }
            channels.add(new Channel(p[0].trim(), pkg, p.length > 2 ? p[2].trim() : "", service, icon));
        }
        if (channels.isEmpty()) ((TextView) findViewById(R.id.status)).setText(R.string.live_none);

        RecyclerView list = findViewById(R.id.channels);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(new ChannelAdapter());
    }

    private void open(Channel c) {
        PackageManager pm = getPackageManager();
        Intent launch = pm.getLeanbackLaunchIntentForPackage(c.pkg);
        if (launch == null) launch = pm.getLaunchIntentForPackage(c.pkg);
        if (launch == null) {
            Toast.makeText(this, getString(R.string.live_not_installed, c.service), Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + c.pkg))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException ignored) {
                // No Play Store: the toast is all there is.
            }
            return;
        }
        if (!c.link.isEmpty()) {
            Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(c.link)).setPackage(c.pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(view);
                return;
            } catch (ActivityNotFoundException ignored) {
                // The app does not take that link: fall through and open it normally.
            }
        }
        try {
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, getString(R.string.cant_open, c.service), Toast.LENGTH_SHORT).show();
        }
    }

    private final class ChannelAdapter extends RecyclerView.Adapter<ChannelAdapter.Holder> {
        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_live, parent, false));
        }

        @Override
        public void onBindViewHolder(Holder h, int position) {
            Channel c = channels.get(position);
            h.name.setText(c.name);
            h.service.setText(c.service);
            h.icon.setImageDrawable(c.icon);
            h.itemView.setOnClickListener(v -> open(c));
        }

        @Override
        public int getItemCount() {
            return channels.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            final TextView name, service;
            final ImageView icon;

            Holder(View v) {
                super(v);
                name = v.findViewById(R.id.name);
                service = v.findViewById(R.id.service);
                icon = v.findViewById(R.id.icon);
            }
        }
    }
}
