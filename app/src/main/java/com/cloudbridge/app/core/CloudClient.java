package com.cloudbridge.app.core;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** What the document provider needs from a cloud service. All paths are normalized ("/a/b"). */
public interface CloudClient {
    /** Direct children of a folder. */
    List<CloudEntry> list(String dirPath) throws IOException;

    /** Metadata for one item. Throws a {@link CloudException} with isNotFound() if it does not exist. */
    CloudEntry stat(String path) throws IOException;

    void download(String path, File dest) throws IOException;

    void upload(String path, File src, String mime) throws IOException;

    void mkdir(String path) throws IOException;

    void delete(String path, boolean isDir) throws IOException;

    /** Renames or moves a file or folder. Fails if the destination already exists (WebDAV) or overwrites it (S3). */
    void move(String from, String to, boolean isDir) throws IOException;

    /** Used, total and free space, or null when this kind of service does not say. */
    default CloudQuota quota() throws IOException {
        return null;
    }
}
