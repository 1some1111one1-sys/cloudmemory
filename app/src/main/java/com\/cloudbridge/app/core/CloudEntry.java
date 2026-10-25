package com.cloudbridge.app.core;

/** One file or folder in a cloud account. */
public final class CloudEntry {
    public final String path;
    public final boolean dir;
    /** Size in bytes, or -1 when unknown (always -1 for folders). */
    public final long size;
    /** Last modified in epoch millis, or 0 when unknown. */
    public final long modified;

    public CloudEntry(String path, boolean dir, long size, long modified) {
        this.path = Paths.normalize(path);
        this.dir = dir;
        this.size = size;
        this.modified = modified;
    }

    public String name() {
        return Paths.name(path);
    }

    public String mime() {
        return dir ? Paths.MIME_DIR : Paths.mimeFor(name());
    }
}
