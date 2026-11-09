package io.github.rjtheorigin.joshitv;

import android.content.Context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The household's languages, best first: the languages of its favourite and recently watched channels, the ones
 * watched most first, then preferred_languages (res/values/joshi_config.xml) for a new install that has watched
 * nothing yet. Used for the guide's language list, the order of Home's shelves, and what the Home screen copy holds.
 */
final class Languages {
    private Languages() {
    }

    static List<String> preferred(Context context, Collection<Channel> all, Collection<String> favourites,
            Collection<String> recent) {
        Map<String, String> languageOf = new HashMap<>();
        for (Channel channel : all) languageOf.put(channel.id, channel.language);
        Map<String, Integer> score = new HashMap<>();
        for (String id : recent) add(score, languageOf.get(id), 2);
        for (String id : favourites) add(score, languageOf.get(id), 3);
        List<String> out = new ArrayList<>(score.keySet());
        out.sort((a, b) -> score.get(b) - score.get(a));
        for (String fallback : Arrays.asList(context.getResources().getStringArray(R.array.preferred_languages))) {
            if (!out.contains(fallback)) out.add(fallback);
        }
        return out;
    }

    private static void add(Map<String, Integer> score, String language, int points) {
        if (language == null || language.isEmpty()) return;
        score.merge(language, points, Integer::sum);
    }
}
