package io.github.rjtheorigin.origintv;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pictures for the PatchWall-style Home: posters from the web and channel logos from the TV provider, decoded at
 * the size they are shown and kept in memory only, under a fixed cap. Nothing is written to the TV's storage.
 */
final class PatchImages {
    private final int widthPx;
    private final boolean opaque;
    private final LruCache<String, Bitmap> memory;
    private final ExecutorService pool;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ContentResolver resolver;

    PatchImages(ContentResolver resolver, int widthPx, int memoryMb, boolean opaque, int threads) {
        this.resolver = resolver;
        this.widthPx = widthPx;
        this.opaque = opaque;
        this.pool = Executors.newFixedThreadPool(threads);
        memory = new LruCache<String, Bitmap>(memoryMb * 1024 * 1024) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };
    }

    /** Shows the picture at that address in the view, now when it is in memory, or once it has loaded. */
    void load(String uri, ImageView view) {
        load(uri, view, false);
    }

    /** hideWhenEmpty: the view stays invisible until its picture is there (a logo plate must not show empty). */
    void load(String uri, ImageView view, boolean hideWhenEmpty) {
        String key = uri == null ? "" : uri;
        view.setTag(R.id.patch_image_tag, key);
        Bitmap ready = key.isEmpty() ? null : memory.get(key);
        view.setImageBitmap(ready);
        if (hideWhenEmpty) view.setVisibility(ready != null ? android.view.View.VISIBLE : android.view.View.INVISIBLE);
        if (ready != null || key.isEmpty()) return;
        pool.execute(() -> {
            // The view was rebound to another card while this waited: skip the download.
            if (!key.equals(view.getTag(R.id.patch_image_tag))) return;
            Bitmap bitmap = read(key);
            if (bitmap == null) return;
            memory.put(key, bitmap);
            main.post(() -> {
                if (!key.equals(view.getTag(R.id.patch_image_tag))) return;
                view.setImageBitmap(bitmap);
                if (hideWhenEmpty) view.setVisibility(android.view.View.VISIBLE);
            });
        });
    }

    void clear() {
        memory.evictAll();
    }

    void shutdown() {
        pool.shutdownNow();
        memory.evictAll();
    }

    private Bitmap read(String uri) {
        byte[] data;
        try {
            data = uri.startsWith("http") ? download(uri) : local(uri);
        } catch (IOException | RuntimeException e) {
            return null;
        }
        if (data == null || data.length == 0) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        if (opaque) options.inPreferredConfig = Bitmap.Config.RGB_565;
        while (bounds.outWidth / (options.inSampleSize * 2) >= widthPx) options.inSampleSize *= 2;
        try {
            return BitmapFactory.decodeByteArray(data, 0, data.length, options);
        } catch (OutOfMemoryError e) {
            memory.evictAll();
            return null;
        }
    }

    private byte[] local(String uri) throws IOException {
        try (InputStream in = resolver.openInputStream(Uri.parse(uri))) {
            return in == null ? null : readAll(in);
        }
    }

    private static byte[] download(String address) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(10000);
        try (InputStream in = connection.getInputStream()) {
            return readAll(in);
        } finally {
            connection.disconnect();
        }
    }

    /** At most 4 MB: a poster is never that big, and a wrong address must not fill the memory. */
    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] chunk = new byte[16 * 1024];
        int n;
        while ((n = in.read(chunk)) > 0 && bytes.size() < 4 * 1024 * 1024) bytes.write(chunk, 0, n);
        return bytes.toByteArray();
    }
}
