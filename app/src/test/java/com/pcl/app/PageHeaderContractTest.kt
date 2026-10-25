package com.pcl.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lightweight source-level regression guard for the mRadio header contract.
 * Visual layout and playback behavior still require Android UI validation.
 */
class PageHeaderContractTest {
    private fun source(): String {
        val relative = "com/pcl/app/MainActivity.kt"
        return listOf(File("src/main/java", relative), File("app/src/main/java", relative))
            .firstOrNull { it.isFile }?.readText()
            ?: error("Missing app UI source: $relative")
    }

    @Test fun pageHeaderHasLeadingIconAndRightSideAppName() {
        val text = source()
        assertTrue(text.contains("topBar = {"))
        val header = text.substringAfter("topBar = {")
            .substringBefore("bottomBar = {")
        assertTrue("Missing page icon in header", header.contains("Icons.Default.Hub"))
        assertTrue("Missing application name in header", header.contains("Text(\"PCL\""))
    }

    @Test fun quitLivesBesideSettingsInBottomNavigation() {
        val screen = source()
        val header = screen.substringAfter("topBar = {").substringBefore("bottomBar = {")
        val bottomBar = screen.substringAfter("bottomBar = {").substringBefore(") { insets ->")

        assertFalse("Quit must not be in the top header", header.contains("quitApp()"))
        assertTrue("Settings must remain in the bottom bar", bottomBar.contains("label = { Text(\"Settings\") }"))
        assertTrue("Quit must be in the bottom bar", bottomBar.contains("label = { Text(\"Quit\") }"))
        assertTrue("Quit must use the existing app shutdown action", bottomBar.contains("onClick = { quitApp() }"))
        assertTrue("Quit must be after Settings", bottomBar.indexOf("label = { Text(\"Quit\") }") >
            bottomBar.indexOf("label = { Text(\"Settings\") }"))
    }

    @Test fun appTitleIsNotProvidedBySharedUpdater() {
        val header = source().substringAfter("topBar = {")
            .substringBefore("bottomBar = {")
        assertFalse("App header should not duplicate updater title", header.contains("N2K02UpdateTitle("))
    }
}
