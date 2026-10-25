package com.pcl.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keep the compact shared updater in the experimental build. */
class UpdaterVersionContractTest {
    private fun projectFile(path: String): File = listOf(
        File(path),
        File("../$path"),
    ).firstOrNull { it.isFile } ?: error("Project file not found: $path")

    @Test fun experimentalUsesCompactUpdater() {
        val gradle = projectFile("app/build.gradle.kts").readText()
        assertTrue("Use the compact updater", gradle.contains("dev.n2k02:updater:0.1.19"))
        assertFalse("The legacy updater must not return", gradle.contains("dev.n2k02:updater:0.1.18"))
    }

    @Test fun hostOwnsTheUpdatePageTitle() {
        val main = projectFile("app/src/main/java/com/pcl/app/MainActivity.kt").readText()
        assertTrue("Keep the embedded updater's host-scroll variant", main.contains("dev.n2k02.updater.N2K02UpdateScreen("))
        assertFalse("No duplicate updater title bar", main.contains("N2K02UpdateTitle("))
    }
}
