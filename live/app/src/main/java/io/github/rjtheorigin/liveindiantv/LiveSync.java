package io.github.rjtheorigin.liveindiantv;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.OperationApplicationException;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.media.tv.TvContract.Channels;
import android.media.tv.TvContract.Programs;
import android.os.RemoteException;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Copies part of Live Indian TV into Android's TV database, where the Live Channels app reads it: every Hindi and Gujarati
 * channel plus the favourite and recently watched ones, with logos and two days of schedule. The work runs off the
 * main thread, one request at a time with a pause in between, so the TV stays responsive while it runs.
 */
final class LiveSync {
    private static final String TAG = "LiveSync";
    private static final String PREFS = "channels", SET_UP = "live_set_up";
    private static final int JOB_PERIODIC = 41, JOB_SOON = 42;
    private static final long HOUR = 3_600_000L, PERIOD_MS = 12 * HOUR, AHEAD_MS = 18 * HOUR;
    /** Between two downloads, so the stream server and the TV are never kept busy. */
    private static final long PAUSE_MS = 150;
    private static final int LOGO_MAX_BYTES = 300 * 1024, BATCH = 250;
    private static final Object LOCK = new Object();

    private LiveSync() { }

    static String inputId(Context context) {
        return TvContract.buildInputId(new ComponentName(context, LiveInput.class));
    }

    static boolean isSetUp(Context context) {
        return prefs(context).getBoolean(SET_UP, false);
    }

    /** After the Live Channels setup screen: from now on the copy follows favourites and refreshes twice a day. */
    static void markSetUp(Context context) {
        prefs(context).edit().putBoolean(SET_UP, true).apply();
        schedulePeriodic(context);
        syncSoon(context, 0);
    }

    static void schedulePeriodic(Context context) {
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        if (jobs == null || jobs.getPendingJob(JOB_PERIODIC) != null) return;
        jobs.schedule(new JobInfo.Builder(JOB_PERIODIC, new ComponentName(context, LiveSyncJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(PERIOD_MS)
                .setPersisted(true)
                .build());
    }

    /** A favourite or a newly watched channel changed the list: copy it within a couple of minutes. */
    static void syncSoon(Context context) {
        if (isSetUp(context)) syncSoon(context, 30_000);
    }

    private static void syncSoon(Context context, long delayMs) {
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        if (jobs == null) return;
        // Scheduling again replaces the pending job, so a burst of changes runs the copy once.
        jobs.schedule(new JobInfo.Builder(JOB_SOON, new ComponentName(context, LiveSyncJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(delayMs)
                .setOverrideDeadline(delayMs + 90_000)
                .build());
    }

    // ---- Channels

    /** The channels list only: quick enough for the setup screen. Returns how many channels Live Channels has. */
    static int syncChannels(Context context) throws IOException {
        synchronized (LOCK) {
            Server.addLiveUser();
            try {
                return writeChannels(context, wanted(context)).size();
            } finally {
                Server.removeLiveUser();
                Server.stopIfIdle();
            }
        }
    }

    /**
     * The channels, then each one's logo once and its schedule when less than 18 hours of it is left. Everything is
     * downloaded first, at a gentle pace, and only then written, in a few large batches: every write to the TV
     * database makes Live Channels and the TV's own app read it again, and one write per channel kept them busy.
     */
    static void syncAll(Context context, BooleanSupplier stopped) throws IOException {
        synchronized (LOCK) {
            Server.addLiveUser();
            try {
                List<Channel> channels = wanted(context);
                Map<String, Long> rows = writeChannels(context, channels);
                ContentResolver resolver = context.getContentResolver();
                long now = System.currentTimeMillis();
                Map<Long, Long> latest = latestEnds(resolver);
                Map<Long, byte[]> logos = new LinkedHashMap<>();
                Map<Long, List<Show>> schedules = new LinkedHashMap<>();
                Map<Long, Channel> byRow = new HashMap<>();
                for (Channel channel : channels) {
                    if (stopped.getAsBoolean()) return;
                    Long row = rows.get(channel.id);
                    if (row == null) continue;
                    byRow.put(row, channel);
                    if (!channel.logo.isEmpty() && !hasLogo(resolver, row)) {
                        byte[] logo = download(channel.logo);
                        if (logo != null) logos.put(row, logo);
                        pause();
                    }
                    Long end = latest.get(row);
                    if (end == null || end < now + AHEAD_MS) {
                        List<Show> shows = fetchSchedule(channel, now);
                        if (!shows.isEmpty()) schedules.put(row, shows);
                    }
                }
                writeSchedules(resolver, schedules, byRow);
                for (Map.Entry<Long, byte[]> logo : logos.entrySet()) writeLogo(resolver, logo.getKey(), logo.getValue());
                Log.i(TAG, channels.size() + " channels, " + logos.size() + " new logos, " + schedules.size()
                        + " schedules");
            } finally {
                Server.removeLiveUser();
                Server.stopIfIdle();
            }
        }
    }

    /** The household's first languages' channels (Languages), favourites and recently watched, numbered as in Live Indian TV. */
    private static List<Channel> wanted(Context context) throws IOException {
        Server.ensure(context);
        if (Server.needsLogin(context)) throw new IOException("signed out of Jio");
        List<Channel> all = Playlist.parse(Api.get("/playlist.m3u?q=high", 30000));
        if (all.isEmpty()) throw new IOException("empty playlist");
        SharedPreferences prefs = prefs(context);
        Set<String> favourites = prefs.getStringSet("favourites", Collections.emptySet());
        Set<String> recent = new HashSet<>(Arrays.asList(prefs.getString("recent", "").split(",")));
        List<String> preferred = Languages.preferred(context, all, favourites, recent);
        List<String> languages = preferred.subList(0, Math.min(preferred.size(),
                context.getResources().getInteger(R.integer.home_languages)));
        List<Channel> out = new ArrayList<>();
        for (Channel channel : all) {
            if (languages.contains(channel.language) || favourites.contains(channel.id)
                    || recent.contains(channel.id)) {
                out.add(channel);
            }
        }
        return out;
    }

    /** Updates the rows that stay, adds the new ones, deletes the rest; returns Jio id to row id. */
    private static Map<String, Long> writeChannels(Context context, List<Channel> channels) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        String input = inputId(context);
        Map<String, Row> rows = rows(resolver, input);
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        Set<String> keep = new HashSet<>();
        for (Channel channel : channels) {
            keep.add(channel.id);
            ContentValues values = channelValues(context, input, channel);
            Row row = rows.get(channel.id);
            if (row == null) {
                ops.add(ContentProviderOperation.newInsert(Channels.CONTENT_URI).withValues(values).build());
            } else if (!row.same(values)) {
                // Unchanged rows are left alone, so a refresh with nothing new writes nothing.
                ops.add(ContentProviderOperation.newUpdate(TvContract.buildChannelUri(row.id)).withValues(values).build());
            }
        }
        for (Map.Entry<String, Row> entry : rows.entrySet()) {
            // The TV database deletes a removed channel's schedule and logo with it.
            if (!keep.contains(entry.getKey())) {
                ops.add(ContentProviderOperation.newDelete(TvContract.buildChannelUri(entry.getValue().id)).build());
            }
        }
        apply(resolver, ops);
        Map<String, Long> ids = new HashMap<>();
        for (Map.Entry<String, Row> entry : rows(resolver, input).entrySet()) ids.put(entry.getKey(), entry.getValue().id);
        return ids;
    }

    private static void apply(ContentResolver resolver, ArrayList<ContentProviderOperation> ops) throws IOException {
        for (int from = 0; from < ops.size(); from += BATCH) {
            try {
                resolver.applyBatch(TvContract.AUTHORITY,
                        new ArrayList<>(ops.subList(from, Math.min(ops.size(), from + BATCH))));
            } catch (RemoteException | OperationApplicationException e) {
                throw new IOException("TV database: " + e.getMessage(), e);
            }
        }
    }

    private static ContentValues channelValues(Context context, String input, Channel channel) {
        ContentValues values = new ContentValues();
        values.put(Channels.COLUMN_INPUT_ID, input);
        values.put(Channels.COLUMN_DISPLAY_NUMBER, String.valueOf(channel.number));
        values.put(Channels.COLUMN_DISPLAY_NAME, channel.name);
        values.put(Channels.COLUMN_TYPE, Channels.TYPE_OTHER);
        values.put(Channels.COLUMN_SERVICE_TYPE, Channels.SERVICE_TYPE_AUDIO_VIDEO);
        values.put(Channels.COLUMN_SEARCHABLE, 1);
        values.put(Channels.COLUMN_INTERNAL_PROVIDER_ID, channel.id);
        values.put(Channels.COLUMN_INTERNAL_PROVIDER_DATA, LiveInput.encodeStream(channel.url, channel.license));
        Intent open = new Intent(context, MainActivity.class).putExtra("id", channel.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        values.put(Channels.COLUMN_APP_LINK_INTENT_URI, open.toUri(Intent.URI_INTENT_SCHEME));
        values.put(Channels.COLUMN_APP_LINK_TEXT, context.getString(R.string.live_open_in_app));
        return values;
    }

    private static Map<String, Row> rows(ContentResolver resolver, String input) {
        Map<String, Row> rows = new HashMap<>();
        try (Cursor cursor = resolver.query(TvContract.buildChannelsUriForInput(input),
                new String[]{Channels._ID, Channels.COLUMN_INTERNAL_PROVIDER_ID, Channels.COLUMN_DISPLAY_NUMBER,
                        Channels.COLUMN_DISPLAY_NAME, Channels.COLUMN_INTERNAL_PROVIDER_DATA,
                        Channels.COLUMN_APP_LINK_INTENT_URI}, null, null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                if (cursor.isNull(1)) continue;
                rows.put(cursor.getString(1), new Row(cursor.getLong(0), cursor.getString(2), cursor.getString(3),
                        cursor.isNull(4) ? null : cursor.getBlob(4), cursor.getString(5)));
            }
        }
        return rows;
    }

    /** A channel row as the TV database has it, to tell whether an update would change anything. */
    private static final class Row {
        final long id;
        final String number, name, link;
        final byte[] stream;

        Row(long id, String number, String name, byte[] stream, String link) {
            this.id = id;
            this.number = number;
            this.name = name;
            this.stream = stream;
            this.link = link;
        }

        boolean same(ContentValues values) {
            return Objects.equals(number, values.getAsString(Channels.COLUMN_DISPLAY_NUMBER))
                    && Objects.equals(name, values.getAsString(Channels.COLUMN_DISPLAY_NAME))
                    && Objects.equals(link, values.getAsString(Channels.COLUMN_APP_LINK_INTENT_URI))
                    && Arrays.equals(stream, values.getAsByteArray(Channels.COLUMN_INTERNAL_PROVIDER_DATA));
        }
    }

    // ---- Logos

    private static boolean hasLogo(ContentResolver resolver, long row) {
        try (InputStream ignored = resolver.openInputStream(TvContract.buildChannelLogoUri(row))) {
            return true;
        } catch (FileNotFoundException e) {
            return false;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static byte[] download(String url) {
        HttpURLConnection connection = null;
        try {
            connection = Api.open(url, 10000);
            if (connection.getResponseCode() != 200) return null;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] chunk = new byte[16 * 1024];
                int n;
                while ((n = in.read(chunk)) > 0) {
                    bytes.write(chunk, 0, n);
                    if (bytes.size() > LOGO_MAX_BYTES) return null;
                }
            }
            return bytes.toByteArray();
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void writeLogo(ContentResolver resolver, long row, byte[] logo) {
        try (OutputStream out = resolver.openOutputStream(TvContract.buildChannelLogoUri(row))) {
            if (out != null) out.write(logo);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "logo " + row + ": " + e.getMessage());
        }
    }

    // ---- Schedules

    /** How far each of this app's channels already has a schedule, in one query. */
    private static Map<Long, Long> latestEnds(ContentResolver resolver) {
        Map<Long, Long> latest = new HashMap<>();
        try (Cursor cursor = resolver.query(Programs.CONTENT_URI,
                new String[]{Programs.COLUMN_CHANNEL_ID, Programs.COLUMN_END_TIME_UTC_MILLIS}, null, null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                latest.merge(cursor.getLong(0), cursor.getLong(1), Math::max);
            }
        }
        return latest;
    }

    /** Today and tomorrow, without what has already ended. Empty when Jio has no schedule for the channel. */
    private static List<Show> fetchSchedule(Channel channel, long now) {
        List<Show> shows = new ArrayList<>();
        Set<Long> starts = new HashSet<>();
        for (int day = 0; day < 2; day++) {
            try {
                for (Show show : Epg.fetch(channel.id, day)) {
                    if (show.end > now && show.end > show.start && starts.add(show.start)) shows.add(show);
                }
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "schedule " + channel.id + ": " + e.getMessage());
            }
            pause();
        }
        return shows;
    }

    /** Each channel's old schedule out and the new one in, in batches of a few hundred rows. */
    private static void writeSchedules(ContentResolver resolver, Map<Long, List<Show>> schedules,
            Map<Long, Channel> byRow) throws IOException {
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        for (Map.Entry<Long, List<Show>> entry : schedules.entrySet()) {
            long row = entry.getKey();
            ops.add(ContentProviderOperation.newDelete(TvContract.buildProgramsUriForChannel(row)).build());
            for (Show show : entry.getValue()) {
                ops.add(ContentProviderOperation.newInsert(Programs.CONTENT_URI)
                        .withValues(programValues(row, show, byRow.get(row))).build());
            }
            if (ops.size() >= BATCH) {
                apply(resolver, ops);
                ops.clear();
            }
        }
        apply(resolver, ops);
    }

    private static ContentValues programValues(long row, Show show, Channel channel) {
        ContentValues values = new ContentValues();
        values.put(Programs.COLUMN_CHANNEL_ID, row);
        values.put(Programs.COLUMN_TITLE, show.title);
        values.put(Programs.COLUMN_START_TIME_UTC_MILLIS, show.start);
        values.put(Programs.COLUMN_END_TIME_UTC_MILLIS, show.end);
        if (!show.description.isEmpty()) values.put(Programs.COLUMN_SHORT_DESCRIPTION, show.description);
        String poster = show.posterUrl(480);
        if (!poster.isEmpty()) {
            values.put(Programs.COLUMN_POSTER_ART_URI, poster);
            values.put(Programs.COLUMN_THUMBNAIL_URI, poster);
        }
        String genre = genre(show, channel);
        if (genre != null) values.put(Programs.COLUMN_CANONICAL_GENRE, Programs.Genres.encode(genre));
        return values;
    }

    /** Jio's own genre words, mapped to the few Live Channels can filter on. */
    private static String genre(Show show, Channel channel) {
        String text = (show.genre + " " + show.category + " " + channel.category).toLowerCase(Locale.ROOT);
        if (show.isFilm() || text.contains("movie") || text.contains("film")) return Programs.Genres.MOVIES;
        if (text.contains("news")) return Programs.Genres.NEWS;
        if (text.contains("sport")) return Programs.Genres.SPORTS;
        if (text.contains("kid") || text.contains("animation") || text.contains("cartoon")) {
            return Programs.Genres.FAMILY_KIDS;
        }
        if (text.contains("music")) return Programs.Genres.MUSIC;
        if (text.contains("comedy")) return Programs.Genres.COMEDY;
        if (text.contains("drama")) return Programs.Genres.DRAMA;
        if (text.contains("lifestyle") || text.contains("cookery") || text.contains("food")) {
            return Programs.Genres.LIFE_STYLE;
        }
        if (text.contains("travel")) return Programs.Genres.TRAVEL;
        if (text.contains("education") || text.contains("infotainment")) return Programs.Genres.EDUCATION;
        if (text.contains("devotional") || text.contains("spiritual")) return null;
        return Programs.Genres.ENTERTAINMENT;
    }

    // ---- Small things

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void pause() {
        try {
            Thread.sleep(PAUSE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
