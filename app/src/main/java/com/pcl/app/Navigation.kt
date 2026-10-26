package com.pcl.app

/** The app's screens. Home, Calls and Settings are in the bottom bar; the rest open from Settings. */
internal enum class MainTab { HOME, CALLS, SETTINGS, DIAGNOSTICS, UPDATE }

/**
 * Where navigation goes, kept apart from the UI so it can be unit tested.
 * Same layout as mRadio: Home · (Calls) · Settings · Quit in the bottom bar; Settings lists
 * its pages, then a divider, then Connection diagnostics and About & updates.
 */
internal object Navigation {
    /** Where a tap leads: a screen, and whether the permission checklist is shown on it. */
    data class Target(val tab: MainTab, val permissions: Boolean = false)

    /** The first screen: Home once setup is complete, else the permission checklist. */
    fun start(setupReady: Boolean): Target =
        if (setupReady) Target(MainTab.HOME) else Target(MainTab.SETTINGS, permissions = true)

    /** Bottom-bar tabs for the role, in order (Quit is an action, not a tab). */
    fun bottomTabs(remote: Boolean): List<MainTab> =
        if (remote) listOf(MainTab.HOME, MainTab.CALLS, MainTab.SETTINGS) else listOf(MainTab.HOME, MainTab.SETTINGS)

    /** The bottom-bar item highlighted while [screen] is shown: pages opened from Settings keep Settings lit. */
    fun highlighted(screen: MainTab): MainTab = when (screen) {
        MainTab.HOME -> MainTab.HOME
        MainTab.CALLS -> MainTab.CALLS
        MainTab.SETTINGS, MainTab.DIAGNOSTICS, MainTab.UPDATE -> MainTab.SETTINGS
    }

    /**
     * Opening [tab]. Settings and its pages always open. Home and Calls need setup to be
     * complete; until then they lead to the permission checklist. Calls is the Remote's only.
     */
    fun open(tab: MainTab, remote: Boolean, setupReady: Boolean): Target = when {
        tab == MainTab.SETTINGS || tab == MainTab.DIAGNOSTICS || tab == MainTab.UPDATE -> Target(tab)
        !setupReady -> Target(MainTab.SETTINGS, permissions = true)
        tab == MainTab.CALLS && !remote -> Target(MainTab.HOME)
        else -> Target(tab)
    }

    /** Where Back goes from [screen], or null to leave the app. */
    fun back(screen: MainTab, permissions: Boolean): Target? = when {
        screen == MainTab.SETTINGS && permissions -> Target(MainTab.SETTINGS)
        screen == MainTab.DIAGNOSTICS || screen == MainTab.UPDATE -> Target(MainTab.SETTINGS)
        screen == MainTab.CALLS -> Target(MainTab.HOME)
        else -> null
    }
}
