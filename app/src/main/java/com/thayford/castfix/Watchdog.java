package com.thayford.castfix;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import java.util.concurrent.TimeUnit;

/**
 * Checks every 15 minutes that the Cast receiver still signs (CastHealth) and runs the fix when it doesn't. The
 * receiver loses its credentials whenever its process restarts (a crash, the low-memory killer, an update), not
 * only at boot, and a run at boot can fail too; both used to leave casting broken until someone pressed the
 * button. A healthy check costs one local TLS request and never touches Wireless debugging.
 */
public class Watchdog extends JobService {
    private static final int PERIODIC_ID = 1;
    private static final int RETRY_ID = 2;
    private static final long PERIOD_MS = TimeUnit.MINUTES.toMillis(15);
    private static final long RETRY_DELAY_MS = TimeUnit.MINUTES.toMillis(2);
    /** Quick retries after a failed run: 2, 4 and 8 minutes. Then only the periodic check tries again. */
    static final int MAX_QUICK_RETRIES = 3;

    static long retryDelayMinutes(int failures) {
        return (RETRY_DELAY_MS << (failures - 1)) / TimeUnit.MINUTES.toMillis(1);
    }

    /** Schedules the periodic check unless it is already scheduled (rescheduling would restart its period). */
    static void schedule(Context context) {
        JobScheduler jobs = context.getSystemService(JobScheduler.class);
        if (jobs.getPendingJob(PERIODIC_ID) != null) return;
        jobs.schedule(new JobInfo.Builder(PERIODIC_ID, new ComponentName(context, Watchdog.class))
                .setPeriodic(PERIOD_MS)
                .setPersisted(true)
                .build());
    }

    /** One more check a few minutes from now, after the given number of failed runs in a row. */
    static void retrySoon(Context context, int failures) {
        long delay = RETRY_DELAY_MS << (failures - 1);
        context.getSystemService(JobScheduler.class).schedule(
                new JobInfo.Builder(RETRY_ID, new ComponentName(context, Watchdog.class))
                        .setMinimumLatency(delay)
                        .setOverrideDeadline(delay * 2)
                        .setPersisted(true)
                        .build());
    }

    static boolean isScheduled(Context context) {
        return context.getSystemService(JobScheduler.class).getPendingJob(PERIODIC_ID) != null;
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        String trigger = params.getJobId() == RETRY_ID ? "retry" : "watchdog";
        new Thread(() -> {
            try {
                FixLog log = new FixLog(this);
                CastHealth.State state = CastHealth.check(5_000);
                log.setLastCheck(state.name());
                if (state == CastHealth.State.SIGNED) log.resetFailures();
                // Only an empty signature means the receiver lost its credentials. No answer at all is normally the
                // idle receiver frozen by Android (a thaw kills it, and it comes back with the empty signature this
                // check then finds), and a fix cannot help a receiver that is not running.
                // A run that was cut short (process killed, TV switched off) left the wizard on: clean it up even
                // though the receiver signs now.
                if (state == CastHealth.State.UNSIGNED || CastFixer.runWasInterrupted(this)) {
                    CastFixService.runGuarded(this, trigger);
                }
            } finally {
                jobFinished(params, false);
            }
        }, "castfix-watchdog").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false; // the next periodic check comes anyway
    }
}
