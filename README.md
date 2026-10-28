# CloudBridge

Connect cloud storage to your Android phone so it behaves like a folder on the device.

CloudBridge registers your cloud accounts as **storage locations in the system Files app**
(the same place "Downloads" and "SD card" live) and in the file picker of every other app.
Browse, open, copy, rename, move, delete, and save files straight to the cloud.

| Works with | Notes |
|---|---|
| **Google Drive** | Sign in with Google. Needs a free client ID from your own Google Cloud project (steps below). |
| **MEGA** | E-mail and password (and a two-factor code if you use one). Files are decrypted on the phone. |
| **TeraBox** (experimental) | Unofficial. You sign in on TeraBox's real website inside the app. May break whenever TeraBox changes its site. |
| **WebDAV** | Nextcloud, ownCloud, Synology / QNAP NAS, Box, pCloud, Koofr, Apache/nginx DAV, ... |
| **S3-compatible** | AWS S3, Cloudflare R2, Backblaze B2, Wasabi, MinIO, DigitalOcean Spaces, ... |

Not included: OneDrive and Dropbox already ship their own apps that appear in the Files app.

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

### Google Drive: one-tap sign-in (build the client ID into the app)

Google requires every app that touches Drive to be registered with a client ID; there is no way around that. You can,
however, register once and build the ID into the app, so that signing in is just the "Sign in with Google" button:
create the client as described below, then in your GitHub repository open *Settings > Secrets and variables > Actions*
and add a **variable** named `GOOGLE_CLIENT_ID` and a **secret** named `GOOGLE_CLIENT_SECRET`. The next build then
hides the two boxes in the app. (If you do not add them, the app asks for the two values instead.)

### Google Drive: getting the client ID (once, about 5 minutes)

Google only lets an app reach your Drive with a client ID, and this app has none of its own, so you make one:

1. Go to console.cloud.google.com and create a project.
2. *APIs & Services > Library*, search "Google Drive API", press **Enable**.
3. *OAuth consent screen* (called "Google Auth platform" in newer consoles): choose **External**, fill in an app
   name and your e-mail, and add yourself as a test user.
4. Press **Publish app** so the status is **In production**. If you leave it in "Testing", Google ends the sign-in
   every 7 days.
5. *Credentials > Create credentials > OAuth client ID*, application type **Desktop app**.
6. In CloudBridge choose *Google Drive*, paste the client ID and client secret (unless you built them in, see above),
   press **Sign in with Google**.
   Google says the app is "not verified": that is expected, because it is your own app. Tap *Advanced* and continue.

Drive notes: Google Docs, Sheets and Slides appear as .docx, .xlsx and .pptx (converted when opened, read-only; they
cannot be overwritten). Drive allows several files with the same name; the oldest keeps its name and the others show
as "name (2)", "name (3)". Deleting moves a file to the Drive trash. The sign-in uses the full Drive permission,
because the narrower one only shows files this app created.

### MEGA notes

CloudBridge talks to MEGA's own encrypted API (MEGA has no WebDAV or public file API). Your whole file list is read
once and kept for a minute, so the first open of a very large account is slow. Deleting moves to the MEGA Rubbish
Bin; replacing a file moves the old copy there. Duplicate names are numbered like on Drive. If MEGA ever answers that
it wants an "app key", get a free one at mega.io/developers and enter it in the optional box.

### TeraBox notes (experimental)

TeraBox has no public API and no WebDAV. CloudBridge therefore uses the same requests as the TeraBox website: you
sign in on the real site in a web view (CloudBridge never sees your password and keeps only the session cookie the
site sets, encrypted). Because these requests are not a supported interface, TeraBox can change them at any time and
the connection will stop working until the app is updated; automated access may also be against TeraBox's terms, so
use it at your own risk. Use e-mail sign-in on the TeraBox page, which opens as the desktop website (Google sign-in is normally refused inside an app).
If TeraBox sends a security check, open the TeraBox website in a browser once, solve it, then sign in again. Errors
include TeraBox's own error number to help diagnose problems. The default address is https://www.1024terabox.com;
change it in the form if your account uses another TeraBox domain.

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
  (listings are cached for 30 to 60 seconds).
- Passwords, keys and sign-ins are encrypted with the Android Keystore. Plain `http://` addresses are allowed
  (for a NAS on your home network) but send the password unencrypted, so prefer `https://`.
- The Android layer was written without access to the Android SDK. The protocol code (WebDAV, S3 and
  AWS Signature V4, Google OAuth and the Drive API, MEGA's protocol and encryption, TeraBox's website requests) is covered by automated tests,
  but Google, MEGA and TeraBox were only tested against imitations of their servers, so the first real sign-in is the
  true test (TeraBox is the least certain). If the build or the app misbehaves, please share the Actions log or the message shown on screen.

## Project layout

```
app/src/main/java/com/cloudbridge/app/
  CloudDocumentsProvider.java   the Files-app integration (DocumentsProvider)
  MainActivity.java             add / edit / remove accounts, Google sign-in
  TeraBoxLoginActivity.java     web view for signing in on the TeraBox website
  AccountStore.java, SecretBox.java, OkHttpTransport.java
  core/                         pure Java, no Android: WebDAV, S3 + SigV4, Google OAuth + Drive, MEGA, TeraBox, paths
tests/                          protocol tests with in-process mock WebDAV, S3, Google, MEGA and TeraBox servers
```

Run the tests on any computer with a JDK: `bash tests/run.sh`.
