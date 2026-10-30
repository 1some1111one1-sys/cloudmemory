package com.cloudbridge.app.core;

/** How much space a cloud has. Any value the service does not report is -1. */
public final class CloudQuota {
    public final long used;
    public final long total;
    public final long free;

    public CloudQuota(long used, long total, long free) {
        this.used = used;
        this.total = total;
        this.free = free;
    }

    /** From used and total (free is worked out). */
    public static CloudQuota ofUsedAndTotal(long used, long total) {
        long free = used >= 0 && total >= 0 ? Math.max(0, total - used) : -1;
        return new CloudQuota(used, total, free);
    }

    /** True when at least the free space is known. */
    public boolean known() {
        return free >= 0 || (used >= 0 && total >= 0);
    }
}
