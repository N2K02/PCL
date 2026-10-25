package com.pcl.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequiredPermissionsTest {
    private val gateway = RequiredPermissions.runtime(LinkService.ROLE_GATEWAY, 34)
    private val remote = RequiredPermissions.runtime(LinkService.ROLE_REMOTE, 34)

    @Test fun gatewayNeedsCallLogAndContactsForCallerNumbersAndNames() {
        assertTrue(gateway.contains("android.permission.READ_CALL_LOG"))
        assertTrue(gateway.contains("android.permission.READ_CONTACTS"))
        assertTrue(gateway.containsAll(listOf("android.permission.READ_PHONE_STATE",
            "android.permission.ANSWER_PHONE_CALLS", "android.permission.CALL_PHONE")))
    }

    @Test fun remoteNeedsNoPhoneAccess() {
        assertFalse(remote.any { it.contains("CALL") || it.contains("PHONE") })
        assertTrue(remote.contains("android.permission.BLUETOOTH_CONNECT"))
    }

    @Test fun olderAndroidSkipsPermissionsItDoesNotHave() {
        val old = RequiredPermissions.runtime(LinkService.ROLE_GATEWAY, 28)
        assertFalse(old.contains("android.permission.BLUETOOTH_CONNECT"))
        assertFalse(old.contains("android.permission.POST_NOTIFICATIONS"))
    }

    @Test fun everyRequiredPermissionIsDeclaredInTheManifest() {
        val manifest = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
            .map(::java.io.File).first { it.isFile }.readText()
        for (p in gateway + remote) assertTrue("$p missing from the manifest", manifest.contains("\"$p\""))
    }
}
