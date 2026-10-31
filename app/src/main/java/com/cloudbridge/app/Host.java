package com.cloudbridge.app;

import android.app.Activity;
import com.cloudbridge.app.core.Account;

/** What the pages can ask the main screen to do. */
interface Host {
    Activity activity();

    void openDrawer();

    void goHome();

    void goDrives();

    /** Opens the Files page, inside the given drive (or on the phone when null). */
    void goFiles(Account driveOrNull);

    /** Opens a drive in the phone's own Files app. */
    void openInSystemFiles(Account a);

    void addDrive();

    void editDrive(Account a);

    void removeDrive(Account a);

    /** Asks for access to photos (and notifications), then calls {@code then} on the main thread. */
    void askMediaAccess(Runnable then);

    /** The accounts, the schedule or the backup state changed. */
    void changed();
}
