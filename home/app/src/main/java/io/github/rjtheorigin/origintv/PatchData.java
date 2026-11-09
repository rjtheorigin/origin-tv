package io.github.rjtheorigin.origintv;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.net.Uri;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the PatchWall-style Home shows, read off the main thread:
 * - Joshi TV's channels and their schedule, from Android's TV database, where Joshi TV's Live Channels copy
 *   (LiveSync) writes them. Only rows marked searchable are visible to the launcher, which LiveSync's are.
 * - The system's Watch Next rows (YouTube, SonyLIV, Mi TV+ on this TV), with their web posters.
 * - The apps of the app row, in the order of patch_home_apps.
 * A query that fails leaves its part empty; Home then shows what it has.
 */
final class PatchData {
    static final String TAG = "TvHomePatch";
    static final String JOSHI = "io.github.rjtheorigin.joshitv";
    /** The schedule read ahead of now: enough for "Starting soon". */
    private static final long AHEAD_MS = 6L * 60 * 60 * 1000;

    /** One programme on one of Joshi TV's channels. Times are epoch milliseconds. */
    static final class Program {
        final long channelRow;
        final String channelName, channelNumber, appLink;
        final String title, description, poster, genre;
        final long start, end;

        Program(Channel channel, String title, String description, String poster, String genre, long start, long end) {
            this.channelRow = channel.row;
            this.channelName = channel.name;
            this.channelNumber = channel.number;
            this.appLink = channel.appLink;
            this.title = title;
            this.description = description == null ? "" : description;
            this.poster = poster == null ? "" : poster;
            this.genre = genre;
            this.start = start;
            this.end = end;
        }

        boolean onNow(long now) {
            return start <= now && now < end;
        }

        /** The poster at a width Jio's image server scales it to; other addresses are left as they are. */
        String posterAt(int width) {
            int q = poster.indexOf("?width=");
            return q < 0 ? poster : poster.substring(0, q) + "?width=" + width;
        }

        String logo() {
            return TvContract.buildChannelLogoUri(channelRow).toString();
        }
    }

    static final class Channel {
        final long row;
        final String name, number, appLink;

        Channel(long row, String name, String number, String appLink) {
            this.row = row;
            this.name = name == null ? "" : name;
            this.number = number == null ? "" : number;
            this.appLink = appLink;
        }
    }

    static final class App {
        final String pkg, label;
        final Drawable banner, icon;
        final Intent launch;

        App(String pkg, String label, Drawable banner, Drawable icon, Intent launch) {
            this.pkg = pkg;
            this.label = label;
            this.banner = banner;
            this.icon = icon;
            this.launch = launch;
        }
    }

    final List<Channel> channels = new ArrayList<>();
    final List<Program> programs = new ArrayList<>();
    final List<ContinueWatching.Item> continueItems = new ArrayList<>();
    final List<App> apps = new ArrayList<>();
    long loadedAt;

    static String joshiInputId() {
        return TvContract.buildInputId(new ComponentName(JOSHI, JOSHI + ".LiveInput"));
    }

    /** Everything at once. Runs on a background thread and never throws. */
    static PatchData load(Context context, List<String> appOrder) {
        PatchData data = new PatchData();
        long now = System.currentTimeMillis();
        data.loadedAt = now;
        ContentResolver cr = context.getContentResolver();
        Map<Long, Channel> byRow = data.readChannels(cr);
        if (!byRow.isEmpty()) data.readPrograms(cr, byRow, now);
        try {
            data.continueItems.addAll(byApp(ContinueWatching.load(context, 60), 24));
        } catch (RuntimeException e) {
            Log.w(TAG, "Watch Next: " + e);
        }
        data.readApps(context, appOrder);
        Log.i(TAG, "loaded: " + data.channels.size() + " channels, " + data.programs.size() + " programmes, "
                + data.continueItems.size() + " to continue, " + data.apps.size() + " apps, "
                + (System.currentTimeMillis() - now) + " ms");
        return data;
    }

    /** Joshi TV's channels only: its input id is part of the address, so no selection is needed (one would be refused). */
    private Map<Long, Channel> readChannels(ContentResolver cr) {
        Map<Long, Channel> byRow = new HashMap<>();
        Uri uri = TvContract.buildChannelsUriForInput(joshiInputId());
        String[] columns = {TvContract.Channels._ID, TvContract.Channels.COLUMN_DISPLAY_NAME,
                TvContract.Channels.COLUMN_DISPLAY_NUMBER, TvContract.Channels.COLUMN_APP_LINK_INTENT_URI};
        try (Cursor c = cr.query(uri, columns, null, null, null)) {
            if (c == null) return byRow;
            while (c.moveToNext()) {
                Channel channel = new Channel(c.getLong(0), c.getString(1), c.getString(2), c.getString(3));
                channels.add(channel);
                byRow.put(channel.row, channel);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "channels not readable: " + e);
        }
        Collections.sort(channels, (a, b) -> compareNumbers(a.number, b.number));
        return byRow;
    }

    /**
     * Every programme overlapping now to now + 6 h, on any channel, through the provider's own time parameters.
     * Rows from other inputs (none on this TV) are dropped by the channel map.
     */
    private void readPrograms(ContentResolver cr, Map<Long, Channel> byRow, long now) {
        // The same parameters TvContract.buildProgramsUriForChannel adds ("start_time", "end_time"; their constants
        // are hidden), without its channel: one query for every channel.
        Uri uri = TvContract.Programs.CONTENT_URI.buildUpon()
                .appendQueryParameter("start_time", String.valueOf(now))
                .appendQueryParameter("end_time", String.valueOf(now + AHEAD_MS))
                .build();
        String[] columns = {TvContract.Programs.COLUMN_CHANNEL_ID, TvContract.Programs.COLUMN_TITLE,
                TvContract.Programs.COLUMN_SHORT_DESCRIPTION, TvContract.Programs.COLUMN_POSTER_ART_URI,
                TvContract.Programs.COLUMN_CANONICAL_GENRE, TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS,
                TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS};
        try (Cursor c = cr.query(uri, columns, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                Channel channel = byRow.get(c.getLong(0));
                String title = c.getString(1);
                if (channel == null || title == null || title.isEmpty()) continue;
                long start = c.getLong(5), end = c.getLong(6);
                if (end <= now) continue;
                programs.add(new Program(channel, title, c.getString(2), c.getString(3),
                        firstGenre(c.getString(4)), start, end));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "programmes not readable: " + e);
        }
        Collections.sort(programs, (a, b) -> Long.compare(a.start, b.start));
    }

    private static String firstGenre(String encoded) {
        if (encoded == null || encoded.isEmpty()) return TvContract.Programs.Genres.ENTERTAINMENT;
        String[] genres = TvContract.Programs.Genres.decode(encoded);
        return genres.length == 0 ? TvContract.Programs.Genres.ENTERTAINMENT : genres[0];
    }

    private void readApps(Context context, List<String> order) {
        PackageManager pm = context.getPackageManager();
        for (String pkg : order) {
            try {
                ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                if (!info.enabled) continue;
                Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
                if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
                if (launch == null) continue;
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                Drawable banner = null;
                if (launch.getComponent() != null) {
                    try {
                        banner = pm.getActivityBanner(launch.getComponent());
                    } catch (PackageManager.NameNotFoundException ignored) {
                        // falls back to the app's banner below
                    }
                }
                if (banner == null) banner = pm.getApplicationBanner(info);
                apps.add(new App(pkg, String.valueOf(pm.getApplicationLabel(info)), banner,
                        pm.getApplicationIcon(info), launch));
            } catch (PackageManager.NameNotFoundException ignored) {
                // not installed on this TV: no tile
            } catch (RuntimeException e) {
                Log.w(TAG, "app " + pkg + ": " + e);
            }
        }
    }

    /**
     * Watch Next sorted by last use can be all one app (Mi TV+ and YouTube update theirs often; Hotstar leaves the time
     * empty, so its rows sort last). Take them in turns, one per app, each app's own order kept, so every app that
     * published rows shows near the front.
     */
    static List<ContinueWatching.Item> byApp(List<ContinueWatching.Item> items, int limit) {
        Map<String, List<ContinueWatching.Item>> perApp = new java.util.LinkedHashMap<>();
        for (ContinueWatching.Item item : items) perApp.computeIfAbsent(item.pkg, k -> new ArrayList<>()).add(item);
        List<ContinueWatching.Item> out = new ArrayList<>();
        for (int round = 0; out.size() < limit; round++) {
            boolean any = false;
            for (List<ContinueWatching.Item> list : perApp.values()) {
                if (round < list.size() && out.size() < limit) {
                    out.add(list.get(round));
                    any = true;
                }
            }
            if (!any) break;
        }
        return out;
    }

    private static int compareNumbers(String a, String b) {
        try {
            return Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
        } catch (NumberFormatException e) {
            return a.compareTo(b);
        }
    }
}
