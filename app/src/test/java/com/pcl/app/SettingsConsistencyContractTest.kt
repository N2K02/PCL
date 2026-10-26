package com.pcl.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guard the mRadio-aligned Settings layout. Home and app functionality stay independent. */
class SettingsConsistencyContractTest {
    @Test fun settingsUsesConsistentRowsAndUpdatePlacement() {
        val relative = "app/src/main/java/com/pcl/app/MainActivity.kt"
        val source = listOf(File(relative), File("../$relative"))
            .firstOrNull { it.isFile }?.readText() ?: error("Missing $relative")
        val menu = source.substringAfter("if (screen == MainTab.SETTINGS && !showingPermissions) {", "")
        assertTrue("Settings menu section must exist", menu.isNotBlank())
        assertTrue("Settings must have mRadio-style section heading", menu.contains("Manage your connection"))
        assertTrue("Settings should have aligned ListItems", menu.contains("headlineContent = { Text(\"Permissions\") }"))
        assertTrue("Updates must remain available", menu.contains("headlineContent = { Text(\"About & updates\") }"))
        // Same grouping as mRadio: divider, then Connection diagnostics, then About & updates.
        val divider = menu.indexOf("HorizontalDivider()")
        val diagnostics = menu.indexOf("headlineContent = { Text(\"Connection diagnostics\") }")
        val updates = menu.indexOf("headlineContent = { Text(\"About & updates\") }")
        assertTrue("Divider must precede Connection diagnostics", divider >= 0 && diagnostics > divider)
        assertTrue("About & updates must come last, after Connection diagnostics", updates > diagnostics)
        assertTrue("Calls is a bottom-bar tab, not a Settings row", !menu.substringBefore("if (screen == MainTab.SETTINGS && showingPermissions)").contains("Text(\"Calls\")"))
    }
}
