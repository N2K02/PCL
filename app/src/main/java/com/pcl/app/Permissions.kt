package com.pcl.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * The one list of what each phone role must have before PCL can be used. The setup screen,
 * Start, and the service all use it, so a permission added here is required everywhere.
 */
object RequiredPermissions {
    /** Runtime permissions [role] needs on Android [sdk]. Pure, for tests. */
    fun runtime(role: String, sdk: Int): List<String> {
        val list = ArrayList<String>()
        if (sdk >= 31) list.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (sdk >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        if (role == LinkService.ROLE_GATEWAY) {
            list.add(Manifest.permission.READ_PHONE_STATE)
            list.add(Manifest.permission.ANSWER_PHONE_CALLS)
            list.add(Manifest.permission.CALL_PHONE)
            // Caller names and the contact sync.
            list.add(Manifest.permission.READ_CONTACTS)
            // Since Android 9 the caller's number is only given to apps holding this.
            list.add(Manifest.permission.READ_CALL_LOG)
        }
        return list
    }

    /** Short name shown to the user for a runtime permission. */
    fun label(permission: String): String = when (permission) {
        Manifest.permission.BLUETOOTH_CONNECT -> "Nearby devices"
        Manifest.permission.POST_NOTIFICATIONS -> "Notifications"
        Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.CALL_PHONE -> "Phone"
        Manifest.permission.READ_CONTACTS -> "Contacts"
        Manifest.permission.READ_CALL_LOG -> "Call log"
        else -> permission.substringAfterLast('.')
    }

    /** Runtime permissions [role] still lacks on this phone. */
    fun missingRuntime(ctx: Context, role: String): List<String> =
        runtime(role, Build.VERSION.SDK_INT).filter {
            ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

    /** Everything [role] still lacks, as names for the user (runtime permissions and special access). */
    fun missingNames(ctx: Context, role: String): List<String> {
        val names = missingRuntime(ctx, role).map(::label).toMutableList()
        if (role == LinkService.ROLE_GATEWAY && !NotifListener.isEnabled(ctx)) names.add("Notification access")
        return names.distinct()
    }
}
