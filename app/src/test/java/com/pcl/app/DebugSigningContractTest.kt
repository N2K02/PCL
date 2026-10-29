package com.pcl.app
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
class DebugSigningContractTest {
    @Test fun privateDistributionKeyIsOptionalForPrChecks() {
        val src = listOf(File("app/build.gradle.kts"), File("../app/build.gradle.kts"))
            .firstOrNull { it.isFile }?.readText() ?: error("Missing Gradle config")
        assertTrue(src.contains("if (distributionKeystore.isFile)"))
        assertTrue(src.contains("storeFile = distributionKeystore"))
        assertTrue(src.contains("signingConfig = signingConfigs.getByName(\"debug\")"))
    }
}
