package io.github.rjtheorigin.joshitv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads pictures at the size they are shown, with a small memory cache and a folder of files for the next launch. */
final class Logos {
    private final int sizePx;
    private final boolean opaque;
    /** Null for pictures kept in memory only, which are gone when Joshi TV closes. */
    private final LruCache<String, Bitmap> memory;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final File folder;

    /** Channel logos, which have transparent edges. */
    Logos(File cacheDir) {
        this(cacheDir, "logos", 120, 6, false);
    }

    /** Pictures in their own folder; opaque ones use half the memory. */
    Logos(File cacheDir, String name, int sizePx, int memoryMb, boolean opaque) {
        this.sizePx = sizePx;
        this.opaque = opaque;
        memory = new LruCache<String, Bitmap>(memoryMb * 1024 * 1024) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };
        if (cacheDir == null) {
            folder = null;
        } else {
            folder = new File(cacheDir, name);
            folder.mkdirs();
        }
    }

    /** Posters and banners: memory only, never written to the TV's storage. */
    static Logos inMemory(int sizePx, int memoryMb) {
        return new Logos(null, null, sizePx, memoryMb, true);
    }

    void load(String url, ImageView view) {
        view.setTag(url);
        Bitmap ready = url.isEmpty() ? null : memory.get(url);
        view.setImageBitmap(ready);
        if (ready != null || url.isEmpty()) return;
        pool.execute(() -> {
            if (!url.equals(view.getTag())) return;
            Bitmap bitmap = read(url);
            if (bitmap == null) return;
            memory.put(url, bitmap);
            main.post(() -> {
                if (url.equals(view.getTag())) view.setImageBitmap(bitmap);
            });
        });
    }

    void shutdown() {
        pool.shutdownNow();
    }

    private Bitmap read(String url) {
        if (folder == null) return readInMemory(url);
        File file = new File(folder, url.substring(url.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]", "_"));
        try {
            if (!file.exists()) download(url, file);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), bounds);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            if (opaque) options.inPreferredConfig = Bitmap.Config.RGB_565;
            while (bounds.outWidth / (options.inSampleSize * 2) >= sizePx) options.inSampleSize *= 2;
            return BitmapFactory.decodeFile(file.getPath(), options);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private Bitmap readInMemory(String url) {
        HttpURLConnection connection = null;
        try {
            connection = Api.open(url, 10000);
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] chunk = new byte[16 * 1024];
                int n;
                while ((n = in.read(chunk)) > 0 && bytes.size() < 4 * 1024 * 1024) bytes.write(chunk, 0, n);
            }
            byte[] data = bytes.toByteArray();
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            if (opaque) options.inPreferredConfig = Bitmap.Config.RGB_565;
            while (bounds.outWidth / (options.inSampleSize * 2) >= sizePx) options.inSampleSize *= 2;
            return BitmapFactory.decodeByteArray(data, 0, data.length, options);
        } catch (IOException | RuntimeException e) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** Keeps a folder under the limit, oldest downloads first, and returns its size after. */
    static long trim(File cacheDir, String name, long maxBytes) {
        File[] files = new File(cacheDir, name).listFiles();
        if (files == null) return 0;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        long total = 0;
        for (File file : files) total += file.length();
        for (File file : files) {
            if (total <= maxBytes) break;
            long size = file.length();
            if (file.delete()) total -= size;
        }
        return total;
    }

    /** Removes a picture folder an earlier version kept. */
    static void forget(File cacheDir, String name) {
        File[] files = new File(cacheDir, name).listFiles();
        if (files != null) for (File file : files) file.delete();
        new File(cacheDir, name).delete();
    }

    private static void download(String url, File file) throws IOException {
        HttpURLConnection connection = Api.open(url, 10000);
        File partial = new File(file.getPath() + ".part");
        try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(partial)) {
            byte[] chunk = new byte[16 * 1024];
            int n;
            while ((n = in.read(chunk)) > 0) out.write(chunk, 0, n);
        } finally {
            connection.disconnect();
        }
        if (!partial.renameTo(file)) throw new IOException("rename");
    }
}
