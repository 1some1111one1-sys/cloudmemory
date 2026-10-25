# CloudBridge

Connect cloud storage to your Android phone so it behaves like a folder on the device.

CloudBridge registers your cloud accounts as **storage locations in the system Files app**
(the same place "Downloads" and "SD card" live) and in the file picker of every other app.
Browse, open, copy, rename, move, delete, and save files straight to the cloud.

| Works with | Notes |
|---|---|
| **WebDAV** | Nextcloud, ownCloud, Synology / QNAP NAS, Box, pCloud, Koofr, Apache/nginx DAV, ... |
| **S3-compatible** | AWS S3, Cloudflare R2, Backblaze B2, Wasabi, MinIO, DigitalOcean Spaces, ... |

Not included: Google Drive, OneDrive and Dropbox already ship their own apps that appear in the
Files app. TeraBox has no public API, so it cannot be connected.

## Get the APK (no Android Studio needed)

1. Create a free GitHub account and a new repository (private is fine).
2. Upload this project's files to it (or, if you were given the single `build-apk.yml` file, create
   `.github/workflows/build-apk.yml` in the repository and paste it in: it contains the whole project).
3. Open the **Actions** tab. The *Build APK* workflow starts by itself (or press *Run workflow*).
   It takes about 5 minutes.
4. When it turns green, open the repository's **Releases** page on your phone and tap
   `CloudBridge.apk` to download it. (The same file is also attached to the workflow run under *Artifacts*.)
5. Open the downloaded file and allow "install unknown apps" when Android asks.

Every build is signed with a fresh key, so to install a newer build you must uninstall the old one
first, and then add your accounts again.

## Use it

1. Open **CloudBridge** and tap *Add cloud storage*.
2. WebDAV: server URL (for Nextcloud `https://host/remote.php/dav/files/USERNAME`), username, password.
   S3: endpoint (for example `https://s3.amazonaws.com`), region, bucket, access key, secret key.
   For Cloudflare R2 use region `auto`; for Backblaze B2 use the region shown for your bucket.
3. *Test & save*. The storage now shows up in the **Files** app (menu, then its name) and in every
   app's "choose file" screen.

## How it works and its limits

- A file is **downloaded to the phone when it is opened** and **uploaded when the app that wrote it closes
  it**. Very large files therefore take a while to open the first time. Downloads are cached (512 MB, least
  recently used are dropped).
- S3 uploads are a single request, so files over 5 GB are rejected. S3 folders are key prefixes, so
  renaming or deleting a big folder copies or deletes object by object.
- If Android kills CloudBridge while it is still uploading a very large file (after the app that saved it
  has already closed it), that upload is lost. Wait for the upload to finish before leaving the phone idle,
  and check the file in the cloud when it matters.
- There is no background sync. Changes made by other devices appear when a folder is reopened
  (listings are cached for 60 seconds).
- Passwords and keys are encrypted with the Android Keystore. Plain `http://` addresses are allowed
  (for a NAS on your home network) but send the password unencrypted, so prefer `https://`.
- The Android layer was written without access to the Android SDK. The protocol code (WebDAV, S3 and
  AWS Signature V4) is covered by automated tests, but the first real-device run is the true test.
  If the build or the app misbehaves, please share the Actions log or the message shown in the Files app.

## Project layout

```
app/src/main/java/com/cloudbridge/app/
  CloudDocumentsProvider.java   the Files-app integration (DocumentsProvider)
  MainActivity.java             add / edit / remove accounts
  AccountStore.java, SecretBox.java, OkHttpTransport.java
  core/                         pure Java, no Android: WebDAV client, S3 client + SigV4 signer, paths
tests/                          protocol tests with in-process mock WebDAV and S3 servers
```

Run the tests on any computer with a JDK: `bash tests/run.sh`.
