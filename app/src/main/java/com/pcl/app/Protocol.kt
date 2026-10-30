package com.pcl.app

import android.os.Handler
import android.os.Looper
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Wire format over the Bluetooth RFCOMM socket:
 *   [1 byte type][4 byte length][payload]
 * type 1 = text command "NAME|arg1|arg2".
 */
object Protocol {
    val SERVICE_UUID: UUID = UUID.fromString("7a1e5d2c-3b4f-4c8a-9e61-2f5d8c0b9a41")
    const val SERVICE_NAME = "PCL"
    const val TYPE_CMD = 1
    const val MAX_FRAME = 64 * 1024

    /**
     * Bumped whenever the phones start exchanging new messages, and sent in HELLO so mismatched
     * versions are reported instead of failing silently.
     *  - no number: PCL 2.1 and older
     *  - 3: HELLO carries versions; Gateway reports hotspot status (HOTSPOT_STATE)
     *  - 5: removed the legacy Wi-Fi hotspot workaround
     *  - 7: SIM-only command surface; third-party calls and notification mirroring removed
     *  - 8: remote-selectable Gateway volume modes and synchronized mode status
     *  - 9: verified SIM dialing and acknowledged optional CallLog enrichment
     *  - 10: call-control request IDs and confirmed connection compatibility
     *  - 11: match Answer/Reject/Hangup to the live Gateway SIM call instance
     *  - 12: bind volume/route commands to the live SIM call instance
     */
    const val VERSION = 12
}

/**
 * Which phone may act on each command. A command arriving at the wrong role is ignored,
 * so a peer can't, for example, make a Remote place calls.
 * HELLO goes both ways and is in neither set.
 */
object Commands {
    /** Sent by the Remote; handled only on the Gateway. */
    val GATEWAY_HANDLES = setOf(
        "ANSWER", "REJECT", "HANGUP", "DIAL", "DIAL_SIM", "CALL_LOG_ACK", "SYNC_CONTACTS",
        "AUDIO_VOLUME", "AUDIO_STEP", "AUDIO_ROUTE", "AUDIO_QUERY", "AUDIO_MODE"
    )

    /** Sent by the Gateway; handled only on the Remote. */
    val REMOTE_HANDLES = setOf(
        "CALL", "CALL_LOGGED", "CALL_RESULT", "CONTACTS_HASH", "CONTACTS_BEGIN", "CONTACTS", "CONTACTS_END",
        "CONTACTS_RESULT", "AUDIO_STATUS", "AUDIO_RESULT"
    )
}

/** Device-independent request correlation and authoritative phone-state confirmation. */
internal object CallControlProtocol {
    fun peerRoleMatches(ourRole: String, peerRole: String): Boolean =
        (ourRole == LinkService.ROLE_GATEWAY && peerRole == LinkService.ROLE_REMOTE) ||
            (ourRole == LinkService.ROLE_REMOTE && peerRole == LinkService.ROLE_GATEWAY)

    fun matchesResult(expectedId: Int, pendingCommand: String?, receivedId: String?): Boolean =
        expectedId > 0 && pendingCommand != null &&
            receivedId?.toIntOrNull() == expectedId

    fun stateConfirmed(command: String?, state: String): Boolean = when (command) {
        "ANSWER", "DIAL", "DIAL_SIM" -> state == LinkService.CALL_OFFHOOK
        "REJECT", "HANGUP" -> state == LinkService.CALL_IDLE
        else -> false
    }
}

class FrameWriter(stream: OutputStream) {
    private val out = DataOutputStream(BufferedOutputStream(stream, 8192))

    @Synchronized
    fun writeCmd(cmd: String) {
        val b = cmd.toByteArray(Charsets.UTF_8)
        writeFrame(Protocol.TYPE_CMD, b, b.size)
    }

    private fun writeFrame(type: Int, b: ByteArray, len: Int) {
        out.writeByte(type)
        out.writeInt(len)
        out.write(b, 0, len)
        out.flush()
    }
}

/** Tiny shared state between the service and the screen. */
object Hub {
    @Volatile
    var service: LinkService? = null
    var listener: (() -> Unit)? = null

    /** Bumped when new contacts arrive from the gateway, so the screen reloads them. */
    @Volatile
    var contactsVersion = 0

    private val main = Handler(Looper.getMainLooper())

    /** Redact before writing any user-visible or USB-readable diagnostics. */
    fun log(msg: String) {
        val safeEvent = when {
            msg.startsWith("Connected to ") -> "Connected to selected Bluetooth peer"
            msg.startsWith("Connecting to ") -> "Connecting to selected Bluetooth peer"
            msg.startsWith("Waiting for ") -> "Waiting for selected Bluetooth peer"
            msg.startsWith("Refused Bluetooth connection from ") -> "Rejected unselected Bluetooth peer"
            else -> msg
        }
        val redacted = AppLog.redact(safeEvent)
        android.util.Log.i("PCL", redacted)
        CallLogger.log(redacted)
        AppLog.add("LINK", redacted)
        notifyUi()
    }

    fun notifyUi() {
        main.post { listener?.invoke() }
    }
}
