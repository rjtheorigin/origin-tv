package io.github.rjtheorigin.joshitv;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** A catch-up programme watched part of the way, kept so Home can offer to carry on. */
final class Resume {
    private static final int KEEP = 12;
    private static final long WEEK = 7L * 24 * 60 * 60 * 1000;
    final String srno, channelId, title, poster;
    final long start, end, position;

    Resume(String srno, String channelId, String title, String poster, long start, long end, long position) {
        this.srno = srno;
        this.channelId = channelId;
        this.title = title;
        this.poster = poster;
        this.start = start;
        this.end = end;
        this.position = position;
    }

    long duration() {
        return Math.max(1, end - start);
    }

    Show show() {
        return new Show(start, end, title, "", poster, "", srno, "", true);
    }

    static List<Resume> read(String json) {
        List<Resume> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(json == null ? "[]" : json);
            long oldest = System.currentTimeMillis() - WEEK;
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                Resume r = new Resume(o.getString("srno"), o.getString("ch"), o.getString("title"),
                        o.optString("poster"), o.getLong("start"), o.getLong("end"), o.getLong("pos"));
                // Catch-up keeps a week; past that the programme is gone.
                if (r.start > oldest) out.add(r);
            }
        } catch (JSONException ignored) {
            // A damaged list is simply forgotten.
        }
        return out;
    }

    /** Moves this one to the front, or drops it when it was watched to the end. */
    static String save(List<Resume> list, Resume latest) {
        List<Resume> next = new ArrayList<>();
        boolean finished = latest.position > latest.duration() * 95 / 100;
        if (!finished) next.add(latest);
        for (Resume r : list) {
            if (!r.srno.equals(latest.srno) && next.size() < KEEP) next.add(r);
        }
        JSONArray array = new JSONArray();
        try {
            for (Resume r : next) {
                array.put(new JSONObject().put("srno", r.srno).put("ch", r.channelId).put("title", r.title)
                        .put("poster", r.poster).put("start", r.start).put("end", r.end).put("pos", r.position));
            }
        } catch (JSONException ignored) {
            // Never thrown for these plain values.
        }
        return array.toString();
    }
}
