package io.github.rjtheorigin.joshitv;

import android.util.JsonReader;
import android.util.JsonToken;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Schedules, one channel and day at a time, kept until midnight. Loads block: call them off the main thread. */
final class Epg {
    private final Map<String, List<Show>> days = new ConcurrentHashMap<>();
    private volatile int today = -1;

    List<Show> load(String channelId, int offset) {
        forgetOldDays();
        String key = channelId + "#" + offset;
        List<Show> cached = days.get(key);
        if (cached != null) return cached;
        List<Show> shows;
        try {
            shows = fetch(channelId, offset);
        } catch (IOException | RuntimeException e) {
            shows = Collections.emptyList();
        }
        days.put(key, shows);
        return shows;
    }

    /** What is already loaded for that day, without fetching. Null when not loaded yet. */
    List<Show> cached(String channelId, int offset) {
        return days.get(channelId + "#" + offset);
    }

    boolean hasToday(String channelId) {
        return days.containsKey(channelId + "#0");
    }

    /** What is on now, from what is already loaded, or null. */
    Show now(String channelId) {
        List<Show> shows = days.get(channelId + "#0");
        if (shows == null) return null;
        long time = System.currentTimeMillis();
        for (Show show : shows) {
            if (show.start <= time && time < show.end) return show;
        }
        return null;
    }

    private void forgetOldDays() {
        int day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR);
        if (day != today) {
            days.clear();
            today = day;
        }
    }

    /** One day of one channel, straight from the server, with no cache. */
    static List<Show> fetch(String channelId, int offset) throws IOException {
        HttpURLConnection connection = Api.open("/epg/" + channelId + "/" + offset, 12000);
        try {
            if (connection.getResponseCode() != 200) return Collections.emptyList();
            List<Show> shows = new ArrayList<>();
            try (JsonReader reader = new JsonReader(new InputStreamReader(
                    new BufferedInputStream(connection.getInputStream()), StandardCharsets.UTF_8))) {
                reader.beginObject();
                while (reader.hasNext()) {
                    if ("epg".equals(reader.nextName()) && reader.peek() == JsonToken.BEGIN_ARRAY) {
                        reader.beginArray();
                        while (reader.hasNext()) {
                            Show show = readShow(reader);
                            if (show != null) shows.add(show);
                        }
                        reader.endArray();
                    } else {
                        reader.skipValue();
                    }
                }
                reader.endObject();
            }
            return shows;
        } finally {
            connection.disconnect();
        }
    }

    private static Show readShow(JsonReader reader) throws IOException {
        long start = 0, end = 0;
        String title = null, description = "", poster = "", genre = "", srno = "", category = "";
        boolean catchup = false;
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if (reader.peek() == JsonToken.NULL) {
                reader.skipValue();
            } else if ("showname".equals(name)) {
                title = reader.nextString();
            } else if ("description".equals(name)) {
                description = reader.nextString();
            } else if ("startEpoch".equals(name)) {
                start = reader.nextLong();
            } else if ("endEpoch".equals(name)) {
                end = reader.nextLong();
            } else if ("episodePoster".equals(name)) {
                poster = reader.nextString();
            } else if ("showCategory".equals(name)) {
                category = reader.nextString();
            } else if ("srno".equals(name)) {
                srno = reader.nextString();
            } else if ("isCatchupAvailable".equals(name)) {
                catchup = reader.peek() == JsonToken.BOOLEAN ? reader.nextBoolean()
                        : Boolean.parseBoolean(reader.nextString());
            } else if ("showGenre".equals(name) && reader.peek() == JsonToken.BEGIN_ARRAY) {
                reader.beginArray();
                if (reader.hasNext()) genre = reader.nextString();
                while (reader.hasNext()) reader.skipValue();
                reader.endArray();
            } else {
                reader.skipValue();
            }
        }
        reader.endObject();
        return title == null || start == 0 || end == 0 ? null
                : new Show(start, end, title, description, poster, genre, srno, category, catchup);
    }
}
