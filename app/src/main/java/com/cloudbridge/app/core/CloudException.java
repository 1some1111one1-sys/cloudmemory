package com.cloudbridge.app.core;

import java.io.IOException;

/** A failed request to a cloud service. {@link #status} is the HTTP status (or 0 when not applicable). */
public class CloudException extends IOException {
    private static final long serialVersionUID = 1L;

    public final int status;

    public CloudException(int status, String message) {
        super(message);
        this.status = status;
    }

    public boolean isNotFound() {
        return status == 404 || status == 410;
    }
}
