package com.pcl.app
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
class CallLogsMergeContractTest {
    private fun read(path: String) = listOf(File(path), File("../$path"))
        .firstOrNull { it.isFile }?.readText() ?: error(path)
    @Test fun activityAndCrashHistoryShareOneLogsPage() {
        val main = read("app/src/main/java/com/pcl/app/MainActivity.kt")
        val logs = read("app/src/main/java/com/pcl/app/LogsActivity.kt")
        assertFalse(main.contains("ActivityLogCard()"))
        assertFalse(main.contains("private fun ActivityLogCard()"))
        assertTrue(logs.contains("CallLogger.lines.collectAsState()"))
        assertTrue(logs.contains("extraLines = callActivity"))
        assertTrue(logs.contains("clearExtra = CallLogger::clear"))
        assertTrue(logs.contains("N2K02UnifiedLogsPage("))
    }
}
