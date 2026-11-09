package io.github.rjtheorigin.origintv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the phone page knows about the TV's apps. Which apps are on Home, and in what order, comes from the launcher's
 * own rules and saved order, so the phone matches the screen after the tiles are rearranged on the TV.
 */
final class PhoneRemoteApps {
    /**
     * Xiaomi's Miracast screen takes the TV off Wi-Fi until it is closed, which cuts off the phone. So it is never an
     * app tile: only the Casting page opens it, under a warning.
     */
    static final String MIRACAST = "com.xiaomi.mitv.smartshare";
    private static final String CHROMECAST = "com.google.android.apps.mediashell";
    private static final int ART_WIDTH = 320;
    private static final int ART_HEIGHT = 180;
    private static final int ICON_SIDE = 96;
    private static final int PAGE_ICON_SIDE = 180;
    private static final int ART_BACKGROUND = 0xFF1A2136;

    private PhoneRemoteApps() { }

    static final class App {
        final String pkg;
        final String name;
        final String component;
        final boolean home;

        App(String pkg, String name, String component, boolean home) {
            this.pkg = pkg;
            this.name = name;
            this.component = component;
            this.home = home;
        }
    }

    /** Home's tiles first, in Home's order (the saved order, then the dock, then by name), then the rest by name. */
    static void sort(List<App> apps, Map<String, Integer> saved, List<String> dock) {
        Collections.sort(apps, (a, b) -> {
            if (a.home != b.home) return a.home ? -1 : 1;
            if (a.home) {
                int order = Order.compare(saved, a.component, b.component);
                if (order != 0) return order;
                int da = dock.indexOf(a.pkg), db = dock.indexOf(b.pkg);
                if (da >= 0 || db >= 0) {
                    if (da < 0) return 1;
                    if (db < 0) return -1;
                    return Integer.compare(da, db);
                }
            }
            return a.name.compareToIgnoreCase(b.name);
        });
    }

    /** Every app the TV can open, whether or not Home shows it. */
    static JSONArray list(Context context) throws JSONException {
        PackageManager pm = context.getPackageManager();
        List<ResolveInfo> found = new ArrayList<>(pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0));
        Set<String> tvApps = new HashSet<>();
        for (ResolveInfo info : found) if (info.activityInfo != null) tvApps.add(info.activityInfo.packageName);
        found.addAll(pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0));

        Config config = Config.load(context);
        Set<String> hidden = new HashSet<>(config.hiddenApps);
        Set<String> added = new HashSet<>(LibraryApps.load(context));
        added.addAll(config.libraryApps);
        Set<String> removed = LibraryApps.loadRemoved(context);

        List<App> apps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo info : found) {
            if (info.activityInfo == null || !info.activityInfo.exported) continue;
            String pkg = info.activityInfo.packageName;
            if (pkg.equals(context.getPackageName()) || MIRACAST.equals(pkg) || !seen.add(pkg)) continue;
            boolean home = LibraryApps.visible(tvApps.contains(pkg), hidden.contains(pkg),
                    added.contains(pkg), removed.contains(pkg));
            apps.add(new App(pkg, info.loadLabel(pm).toString(),
                    new ComponentName(pkg, info.activityInfo.name).flattenToShortString(), home));
        }
        sort(apps, Order.ranks(Order.load(Order.file(context)), config.dockApps), config.dockApps);

        JSONArray rows = new JSONArray();
        for (App app : apps) {
            rows.put(new JSONObject().put("package", app.pkg).put("name", app.name).put("home", app.home));
        }
        return rows;
    }

    static Intent launchIntent(PackageManager pm, String pkg) {
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        return intent != null ? intent : pm.getLaunchIntentForPackage(pkg);
    }

    /** The app's banner, or its icon on a banner-shaped tile, or null when the app cannot be opened. */
    static byte[] art(Context context, String pkg) {
        PackageManager pm = context.getPackageManager();
        Intent launch = launchIntent(pm, pkg);
        if (launch == null) return null;
        try {
            Drawable banner = pm.getActivityBanner(launch);
            if (banner == null) banner = pm.getApplicationBanner(pkg);
            Bitmap bitmap = Bitmap.createBitmap(ART_WIDTH, ART_HEIGHT, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(ART_BACKGROUND);
            if (banner != null) {
                banner.setBounds(0, 0, ART_WIDTH, ART_HEIGHT);
                banner.draw(canvas);
            } else {
                int left = (ART_WIDTH - ICON_SIDE) / 2, top = (ART_HEIGHT - ICON_SIDE) / 2;
                Drawable icon = pm.getActivityIcon(launch);
                icon.setBounds(left, top, left + ICON_SIDE, top + ICON_SIDE);
                icon.draw(canvas);
            }
            return compress(bitmap, Bitmap.CompressFormat.WEBP);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** The launcher's own icon, for a phone that adds the page to its home screen. */
    static byte[] pageIcon(Context context) {
        Bitmap bitmap = Bitmap.createBitmap(PAGE_ICON_SIDE, PAGE_ICON_SIDE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(ART_BACKGROUND);
        PackageManager pm = context.getPackageManager();
        Drawable icon;
        try {
            icon = pm.getApplicationIcon(PhoneRemoteLive.APP);
        } catch (PackageManager.NameNotFoundException e) {
            icon = context.getApplicationInfo().loadIcon(pm);
        }
        icon.setBounds(0, 0, PAGE_ICON_SIDE, PAGE_ICON_SIDE);
        icon.draw(canvas);
        return compress(bitmap, Bitmap.CompressFormat.PNG);
    }

    private static byte[] compress(Bitmap bitmap, Bitmap.CompressFormat format) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(format, 82, out);
        bitmap.recycle();
        return out.toByteArray();
    }

    /** What the Casting page shows. */
    static JSONObject cast(Context context) throws JSONException {
        PackageManager pm = context.getPackageManager();
        String name = Settings.Global.getString(context.getContentResolver(), "device_name");
        return new JSONObject()
                .put("name", name == null || name.isEmpty() ? Build.MODEL : name)
                .put("chromecast", enabled(pm, CHROMECAST))
                .put("miracast", launchIntent(pm, MIRACAST) != null);
    }

    private static boolean enabled(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationInfo(pkg, 0).enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
