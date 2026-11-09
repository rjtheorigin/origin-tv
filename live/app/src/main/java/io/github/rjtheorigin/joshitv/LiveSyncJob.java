package io.github.rjtheorigin.joshitv;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Process;
import android.util.Log;

import java.io.IOException;

/** Runs the Live Channels copy in the background, at low priority, twice a day and after a favourite changes. */
public class LiveSyncJob extends JobService {
    private volatile boolean stopped;

    @Override
    public boolean onStartJob(JobParameters params) {
        stopped = false;
        new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            try {
                LiveSync.syncAll(this, () -> stopped);
            } catch (IOException | RuntimeException e) {
                Log.w("LiveSync", "copy failed: " + e.getMessage());
            }
            jobFinished(params, false);
        }, "live-sync").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        stopped = true;
        return true;
    }
}
