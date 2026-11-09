package io.github.rjtheorigin.origintv;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class PhoneRemoteAppsTest {
    private static PhoneRemoteApps.App app(String pkg, String name, boolean home) {
        return new PhoneRemoteApps.App(pkg, name, pkg + "/.Main", home);
    }

    private static List<String> order(List<PhoneRemoteApps.App> apps) {
        List<String> names = new ArrayList<>();
        for (PhoneRemoteApps.App a : apps) names.add(a.name);
        return names;
    }

    @Test public void homeFollowsTheDockThenNamesAndHiddenAppsComeLast() {
        List<PhoneRemoteApps.App> apps = new ArrayList<>(Arrays.asList(
                app("hidden.a", "Aardvark", false), app("grid.b", "VLC", true), app("grid.a", "Gallery", true),
                app("dock.two", "YouTube", true), app("dock.one", "Prime", true)));
        PhoneRemoteApps.sort(apps, Collections.emptyMap(), Arrays.asList("dock.one", "dock.two"));
        assertEquals(Arrays.asList("Prime", "YouTube", "Gallery", "VLC", "Aardvark"), order(apps));
    }

    @Test public void theSavedOrderBeatsTheDock() {
        List<PhoneRemoteApps.App> apps = new ArrayList<>(Arrays.asList(
                app("dock.one", "Prime", true), app("dock.two", "YouTube", true), app("grid.a", "VLC", true)));
        Map<String, Integer> saved = new HashMap<>();
        saved.put("grid.a/.Main", 0);
        saved.put("dock.two/.Main", 1);
        PhoneRemoteApps.sort(apps, saved, Arrays.asList("dock.one", "dock.two"));
        assertEquals(Arrays.asList("VLC", "YouTube", "Prime"), order(apps));
    }

    @Test public void aHiddenAppNeverJumpsAheadOfHomeEvenIfSaved() {
        List<PhoneRemoteApps.App> apps = new ArrayList<>(Arrays.asList(
                app("hidden.a", "Live TV", false), app("grid.a", "VLC", true)));
        Map<String, Integer> saved = new HashMap<>();
        saved.put("hidden.a/.Main", 0);
        PhoneRemoteApps.sort(apps, saved, Collections.emptyList());
        assertEquals(Arrays.asList("VLC", "Live TV"), order(apps));
    }
}
