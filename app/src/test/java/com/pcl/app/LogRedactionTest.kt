package com.pcl.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRedactionTest {
    @Test fun macAddressesAreRedactedBeforeSharingLogs() {
        val message = "Connected to AA:BB:CC:12:34:56, remote 11:22:33:44:55:66"
        val redacted = AppLog.redact(message)
        assertFalse(redacted.contains("AA:BB:CC:12:34:56"))
        assertFalse(redacted.contains("11:22:33:44:55:66"))
        assertTrue(redacted.contains("[BT_DEVICE]"))
    }

    @Test fun telephoneNumbersAndCredentialsAreNotPreservedInLogs() {
        val redacted = AppLog.redact("Dialing +230 5123 4567 token=abc123 password=secret")
        assertFalse(redacted.contains("5123 4567"))
        assertFalse(redacted.contains("abc123"))
        assertFalse(redacted.contains("password=secret"))
    }
}
