package com.cloudbridge.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** The scheduled part of the photo backup: every few hours, and again after a restart, it uploads new photos. */
public class BackupJobService extends JobService {
    private static final int JOB_ID = 7001;
    private static final long PERIOD_MS = 3L * 60 * 60 * 1000;

    private volatile boolean cancelled;

    /** Makes the schedule match the settings: on when some drive has backup on, off otherwise. */
    static void update(Context c) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (!BackupSettings.anyEnabled(c)) {
            js.cancel(JOB_ID);
            return;
        }
        int network = BackupSettings.allWifiOnly(c) ? JobInfo.NETWORK_TYPE_UNMETERED : JobInfo.NETWORK_TYPE_ANY;
        JobInfo existing = js.getPendingJob(JOB_ID);
        if (existing != null && existing.getNetworkType() == network) return;   // keep its place in the cycle
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, BackupJobService.class))
                .setRequiredNetworkType(network)
                .setRequiresBatteryNotLow(true)
                .setPeriodic(PERIOD_MS)
                .setPersisted(true)
                .build());
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        cancelled = false;
        new Thread(() -> {
            try {
                BackupRunner.runAll(this, () -> cancelled, t -> { });
            } finally {
                jobFinished(params, false);
            }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        cancelled = true;
        return true;
    }
}
