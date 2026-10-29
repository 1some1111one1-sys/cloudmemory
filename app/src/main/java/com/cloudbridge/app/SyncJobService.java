package com.cloudbridge.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** Syncs the clouds that have the sync folder on every few hours, on Wi-Fi, without the user doing anything. */
public class SyncJobService extends JobService {
    private static final int JOB_ID = 4242;
    private static final long PERIOD_MS = 6L * 60 * 60 * 1000;

    /** Turns the periodic sync on or off. */
    public static void schedule(Context c, boolean on) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;
        if (!on) {
            js.cancel(JOB_ID);
            return;
        }
        JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(c, SyncJobService.class))
                .setPeriodic(PERIOD_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setPersisted(true)
                .build();
        js.schedule(job);
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        if (!SyncRunner.begin()) return false;
        new Thread(() -> {
            try {
                SyncRunner.runAll(SyncJobService.this, null);
            } finally {
                SyncRunner.end();
                jobFinished(params, false);
            }
        }, "cloudbridge-sync-job").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        SyncRunner.cancel();
        return true;   // try again at the next opportunity
    }
}
