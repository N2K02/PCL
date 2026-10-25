package com.pcl.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PclNavigationTest {
    @Test fun updatesBelongToSettingsInsteadOfBottomNavigation() {
        assertEquals(MainTab.SETTINGS, MainTab.UPDATE.primaryTab)
        assertEquals("About & updates", MainTab.UPDATE.pageTitle)
    }

    @Test fun permissionsAreASettingsSubpage() {
        assertEquals(MainTab.SETTINGS, MainTab.PERMISSIONS.primaryTab)
        assertEquals("Permissions", MainTab.PERMISSIONS.pageTitle)
    }

    @Test fun primaryPagesRemainIndependent() {
        assertEquals(MainTab.HOME, MainTab.HOME.primaryTab)
        assertEquals(MainTab.CALLS, MainTab.CALLS.primaryTab)
        assertEquals(MainTab.SETTINGS, MainTab.SETTINGS.primaryTab)
    }

    @Test fun initialSetupGoesToSettingsWithoutEnablingHome() {
        assertEquals(MainTab.SETTINGS, initialTab(setupReady = false))
        assertEquals(MainTab.HOME, initialTab(setupReady = true))
    }

    @Test fun everyPageHasATitleAndSettingsOwnsOnlyItsSubpages() {
        assertTrue(MainTab.entries.all { it.pageTitle.isNotBlank() })
        assertEquals(setOf(MainTab.PERMISSIONS, MainTab.UPDATE),
            MainTab.entries.filter { it != MainTab.SETTINGS && it.primaryTab == MainTab.SETTINGS }.toSet())
    }
}
