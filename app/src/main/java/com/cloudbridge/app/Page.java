package com.cloudbridge.app;

import android.view.View;

/** One screen of the app, shown in the area next to the sidebar. */
abstract class Page {
    abstract View view();

    /** The page became visible (or the app came back to it). */
    void onShow() {
    }

    /** Handle the Back button; true if it was used up. */
    boolean onBack() {
        return false;
    }
}
