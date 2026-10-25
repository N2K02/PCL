package com.pcl.app

/**
 * Settings owns both secondary pages. Keep only Home, Calls (Remote), and Settings in
 * the bottom navigation, and let the host activity own the single page title.
 */
internal enum class MainTab(val pageTitle: String) {
    HOME("Home"),
    CALLS("Calls"),
    SETTINGS("Settings"),
    PERMISSIONS("Permissions"),
    UPDATE("About & updates");

    val primaryTab: MainTab
        get() = when (this) {
            PERMISSIONS, UPDATE -> SETTINGS
            else -> this
        }
}

/** Initial setup is always accessible, even before any required permissions are granted. */
internal fun initialTab(setupReady: Boolean): MainTab =
    if (setupReady) MainTab.HOME else MainTab.SETTINGS
