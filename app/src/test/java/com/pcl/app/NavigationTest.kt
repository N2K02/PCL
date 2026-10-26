package com.pcl.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Behaviour of the app's navigation rules (the UI calls these for every tab change). */
class NavigationTest {
    private val permissions = Navigation.Target(MainTab.SETTINGS, permissions = true)

    @Test fun startsOnHomeOnlyWhenSetupIsComplete() {
        assertEquals(Navigation.Target(MainTab.HOME), Navigation.start(setupReady = true))
        assertEquals(permissions, Navigation.start(setupReady = false))
    }

    @Test fun homeAndCallsCannotBypassSetup() {
        for (remote in listOf(true, false)) {
            assertEquals(permissions, Navigation.open(MainTab.HOME, remote, setupReady = false))
            assertEquals(permissions, Navigation.open(MainTab.CALLS, remote, setupReady = false))
        }
    }

    @Test fun settingsPagesAlwaysOpen() {
        for (tab in listOf(MainTab.SETTINGS, MainTab.DIAGNOSTICS, MainTab.UPDATE))
            assertEquals(Navigation.Target(tab), Navigation.open(tab, remote = true, setupReady = false))
    }

    @Test fun callsIsARemoteBottomTab() {
        assertEquals(listOf(MainTab.HOME, MainTab.CALLS, MainTab.SETTINGS), Navigation.bottomTabs(remote = true))
        assertEquals(listOf(MainTab.HOME, MainTab.SETTINGS), Navigation.bottomTabs(remote = false))
        assertEquals(Navigation.Target(MainTab.CALLS), Navigation.open(MainTab.CALLS, remote = true, setupReady = true))
        assertEquals(Navigation.Target(MainTab.HOME), Navigation.open(MainTab.CALLS, remote = false, setupReady = true))
    }

    @Test fun highlightedItemMatchesTheScreen() {
        assertEquals(MainTab.HOME, Navigation.highlighted(MainTab.HOME))
        assertEquals(MainTab.CALLS, Navigation.highlighted(MainTab.CALLS))
        for (tab in listOf(MainTab.SETTINGS, MainTab.DIAGNOSTICS, MainTab.UPDATE))
            assertEquals(MainTab.SETTINGS, Navigation.highlighted(tab))
    }

    @Test fun backGoesUpOneLevel() {
        assertEquals(Navigation.Target(MainTab.SETTINGS), Navigation.back(MainTab.UPDATE, false))
        assertEquals(Navigation.Target(MainTab.SETTINGS), Navigation.back(MainTab.DIAGNOSTICS, false))
        assertEquals(Navigation.Target(MainTab.SETTINGS), Navigation.back(MainTab.SETTINGS, true))
        assertEquals(Navigation.Target(MainTab.HOME), Navigation.back(MainTab.CALLS, false))
        assertNull(Navigation.back(MainTab.HOME, false))
        assertNull(Navigation.back(MainTab.SETTINGS, false))
    }

    @Test fun callsReturnsWhereItWasOpenedFrom() {
        assertEquals(Navigation.Target(MainTab.SETTINGS), Navigation.back(MainTab.CALLS, false, previous = MainTab.SETTINGS))
        assertEquals(Navigation.Target(MainTab.HOME), Navigation.back(MainTab.CALLS, false, previous = MainTab.HOME))
        // No loop back into Calls, and Home when nothing is known.
        assertEquals(Navigation.Target(MainTab.HOME), Navigation.back(MainTab.CALLS, false, previous = MainTab.CALLS))
        assertEquals(Navigation.Target(MainTab.HOME), Navigation.back(MainTab.CALLS, false, previous = null))
    }

    @Test fun backNeverLeadsToALockedScreen() {
        // Opening the Back target again through open() keeps setup enforced.
        val target = Navigation.back(MainTab.CALLS, false, previous = MainTab.HOME)!!
        assertEquals(Navigation.Target(MainTab.SETTINGS, permissions = true),
            Navigation.open(target.tab, remote = true, setupReady = false))
    }
}
