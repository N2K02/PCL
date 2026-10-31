package com.pcl.app
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
class NoAppLogsContractTest {
    private fun src(p: String) = listOf(File(p), File("../$p")).first { it.isFile }.readText()
    @Test fun navigationHasNoLogsViewer() {
        assertFalse(src("app/src/main/java/com/pcl/app/MainActivity.kt").contains("MainTab.LOGS"))
        assertTrue(src("app/src/main/java/com/pcl/app/MainActivity.kt").contains("MainTab.CALLS"))
    }
    @Test fun noInAppLogBufferOrJournal() {
        assertTrue(src("app/src/main/java/com/pcl/app/CallLogger.kt").contains("Log.d("))
        assertFalse(src("app/src/main/java/com/pcl/app/CallLogger.kt").contains("MutableStateFlow"))
        assertFalse(src("app/src/main/AndroidManifest.xml").contains("CrashJournalProvider"))
    }
}
