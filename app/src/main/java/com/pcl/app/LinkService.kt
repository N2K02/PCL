package com.pcl.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.media.AudioManager
import android.media.AudioDeviceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Base64
import android.util.Log
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs on both phones.
 *  - GATEWAY (phone with the SIM): accepts the selected Remote over Bluetooth, reports
 *    SIM call state, and answers/rejects/dials on request.
 *  - REMOTE (companion phone): connects to the selected Gateway, rings on incoming calls,
 *    and sends answer/reject/dial commands.
 */
class LinkService : Service() {

    companion object {
        const val ROLE_GATEWAY = "gateway"
        const val ROLE_REMOTE = "remote"

        const val ACTION_START = "com.pcl.app.START"
        const val ACTION_STOP = "com.pcl.app.STOP"
        const val ACTION_ANSWER = "com.pcl.app.ANSWER"
        const val ACTION_REJECT = "com.pcl.app.REJECT"
        const val ACTION_HANGUP = "com.pcl.app.HANGUP"
        const val ACTION_AUDIO_DOWN = "com.pcl.app.AUDIO_DOWN"
        const val ACTION_AUDIO_UP = "com.pcl.app.AUDIO_UP"
        const val ACTION_AUDIO_SPEAKER = "com.pcl.app.AUDIO_SPEAKER"
        /** Remote: "Call back" on a missed-call notification. */
        const val ACTION_CALL_BACK = "com.pcl.app.CALL_BACK"
        const val EXTRA_NUMBER = "number"
        const val EXTRA_NAME = "name"
        const val EXTRA_CALL_TOKEN = "call_token"
        /** MainActivity: which tab to open ("calls"). */
        const val EXTRA_OPEN_TAB = "open_tab"
        const val EXTRA_ROLE = "role"
        const val EXTRA_DEVICE = "device"
        const val EXTRA_REASON = "reason"

        const val CALL_IDLE = "IDLE"
        const val CALL_RINGING = "RINGING"
        const val CALL_OFFHOOK = "OFFHOOK"

        private const val CH_STATUS = "pcl_status"
        private const val CH_CALLS = "pcl_calls"
        // Old channels, deleted on start: the "vgs_" ones from before the rename, and the
        // hotspot channel from when PCL switched the Gateway's Wi-Fi hotspot for calls.
        private val LEGACY_CHANNELS = listOf("vgs_status", "vgs_calls", "vgs_mirror", "vgs_hotspot", "pcl_hotspot")
        private const val ID_STATUS = 1
        private const val ID_INCOMING = 2
        private const val ID_MISSED = 46
        private const val TRAFFIC_SAMPLE_MS = 5_000L
        private const val CALL_CONTROL_TIMEOUT_MS = 15_000L
        /** Recheck SIM state only while ringing if Android omits OFFHOOK/IDLE events. */
        private const val PHONE_STATE_RECHECK_MS = 500L
        /** Remote: a SIM call connecting this soon after this phone asked to dial is this phone's. */
        private const val DIAL_WINDOW_MS = 60_000L
        /** Upper bound for a contact sync in characters; protects memory from a misbehaving peer. */
        private const val MAX_CONTACT_CHARS = 8_000_000

        /** Restarts the link with the saved role and phone, if the user left it running. */
        fun startSaved(context: Context, reason: String) {
            val prefs = Prefs.of(context)
            if (!prefs.getBoolean(Prefs.LINK_ENABLED, false)) return
            if (Build.VERSION.SDK_INT >= 31 &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) return
            val intent = Intent(context, LinkService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_ROLE, prefs.getString(Prefs.ROLE, ROLE_REMOTE))
                .putExtra(EXTRA_DEVICE, prefs.getString(Prefs.PEER_DEVICE, null))
                .putExtra(EXTRA_REASON, reason)
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.w("PCL", "Could not restart the link $reason", e)
            }
        }
    }

    @Volatile var role: String = ROLE_REMOTE
        private set
    @Volatile var connectionState: String = "Stopped"
        private set
    @Volatile var connected: Boolean = false
        private set
    @Volatile var gatewayVolume = 0
        private set
    @Volatile var gatewayMaxVolume = 1
        private set
    @Volatile var gatewayAudioRoute = "UNKNOWN"
        private set

    @Volatile var gatewayVolumeMode = "AUTO"
        private set
    private val volumeModes = setOf("AUTO", "CALL", "MEDIA", "RING")
    private var selectedGatewayVolumeMode: String
        get() = Prefs.of(this).getString("gateway_volume_mode", "AUTO")
            ?.takeIf { it in volumeModes } ?: "AUTO"
        set(value) {
            Prefs.of(this).edit().putString("gateway_volume_mode", value).apply()
        }

    fun requestGatewayVolumeMode(mode: String) {
        if (mode in volumeModes) sendAudioRequest("AUDIO_MODE", mode)
    }

    private val audioRequestSequence = AtomicLong()
    private val pendingAudioRequests = ConcurrentHashMap<String, Runnable>()
    private val audioTimeoutHandler = Handler(Looper.getMainLooper())

    private fun clearPendingAudioRequests() {
        pendingAudioRequests.values.forEach(audioTimeoutHandler::removeCallbacks)
        pendingAudioRequests.clear()
    }

    private fun sendAudioRequest(command: String, value: String) {
        if (role != ROLE_REMOTE || !connected) {
            Hub.log("Audio command unavailable: Gateway disconnected")
            return
        }
        val id = audioRequestSequence.incrementAndGet().toString()
        val timeout = Runnable {
            if (pendingAudioRequests.remove(id) != null)
                Hub.log("Audio command timed out: $command")
        }
        pendingAudioRequests[id] = timeout
        audioTimeoutHandler.postDelayed(timeout, 5_000L)
        Hub.log("Remote audio request: $command #$id")
        send(command, value, id)
    }

    fun requestGatewayVolume(level: Int) {
        sendAudioRequest("AUDIO_VOLUME", level.coerceAtLeast(0).toString())
    }

    fun requestGatewayRoute(route: String) {
        // The Gateway owns the authoritative call state; never silently drop a
        // request because the Remote has not received the latest call update.
        if (role == ROLE_REMOTE && connected && route in setOf("SPEAKER", "EARPIECE"))
            sendAudioRequest("AUDIO_ROUTE", route)
    }

    fun refreshGatewayAudio() {
        if (role == ROLE_REMOTE && connected) send("AUDIO_QUERY")
    }

    private fun gatewayStream(audio: AudioManager): Int = when (selectedGatewayVolumeMode) {
        "CALL" -> AudioManager.STREAM_VOICE_CALL
        "MEDIA" -> AudioManager.STREAM_MUSIC
        "RING" -> AudioManager.STREAM_RING
        else -> when {
            callState != CALL_IDLE ||
                audio.mode == AudioManager.MODE_IN_CALL ||
                audio.mode == AudioManager.MODE_IN_COMMUNICATION -> AudioManager.STREAM_VOICE_CALL
            else -> AudioManager.STREAM_MUSIC
        }
    }

    private fun gatewaySetVolumeMode(raw: String, requestId: String) {
        if (raw !in volumeModes) {
            send("AUDIO_RESULT", "MODE", "INVALID", requestId)
            return
        }
        selectedGatewayVolumeMode = raw
        Hub.log("Gateway volume mode: $raw")
        send("AUDIO_RESULT", "MODE", "APPLIED", requestId)
        gatewayAudioStatus()
    }

    private fun gatewayAudioStep(raw: String, requestId: String = "") {
        val audio = getSystemService(AudioManager::class.java) ?: return
        val delta = raw.toIntOrNull() ?: return
        if (delta !in -1..1 || delta == 0) return
        val stream = gatewayStream(audio)
        try {
            val before = audio.getStreamVolume(stream)
            audio.adjustStreamVolume(stream, if (delta > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
            val after = audio.getStreamVolume(stream)
            Hub.log("Gateway audio step: stream=$stream, $before -> $after")
            send("AUDIO_RESULT", "STEP", if (after != before) "APPLIED" else "NOT_APPLIED", requestId)
        } catch (e: Exception) {
            Hub.log("Gateway audio step failed: ${e.message ?: e.javaClass.simpleName}")
            send("AUDIO_RESULT", "STEP", "ERROR", requestId)
        } finally { gatewayAudioStatus() }
    }

    private fun gatewayRoute(audio: AudioManager): String {
        if (Build.VERSION.SDK_INT < 31) {
            @Suppress("DEPRECATION")
            return if (audio.isBluetoothScoOn) "BLUETOOTH"
            else if (audio.isWiredHeadsetOn) "WIRED_HEADSET"
            else if (audio.isSpeakerphoneOn) "SPEAKER" else "EARPIECE"
        }
        return when (audio.communicationDevice?.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "SPEAKER"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "EARPIECE"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLUETOOTH"
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET -> "WIRED_HEADSET"
            else -> "UNKNOWN"
        }
    }

    private fun verifyGatewayRouteLater(route: String, requestId: String) {
        audioTimeoutHandler.postDelayed({
            if (!running || role != ROLE_GATEWAY || !connected) return@postDelayed
            val audio = getSystemService(AudioManager::class.java) ?: return@postDelayed
            val actual = gatewayRoute(audio)
            val outcome = if (actual == route) "APPLIED" else "NOT_APPLIED"
            Hub.log("Gateway delayed route verification: requested=$route actual=$actual result=$outcome")
            send("AUDIO_RESULT", "ROUTE_VERIFY", outcome, requestId)
            gatewayAudioStatus()
        }, 900L)
    }

    private fun gatewayAudioStatus() {
        val audio = getSystemService(AudioManager::class.java) ?: return
        val stream = gatewayStream(audio)
        send("AUDIO_STATUS", audio.getStreamVolume(stream).toString(),
            audio.getStreamMaxVolume(stream).toString(), gatewayRoute(audio), selectedGatewayVolumeMode)
    }

    private fun gatewaySetAudioVolume(raw: String, requestId: String = "") {
        val audio = getSystemService(AudioManager::class.java) ?: return
        val level = raw.toIntOrNull() ?: return
        val stream = gatewayStream(audio)
        try {
            val before = audio.getStreamVolume(stream)
            val target = level.coerceIn(0, audio.getStreamMaxVolume(stream))
            audio.setStreamVolume(stream, target, 0)
            val actual = audio.getStreamVolume(stream)
            Hub.log("Gateway volume: stream=$stream, mode=${audio.mode}, $before -> $actual / ${audio.getStreamMaxVolume(stream)}")
            send("AUDIO_RESULT", "VOLUME", if (actual == target) "APPLIED" else "NOT_APPLIED", requestId)
        } catch (e: Exception) {
            Hub.log("Gateway volume failed: ${e.javaClass.simpleName}")
            send("AUDIO_RESULT", "VOLUME", "ERROR", requestId)
        } finally {
            gatewayAudioStatus()
        }
    }

    private fun gatewaySetAudioRoute(route: String, requestId: String = "") {
        if (route !in setOf("SPEAKER", "EARPIECE")) {
            send("AUDIO_RESULT", "ROUTE", "INVALID", requestId)
            return
        }
        val audio = getSystemService(AudioManager::class.java) ?: return
        val active = callState != CALL_IDLE ||
            audio.mode == AudioManager.MODE_IN_CALL ||
            audio.mode == AudioManager.MODE_IN_COMMUNICATION
        if (!active) {
            Hub.log("Gateway audio route not applied: no active communication")
            send("AUDIO_RESULT", "ROUTE", "NO_CALL", requestId)
            gatewayAudioStatus()
            return
        }
        try {
            Hub.log("Gateway audio route requested: $route; mode=${audio.mode}")
            val accepted = if (Build.VERSION.SDK_INT >= 31) {
                val type = if (route == "SPEAKER") AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                val target = audio.availableCommunicationDevices.firstOrNull { it.type == type }
                target != null && audio.setCommunicationDevice(target)
            } else {
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = route == "SPEAKER"
                true
            }
            val verifiedRoute = gatewayRoute(audio)
            val outcome = when {
                verifiedRoute == route -> "APPLIED"
                accepted -> "PENDING"
                else -> "NOT_APPLIED"
            }
            Hub.log("Gateway audio route: requested=$route, actual=$verifiedRoute, outcome=$outcome")
            send("AUDIO_RESULT", "ROUTE", outcome, requestId)
            if (outcome == "PENDING") verifyGatewayRouteLater(route, requestId)
        } catch (e: Exception) {
            Hub.log("Gateway audio route failed: ${e.javaClass.simpleName}")
            send("AUDIO_RESULT", "ROUTE", "ERROR", requestId)
        } finally {
            gatewayAudioStatus()
        }
    }

    @Volatile var callState: String = CALL_IDLE
        private set
    @Volatile var callNumber: String = ""
        private set
    @Volatile var callName: String = ""
        private set
    @Volatile private var callSimIdentity: SimCallIdentity? = null
    @Volatile var availableSimSlots: Set<Int> = emptySet()
        private set
    @Volatile var callDirection: String = ""
        private set
    @Volatile var callControlError: String? = null
        private set

    @Volatile var downloadBytesPerSecond: Long = 0L
        private set
    @Volatile var uploadBytesPerSecond: Long = 0L
        private set
    private val rxTotal = AtomicLong(0L)
    private val txTotal = AtomicLong(0L)
    private var lastRxSample = 0L
    private var lastTxSample = 0L
    private val trafficHandler = Handler(Looper.getMainLooper())
    private val trafficSampler = object : Runnable {
        override fun run() {
            if (!running) return
            val rx = rxTotal.get()
            val tx = txTotal.get()
            // Sampled every few seconds and shown per second; an idle link wakes the phone
            // rarely, and the notification is only touched when the numbers change.
            val down = if (connected) (rx - lastRxSample).coerceAtLeast(0L) * 1000L / TRAFFIC_SAMPLE_MS else 0L
            val up = if (connected) (tx - lastTxSample).coerceAtLeast(0L) * 1000L / TRAFFIC_SAMPLE_MS else 0L
            lastRxSample = rx; lastTxSample = tx
            if (down != downloadBytesPerSecond || up != uploadBytesPerSecond) {
                downloadBytesPerSecond = down
                uploadBytesPerSecond = up
                updateStatus()
            }
            trafficHandler.postDelayed(this, TRAFFIC_SAMPLE_MS)
        }
    }

    /** Protocol version and app version the other phone reported in HELLO; null until HELLO arrives. */
    @Volatile var peerProtocol: Int? = null
        private set
    @Volatile var peerAppVersion: String = ""
        private set
    @Volatile private var helloReceived = false

    /** A warning to show when the two phones run different PCL versions, or null when they match. */
    val versionWarning: String?
        get() {
            if (!connected || !helloReceived) return null
            val theirs = peerProtocol
            val named = if (peerAppVersion.isNotEmpty()) " (${peerAppVersion})" else ""
            return when {
                theirs == Protocol.VERSION -> null
                theirs == null || theirs < Protocol.VERSION ->
                    "The other phone runs an older PCL$named. Update it so both phones run the same version; some features won't work until then."
                else -> "This phone runs an older PCL than the other phone$named. Update this phone from the Update tab."
            }
        }

    private val appVersion: String by lazy {
        try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    @Volatile private var callControlRequest = 0
    @Volatile private var pendingCallCommand: String? = null
    @Volatile private var pendingAnswer = false
    @Volatile private var pendingReject = false
    @Volatile private var pendingCallAccepted = false
    /** Timestamp identity of the live Gateway SIM call. */
    @Volatile private var activeCallToken: Long? = null
    @Volatile private var pendingCallToken: Long? = null
    private var pendingDeclineRecord: CallHistory.Record? = null

    @Volatile private var running = false
    @Volatile private var generation = 0
    @Volatile private var deviceAddress: String? = null
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var writer: FrameWriter? = null
    private var worker: Thread? = null
    private val io = Executors.newSingleThreadExecutor()
    // Contacts and telecom providers can block for seconds on some OEMs. Do not
    // query them on the Bluetooth reader or the phone-state broadcast thread.
    private val contactsExecutor = Executors.newSingleThreadExecutor()
    private val phoneDataExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var verifiedSimSlots: Set<Int> = emptySet()
    // Optional CallLog I/O must never delay Bluetooth command processing.
    private val callLogExecutor = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(8))
    private val pendingCallLogTasks = mutableSetOf<Runnable>()
    private var receiverRegistered = false
    private var ringtone: Ringtone? = null
    private var callWaitingSeen = false
    private var multipleActiveSimCalls = false
    @Volatile var callControlsRestricted = false
        private set

    private fun updateControlRestriction() {
        callControlsRestricted = SimCallConcurrencyPolicy.shouldBlock(callWaitingSeen, multipleActiveSimCalls)
    }

    /** Gateway: recover phone-state transitions missed by the Android broadcast. */
    private val gatewayPhoneStateRecheck = object : Runnable {
        override fun run() {
            if (!running || role != ROLE_GATEWAY || callState == CALL_IDLE) return
            val observed = currentGatewayPhoneState()
            if (SimCallConcurrencyPolicy.shouldRecheck(callState, observed, callWaitingSeen)) {
                Hub.log("Reconciled SIM phone-state transition: $observed")
                applyGatewayPhoneState(observed!!, null)
            } else {
                scheduleGatewayPhoneStateRecheck()
            }
        }
    }

    private fun scheduleGatewayPhoneStateRecheck() {
        trafficHandler.removeCallbacks(gatewayPhoneStateRecheck)
        if (running && role == ROLE_GATEWAY && callState != CALL_IDLE) {
            trafficHandler.postDelayed(gatewayPhoneStateRecheck,
                if (callState == CALL_RINGING) PHONE_STATE_RECHECK_MS else 1_000L)
        }
    }

    /** Remote: this phone answered or placed the current SIM call, so it shows it until it ends. */
    @Volatile private var simCallHere = false
    private var simDialAt = 0L

    /** Remote: the current SIM call was answered or placed on the Gateway, so this phone stays quiet. */
    @Volatile var simCallOnGateway = false
        private set
    private var contactBuf: StringBuilder? = null
    private var expectedContactHash: String? = null

    val isActive: Boolean get() = running

    private val nm: NotificationManager by lazy { getSystemService(NotificationManager::class.java)!! }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Hub.service = this
        CallHistory.init(this)
        AppLog.init(this)
        createChannels()
    }

    override fun onDestroy() {
        shutdown()
        io.shutdownNow()
        contactsExecutor.shutdownNow()
        phoneDataExecutor.shutdownNow()
        callLogExecutor.shutdownNow()
        Hub.service = null
        Hub.notifyUi()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                Prefs.of(this).edit().putBoolean(Prefs.LINK_ENABLED, true).apply()
                begin(
                    intent.getStringExtra(EXTRA_ROLE) ?: ROLE_REMOTE,
                    intent.getStringExtra(EXTRA_DEVICE),
                    intent.getStringExtra(EXTRA_REASON)
                )
            }
            null -> {
                // Android restarted the service after killing it (START_STICKY).
                val prefs = Prefs.of(this)
                if (!running && prefs.getBoolean(Prefs.LINK_ENABLED, false)) {
                    begin(
                        prefs.getString(Prefs.ROLE, ROLE_REMOTE) ?: ROLE_REMOTE,
                        prefs.getString(Prefs.PEER_DEVICE, null),
                        "after Android stopped it"
                    )
                } else if (!running) {
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                Prefs.of(this).edit().putBoolean(Prefs.LINK_ENABLED, false).apply()
                shutdown()
                stopSelf()
            }
            ACTION_ANSWER, ACTION_REJECT, ACTION_HANGUP -> {
                if (!running) stopSelf()
                else if (validNotificationCallAction(intent)) {
                    when (intent.action) {
                        ACTION_ANSWER -> answer()
                        ACTION_REJECT -> reject()
                        ACTION_HANGUP -> hangUp()
                    }
                } else Hub.log("Ignored stale notification action for a different SIM call")
            }
            ACTION_AUDIO_DOWN, ACTION_AUDIO_UP -> if (running && role == ROLE_REMOTE && connected) {
                val delta = if (intent.action == ACTION_AUDIO_UP) 1 else -1
                Hub.log("Remote audio step: $delta")
                sendAudioRequest("AUDIO_STEP", delta.toString())
            }
            ACTION_AUDIO_SPEAKER -> if (running && role == ROLE_REMOTE && connected) {
                val target = if (gatewayAudioRoute == "SPEAKER") "EARPIECE" else "SPEAKER"
                Hub.log("Remote audio route: $target")
                requestGatewayRoute(target)
            }
            ACTION_CALL_BACK -> {
                nm.cancel(ID_MISSED)
                val number = intent.getStringExtra(EXTRA_NUMBER).orEmpty()
                if (running && number.isNotEmpty()) dial(number, intent.getStringExtra(EXTRA_NAME).orEmpty())
                else if (!running) stopSelf()
            }
        }
        Hub.notifyUi()
        return if (running) START_STICKY else START_NOT_STICKY
    }

    private fun begin(newRole: String, device: String?, reason: String?) {
        if (running) shutdown()
        role = newRole
        deviceAddress = device
        running = true
        connectionState = "Starting…"
        rxTotal.set(0L); txTotal.set(0L); lastRxSample = 0L; lastTxSample = 0L
        downloadBytesPerSecond = 0L; uploadBytesPerSecond = 0L
        try {
            goForeground()
        } catch (e: Exception) {
            // Android can refuse a foreground service started from the background.
            running = false
            connectionState = "Stopped"
            Hub.log("Android didn't allow PCL to start ${reason ?: ""}: ${e.message ?: e.javaClass.simpleName}. Open PCL and press Start.")
            stopSelf()
            return
        }
        if (reason != null) Hub.log("Link restarted $reason")
        trafficHandler.removeCallbacks(trafficSampler)
        trafficHandler.postDelayed(trafficSampler, TRAFFIC_SAMPLE_MS)
        // Establish a generation before scheduling background provider reads.
        startWorker()
        if (role == ROLE_GATEWAY) registerPhoneReceiver()
    }

    // ---------------------------------------------------------------- public actions (UI)

    /** A lock-screen action is valid only for the call that opened that screen. */
    fun currentIncomingToken(): Long? = activeCallToken.takeIf {
        role == ROLE_REMOTE && callState == CALL_RINGING && !callControlsRestricted
    }

    fun answerForCall(token: Long?) {
        if (token != null && currentIncomingToken() == token) answer()
    }

    fun rejectForCall(token: Long?) {
        if (token != null && currentIncomingToken() == token) reject()
    }

    private fun validNotificationCallAction(intent: Intent): Boolean {
        val token = intent.getLongExtra(EXTRA_CALL_TOKEN, 0L)
        val action = intent.action.orEmpty()
        return role == ROLE_REMOTE && token > 0L && activeCallToken == token &&
            !callControlsRestricted && when (action) {
                ACTION_ANSWER, ACTION_REJECT -> callState == CALL_RINGING
                ACTION_HANGUP -> callState == CALL_OFFHOOK
                else -> false
            }
    }

    fun answer() {
        if (role == ROLE_GATEWAY) {
            gatewayAnswer()
        } else {
            // Keep ringing until the Gateway confirms OFFHOOK; a failed answer
            // must not silently dismiss the incoming call.
            requestGateway("ANSWER")
        }
    }

    fun reject() {
        if (role == ROLE_GATEWAY) {
            gatewayEnd(rejectRinging = true)
        } else {
            // Mark Declined only once the Gateway confirms the rejection.
            requestGateway("REJECT")
        }
    }

    fun hangUp() {
        if (role == ROLE_GATEWAY) gatewayEnd() else requestGateway("HANGUP")
    }

    /** [name] is only used for the Remote's call history. */
    fun dial(number: String, name: String = "") {
        if (role == ROLE_GATEWAY) {
            gatewayDial(number)
        } else {
            requestGateway("DIAL", number, displayName = name)
        }
    }

    /** Directed SIM calling is only available with a verified voice account. */
    fun dialWithSim(number: String, name: String, slot: Int) {
        if (slot !in 0..1) {
            callControlError = "FAILED: Invalid SIM slot"
            Hub.notifyUi()
            return
        }
        if (role == ROLE_GATEWAY) {
            gatewayDial(number, slot)
        } else if (peerProtocol == null || peerProtocol!! < 9) {
            callControlError = "FAILED: Update the Gateway to support verified SIM selection"
            Hub.notifyUi()
        } else if (slot !in availableSimSlots) {
            callControlError = "FAILED: Selected SIM does not have a verified voice account"
            Hub.notifyUi()
        } else {
            requestGateway("DIAL_SIM", number, slot, displayName = name)
        }
    }

    /** Ask the Gateway to refresh the Remote's offline contact cache. */
    fun syncContacts() {
        if (role == ROLE_GATEWAY) {
            sendContacts()
        } else if (!connected) {
            Hub.log("Contact sync unavailable: Bluetooth is disconnected")
        } else {
            send("SYNC_CONTACTS")
            Hub.log("Requested contact sync from Gateway")
        }
    }

    private fun requestGateway(command: String, argument: String = "", selectedSlot: Int? = null, displayName: String = ""): Boolean {
        if (!connected || !helloReceived || peerProtocol != Protocol.VERSION) {
            callControlError = "FAILED: Connect two phones running the same PCL version"
            Hub.notifyUi()
            return false
        }
        if (callControlsRestricted) {
            callControlError = "FAILED: Concurrent SIM calls must be handled on the Gateway"
            Hub.notifyUi()
            return false
        }
        if (command in setOf("ANSWER", "REJECT", "HANGUP") && activeCallToken == null) {
            callControlError = "FAILED: Gateway call identity is not synchronized"
            Hub.notifyUi()
            return false
        }
        if (command == "DIAL_SIM" && selectedSlot == null) {
            callControlError = "FAILED: No SIM selected"
            Hub.notifyUi()
            return false
        }
        // No duplicate requests. Allow Reject/Hangup to supersede a pending
        // Answer/Dial so the caller can still stop an unwanted call.
        if (pendingCallCommand != null) {
            if (pendingCallCommand == command || command !in setOf("REJECT", "HANGUP")) {
                Hub.log("Call-control request already pending")
                return false
            }
            clearPendingCall()
        }
        val request = ++callControlRequest
        pendingCallCommand = command
        pendingCallToken = if (command in setOf("ANSWER", "REJECT", "HANGUP")) activeCallToken else null
        pendingCallAccepted = false
        // Set ownership before sending: the Bluetooth reader can receive OFFHOOK/IDLE
        // before answer()/reject() returns on the UI thread.
        pendingAnswer = command == "ANSWER"
        pendingReject = command == "REJECT"
        if (command in setOf("DIAL", "DIAL_SIM")) {
            CallHistory.onDialRequested(argument, displayName)
            simDialAt = SystemClock.elapsedRealtime()
        }
        callControlError = "PENDING: Waiting for Gateway confirmation"
        when (command) {
            "DIAL" -> send(command, argument, request.toString())
            "DIAL_SIM" -> send(command, argument, selectedSlot.toString(), request.toString())
            else -> send(command, request.toString(), pendingCallToken?.toString().orEmpty())
        }
        trafficHandler.postDelayed({
            if (request == callControlRequest && callControlError?.startsWith("PENDING:") == true) {
                callControlError = "FAILED: Gateway did not confirm the call state"
                clearPendingCall()
                Hub.log("Call control timed out: $command")
                Hub.notifyUi()
            }
        }, CALL_CONTROL_TIMEOUT_MS)
        Hub.notifyUi()
        return true
    }

    private fun clearPendingCall() {
        if (pendingCallCommand in setOf("DIAL", "DIAL_SIM")) {
            CallHistory.onDialFailed()
            simDialAt = 0L
        }
        pendingCallCommand = null
        pendingCallToken = null
        pendingCallAccepted = false
        pendingDeclineRecord = null
        pendingAnswer = false
        pendingReject = false
    }

    /** Prevent the Remote from claiming calls before its correlated ACK and OFFHOOK. */
    private fun remoteOwnsPendingCall(): Boolean = RemoteCallOwnershipPolicy.claims(
        pendingAnswer, pendingCallCommand, pendingCallAccepted, callState,
        simDialAt, SystemClock.elapsedRealtime(), DIAL_WINDOW_MS
    )

    /** A protocol acknowledgement is not proof that the telephone changed state. */
    private fun confirmCallState() {
        val confirmed = CallControlProtocol.stateConfirmed(pendingCallCommand, callState)
        if (confirmed && pendingCallAccepted) {
            if (pendingCallCommand in setOf("DIAL", "DIAL_SIM")) simDialAt = 0L
            pendingCallCommand = null
            pendingCallToken = null
            pendingCallAccepted = false
            pendingDeclineRecord = null
            pendingAnswer = false
            pendingReject = false
            callControlError = null
        }
    }

    // ---------------------------------------------------------------- connection

    private fun startWorker() {
        val gen = ++generation
        worker = Thread({ workerLoop(gen) }, "pcl-link").also { it.start() }
    }

    private fun alive(gen: Int) = running && gen == generation

    private fun workerLoop(gen: Int) {
        // Failed client connection attempts back off exponentially to reduce wakeups
        // and log churn when the peer is out of range or Bluetooth is unavailable.
        // A healthy session resets the delay so ordinary reconnects remain quick.
        var retryDelayMs = 3_000L
        while (alive(gen)) {
            var s: BluetoothSocket? = null
            var sessionStartedAt = 0L
            try {
                s = if (role == ROLE_GATEWAY) acceptOne(gen) else connectOnce(gen)
                if (s != null && alive(gen)) {
                    sessionStartedAt = SystemClock.elapsedRealtime()
                    runSession(s, gen)
                }
            } catch (e: Exception) {
                if (alive(gen)) Hub.log("Link: ${e.message}")
            } finally {
                try {
                    s?.close()
                } catch (e: Exception) {
                }
            }
            if (!alive(gen)) break
            if (sessionStartedAt != 0L && SystemClock.elapsedRealtime() - sessionStartedAt >= 15_000L) {
                retryDelayMs = 3_000L
            }
            val waitMs = retryDelayMs
            setConn(false, if (role == ROLE_GATEWAY) "Disconnected – waiting again…"
                else "Disconnected – retrying in ${waitMs / 1000}s…")
            try {
                Thread.sleep(waitMs)
            } catch (e: InterruptedException) {
                break
            }
            retryDelayMs = (retryDelayMs * 2).coerceAtMost(30_000L)
        }
    }

    private fun adapter(): BluetoothAdapter {
        val a = getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw IOException("This phone has no Bluetooth")
        if (!a.isEnabled) throw IOException("Bluetooth is OFF – turn it on")
        return a
    }

    @SuppressLint("MissingPermission")
    private fun deviceName(a: BluetoothAdapter, address: String): String =
        try { a.getRemoteDevice(address).name } catch (_: Exception) { null } ?: address

    /** Gateway: waits for the selected Remote. Connections from any other paired device are refused. */
    @SuppressLint("MissingPermission")
    private fun acceptOne(gen: Int): BluetoothSocket? {
        val a = adapter()
        val allowed = deviceAddress
            ?: throw IOException("No Remote phone selected – choose it on the Home screen")
        setConn(false, "Waiting for ${deviceName(a, allowed)}…")
        val ss = a.listenUsingRfcommWithServiceRecord(Protocol.SERVICE_NAME, Protocol.SERVICE_UUID)
        serverSocket = ss
        try {
            while (alive(gen)) {
                val s = ss.accept()
                if (!alive(gen)) {
                    s.close()
                    return null
                }
                val from = s.remoteDevice.address
                if (from.equals(allowed, ignoreCase = true)) return s
                Hub.log("Refused Bluetooth connection from ${deviceName(a, from)}: it isn't the selected Remote")
                try {
                    s.close()
                } catch (e: Exception) {
                }
            }
            return null
        } finally {
            try {
                ss.close()
            } catch (e: Exception) {
            }
            if (serverSocket === ss) serverSocket = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectOnce(gen: Int): BluetoothSocket {
        val a = adapter()
        val addr = deviceAddress ?: throw IOException("No gateway phone selected")
        val dev = a.getRemoteDevice(addr)
        setConn(false, "Connecting to ${dev.name ?: addr}…")
        val s = dev.createRfcommSocketToServiceRecord(Protocol.SERVICE_UUID)
        if (!alive(gen)) {
            s.close()
            throw IOException("Bluetooth session replaced")
        }
        socket = s
        try {
            s.connect()
        } catch (e: IOException) {
            throw IOException("Can't reach gateway (is PCL started there?)")
        }
        return s
    }

    @SuppressLint("MissingPermission")
    private fun runSession(s: BluetoothSocket, gen: Int) {
        if (!alive(gen)) {
            s.close()
            return
        }
        socket = s
        val w = FrameWriter(s.outputStream)
        writer = w
        try {
            val peer = try {
                s.remoteDevice.name ?: s.remoteDevice.address
            } catch (e: Exception) {
                "peer"
            }
            setConn(true, "Connected to $peer")
            helloReceived = false
            peerProtocol = null
            peerAppVersion = ""
            clearPendingCall()
            // The Gateway's first CALL after a fresh Remote session is a snapshot,
            // not evidence of a newly started ringing or outgoing call. This also
            // protects process recreation where no disconnect callback ran.
            if (role == ROLE_REMOTE) CallHistory.onLinkLost()
            send("HELLO", role, Protocol.VERSION.toString(), appVersion)
            if (role == ROLE_GATEWAY) {
                sendCallState()
                sendContactsFingerprint()
            }
            val input = DataInputStream(BufferedInputStream(s.inputStream, 16384))
            while (alive(gen)) {
                val type = input.readUnsignedByte()
                val len = input.readInt()
                if (len < 0 || len > Protocol.MAX_FRAME) throw IOException("bad data from peer")
                val data = ByteArray(len)
                input.readFully(data)
                rxTotal.addAndGet(len.toLong() + 5L)
                when (type) {
                    Protocol.TYPE_CMD -> handleCommand(String(data, Charsets.UTF_8))
                }
            }
        } finally {
            // Only the owner of the *current* session may clear live state.
            // An old reader can terminate after a new generation has connected.
            if (writer === w) {
                writer = null
                if (socket === s) socket = null
                connected = false
                clearPendingAudioRequests()
                contactBuf = null
                expectedContactHash = null
                helloReceived = false
                if (role == ROLE_REMOTE) {
                    if (callControlError?.startsWith("PENDING:") == true) callControlError = "FAILED: Connection lost before Gateway confirmation"
                    clearPendingCall()
                    stopRinging()
                    CallHistory.onLinkLost()
                    callState = CALL_IDLE
                    activeCallToken = null
                    callControlsRestricted = false
                    callNumber = ""
                    callName = ""
                    callDirection = ""
                    callSimIdentity = null
                    availableSimSlots = emptySet()
                    simCallHere = false
                    simCallOnGateway = false
                    simDialAt = 0L
                    nm.cancel(ID_INCOMING)
                }
            }
        }
    }

    private fun closeSocket() {
        try {
            socket?.close()
        } catch (e: Exception) {
        }
        socket = null
    }

    private fun shutdown() {
        if (!running && worker == null) return
        running = false
        if (role == ROLE_REMOTE) CallHistory.onLinkLost()
        pendingCallLogTasks.forEach(trafficHandler::removeCallbacks)
        pendingCallLogTasks.clear()
        callLogExecutor.queue.clear()
        trafficHandler.removeCallbacks(trafficSampler)
        trafficHandler.removeCallbacks(gatewayPhoneStateRecheck)
        downloadBytesPerSecond = 0L; uploadBytesPerSecond = 0L
        generation++
        try {
            serverSocket?.close()
        } catch (e: Exception) {
        }
        closeSocket()
        worker?.interrupt()
        worker = null
        writer = null
        connected = false
        clearPendingCall()
        clearPendingAudioRequests()
        stopRinging()
        verifiedSimSlots = emptySet()
        if (receiverRegistered) {
            try {
                unregisterReceiver(phoneReceiver)
            } catch (e: Exception) {
            }
            receiverRegistered = false
        }
        nm.cancel(ID_INCOMING)
        stopForeground(STOP_FOREGROUND_REMOVE)
        connectionState = "Stopped"
        callState = CALL_IDLE
        activeCallToken = null
        simCallStartedMs = 0L
        callNumber = ""
        callName = ""
        callDirection = ""
        callSimIdentity = null
        callWaitingSeen = false
        multipleActiveSimCalls = false
        callControlsRestricted = false
        simCallHere = false
        simCallOnGateway = false
        simDialAt = 0L
        Hub.log("Stopped")
    }

    private fun send(vararg parts: String) {
        val w = writer
        if (w == null) {
            Hub.log("Not connected – command not sent")
            return
        }
        val msg = parts.joinToString("|") { it.replace('|', '/') }
        if (msg.toByteArray(Charsets.UTF_8).size > Protocol.MAX_FRAME) {
            // The peer would drop the whole link on an oversized frame.
            Hub.log("Not sent: ${parts.firstOrNull()} message is too large")
            return
        }
        try {
            io.execute {
                // Never write commands to a replaced session's old socket.
                if (writer !== w || !connected && !msg.startsWith("HELLO|")) return@execute
                try {
                    w.writeCmd(msg)
                    txTotal.addAndGet(msg.toByteArray(Charsets.UTF_8).size.toLong() + 5L)
                } catch (e: Exception) {
                    Hub.log("Send failed: ${e.javaClass.simpleName}")
                    // An old writer must not close the new Bluetooth session.
                    if (writer === w) closeSocket()
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun handleCommand(msg: String) {
        val p = msg.split("|")
        val cmd = p[0]
        val arg1 = p.getOrElse(1) { "" }
        // Strict SIM-only allowlist; reject unsupported and wrong-role commands.
        if (cmd != "HELLO" && !((role == ROLE_GATEWAY && cmd in Commands.GATEWAY_HANDLES) ||
            (role == ROLE_REMOTE && cmd in Commands.REMOTE_HANDLES))) {
            Hub.log("Ignored $cmd: the ${if (role == ROLE_GATEWAY) "Gateway" else "Remote"} doesn't handle it")
            return
        }
        if (cmd != "HELLO" && (!helloReceived || peerProtocol != Protocol.VERSION)) {
            Hub.log("Ignored $cmd: peer protocol handshake is incomplete or incompatible")
            return
        }
        when (cmd) {
            "HELLO" -> {
                peerProtocol = p.getOrNull(2)?.toIntOrNull()
                peerAppVersion = p.getOrNull(3).orEmpty().take(20)
                helloReceived = CallControlProtocol.peerRoleMatches(role, arg1)
                Hub.log("Peer is the $arg1 phone (PCL ${peerAppVersion.ifEmpty { "older version" }})")
                versionWarning?.let { Hub.log("Version mismatch: $it") }
                if (!helloReceived) {
                    Hub.log("Unexpected Bluetooth peer role; closing connection")
                    closeSocket()
                    return
                }
                if (peerProtocol != Protocol.VERSION) {
                    Hub.log("Incompatible protocol; call commands disabled until both phones update")
                    return
                }
                if (role == ROLE_GATEWAY) sendPendingSimEnrichment()
            }
            // gateway -> remote
            "CALL" -> {
                val previousState = callState
                val nextState = arg1.takeIf { it in setOf(CALL_IDLE, CALL_RINGING, CALL_OFFHOOK) } ?: return
                val nextToken = p.getOrNull(9)?.toLongOrNull()?.takeIf { it > 0L }
                if (nextState != CALL_IDLE && activeCallToken != null && nextToken != null &&
                    activeCallToken != nextToken && pendingCallToken != null) {
                    callControlError = "FAILED: Call changed before Gateway confirmation"
                    Hub.log("Canceled call control for superseded SIM call")
                    clearPendingCall()
                }
                callState = nextState
                activeCallToken = if (nextState == CALL_IDLE) null else nextToken
                val wasRestricted = callControlsRestricted
                callControlsRestricted = p.getOrNull(10) == "1"
                // When Android cannot identify which SIM call is active, discard
                // the uncertain tracker timeline rather than invent a missed call.
                if (callControlsRestricted && !wasRestricted) CallHistory.onLinkLost()
                // Stop the ringtone before potentially slow synchronous call-history I/O.
                if (callState != CALL_RINGING || callControlsRestricted) stopSimRinging()
                if (callState == CALL_RINGING && pendingCallCommand == null) callControlError = null
                callNumber = CallerId.asNumber(p.getOrElse(2) { "" })
                val receivedName = p.getOrElse(3) { "" }.trim()
                // Generic dialer placeholders are not contact names. When the
                // Gateway supplied a number, display that number on the Remote.
                callName = CallerId.asName(receivedName).ifEmpty {
                    if (callNumber.isBlank() && CallerId.isPrivate(receivedName))
                        CallerId.PRIVATE else ""
                }
                if (callNumber.isBlank() && callName.isNotBlank() &&
                    !CallerId.isPrivate(callName)) {
                    callNumber = resolveUniqueContactNumber(callName)
                }
                callDirection = p.getOrElse(4) { "" }
                val slot = p.getOrNull(5)?.toIntOrNull()?.takeIf { it in 0..3 }
                callSimIdentity = slot?.let {
                    SimCallIdentity(it, p.getOrNull(6)?.let(::decodeText).orEmpty(),
                        p.getOrNull(7)?.let(::decodeText).orEmpty())
                }
                availableSimSlots = p.getOrNull(8).orEmpty().split(",")
                    .mapNotNull { it.toIntOrNull()?.takeIf { index -> index in 0..1 } }
                    .toSet()
                val savedCall = if (callControlsRestricted) null else
                    CallHistory.onSimState(callState, callNumber, callName, callSimIdentity,
                        p.getOrNull(9)?.toLongOrNull()?.takeIf { it > 0L })
                // The Gateway's IDLE may arrive before the Reject ACK. Keep the
                // saved Missed candidate for a verified, exactly-matched correction.
                if (pendingReject && !pendingCallAccepted &&
                    previousState == CALL_RINGING && callState == CALL_IDLE &&
                    savedCall?.type == CallHistory.Type.MISSED) {
                    pendingDeclineRecord = savedCall
                }

                onRemoteCallUpdate(previousState)
                confirmCallState()
            }
            "CALL_LOGGED" -> {
                val started = arg1.toLongOrNull() ?: return
                val direction = p.getOrNull(8).orEmpty()
                val id = p.getOrNull(9).orEmpty()
                if (direction !in setOf("INCOMING", "OUTGOING") ||
                    id != "${started}_${direction}") return
                val number = CallerId.asNumber(p.getOrNull(2)?.let(::decodeText))
                val name = p.getOrNull(3)?.let(::decodeText).orEmpty()
                val withheld = p.getOrNull(4) == "1"
                val slot = p.getOrNull(5)?.toIntOrNull()?.takeIf { it in 0..3 }
                val sim = slot?.let {
                    SimCallIdentity(it, p.getOrNull(6)?.let(::decodeText).orEmpty(),
                        p.getOrNull(7)?.let(::decodeText).orEmpty())
                }
                if (CallHistory.amendSim(started, number, name, withheld, sim, direction) != null) {
                    send("CALL_LOG_ACK", id)
                    Hub.log("Verified SIM call history updated")
                }
                Hub.notifyUi()
            }
            "CALL_LOG_ACK" -> {
                if (role == ROLE_GATEWAY) PendingSimEnrichment.acknowledge(this, arg1)
            }
            // remote -> gateway
            "ANSWER" -> {
                callControlError = null
                if (gatewayTokenMatches(p.getOrNull(2))) gatewayAnswer()
                else callControlError = "FAILED: Incoming call changed before Answer arrived"
                sendCallResult(p.getOrNull(1).orEmpty())
            }
            "REJECT", "HANGUP" -> {
                callControlError = null
                if (gatewayTokenMatches(p.getOrNull(2))) gatewayEnd(rejectRinging = cmd == "REJECT")
                else callControlError = "FAILED: SIM call changed before $cmd arrived"
                sendCallResult(p.getOrNull(1).orEmpty())
            }
            "DIAL" -> { callControlError = null; gatewayDial(arg1); sendCallResult(p.getOrNull(2).orEmpty()) }
            "DIAL_SIM" -> {
                callControlError = null
                val slot = p.getOrNull(2)?.toIntOrNull()?.takeIf { it in 0..1 }
                if (slot == null) callControlError = "FAILED: Invalid SIM slot"
                else gatewayDial(arg1, slot)
                sendCallResult(p.getOrNull(3).orEmpty())
            }
            "SYNC_CONTACTS" -> sendContacts()
            "AUDIO_QUERY" -> gatewayAudioStatus()
            "AUDIO_MODE" -> gatewaySetVolumeMode(arg1, p.getOrNull(2).orEmpty())
            "AUDIO_VOLUME" -> gatewaySetAudioVolume(arg1, p.getOrNull(2).orEmpty())
            "AUDIO_STEP" -> gatewayAudioStep(arg1, p.getOrNull(2).orEmpty())
            "AUDIO_ROUTE" -> gatewaySetAudioRoute(arg1, p.getOrNull(2).orEmpty())
            "AUDIO_RESULT" -> {
                val id = p.getOrNull(3).orEmpty()
                val outcome = p.getOrNull(2).orEmpty()
                if (arg1 == "ROUTE" && outcome == "PENDING") {
                    Hub.log("Gateway audio route pending verification")
                    return
                }
                val timeout = pendingAudioRequests.remove(id)
                if (timeout != null) {
                    audioTimeoutHandler.removeCallbacks(timeout)
                    Hub.log("Gateway audio result: ${arg1} — $outcome")
                } else {
                    Hub.log("Ignoring stale audio acknowledgement")
                }
            }
            "AUDIO_STATUS" -> {
                gatewayVolume = (arg1.toIntOrNull() ?: 0).coerceAtLeast(0)
                gatewayMaxVolume = (p.getOrNull(2)?.toIntOrNull() ?: 1).coerceAtLeast(1)
                gatewayAudioRoute = p.getOrNull(3).orEmpty().takeIf { it in setOf("SPEAKER", "EARPIECE", "BLUETOOTH", "WIRED_HEADSET") } ?: "UNKNOWN"
                gatewayVolumeMode = p.getOrNull(4).orEmpty().takeIf { it in volumeModes } ?: "AUTO"
            }
            "CALL_RESULT" -> {
                val request = p.getOrNull(2)?.toIntOrNull()
                if (!CallControlProtocol.matchesResult(callControlRequest,
                        pendingCallCommand, p.getOrNull(2))) {
                    Hub.log("Ignored stale or uncorrelated call-control acknowledgement")
                    return
                }
                val detail = p.drop(3).joinToString("|")
                if (arg1 == "FAILED") {
                    callControlError = "FAILED: $detail"
                    clearPendingCall()
                    Hub.log("Gateway call control failed: $detail")
                } else {
                    pendingCallAccepted = true
                    // The CALL state may reach the Remote before its correlated ACK.
                    // Claim ownership only once both conditions are true.
                    if (remoteOwnsPendingCall()) {
                        simCallHere = true
                        simCallOnGateway = false
                        showOngoingCall()
                    }
                    if (pendingReject) {
                        if (callState == CALL_RINGING) CallHistory.onDeclinedHere()
                        else pendingDeclineRecord?.let { CallHistory.confirmDeclined(it) }
                    }
                    Hub.log("Gateway accepted call-control request #$request; awaiting call state")
                    callControlError = "PENDING: Waiting for confirmed phone state"
                    confirmCallState()
                }
            }
            // contacts: gateway -> remote
            "CONTACTS_HASH" -> {
                expectedContactHash = arg1.takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) }
                val have = Prefs.of(this).getString(Prefs.CONTACTS_HASH, null)
                if (have == arg1) {
                    Hub.log("Contacts are up to date with the Gateway")
                } else {
                    send("SYNC_CONTACTS")
                    Hub.log("Gateway contacts changed; syncing ${p.getOrElse(2) { "" }} contacts")
                }
            }
            "CONTACTS_BEGIN" -> contactBuf = StringBuilder()
            "CONTACTS" -> {
                val buf = contactBuf
                val chunk = msg.substringAfter('|')
                if (buf != null && buf.length + chunk.length > MAX_CONTACT_CHARS) {
                    contactBuf = null
                    Hub.log("Contact sync stopped: the list is too large")
                } else {
                    buf?.append(chunk)
                }
            }
            "CONTACTS_END" -> {
                val data = contactBuf?.toString()
                contactBuf = null
                val expectedHash = expectedContactHash
                if (data == null) {
                    Hub.log("Contact synchronization incomplete; existing contacts retained")
                } else if (expectedHash == null) {
                    Hub.log("Contact synchronization missing a verified checksum; existing contacts retained")
                } else {
                    val session = generation
                    // Hash verification and fsync may be expensive for large address
                    // books. Never block the socket reader while persisting contacts.
                    try {
                        contactsExecutor.execute {
                            if (!running || generation != session || role != ROLE_REMOTE) return@execute
                            val hash = ContactStore.fingerprint(data)
                            if (!hash.equals(expectedHash, ignoreCase = true)) {
                                Hub.log("Contact synchronization checksum mismatch; existing contacts retained")
                            } else if (ContactStore.saveSynced(this, data)) {
                                // Commit the fingerprint only after the full contact file.
                                if (Prefs.of(this).edit().putString(Prefs.CONTACTS_HASH, hash).commit()) {
                                    Hub.contactsVersion++
                                    Hub.log("Received ${ContactStore.parse(data).size} contacts from gateway")
                                } else {
                                    Hub.log("Contact snapshot saved; fingerprint update deferred")
                                }
                            }
                        }
                    } catch (_: RejectedExecutionException) {
                        Hub.log("Contact synchronization canceled during shutdown")
                    }
                }
            }
            "CONTACTS_RESULT" -> {
                val detail = p.drop(2).joinToString("|")
                if (arg1 == "OK") Hub.log("Gateway contact sync completed: $detail contacts sent")
                else Hub.log("Contact sync failed: $detail")
            }
            else -> Hub.log("Unknown command: $cmd")
        }
        Hub.notifyUi()
    }

    private fun gatewayTokenMatches(raw: String?): Boolean =
        SimCallTokenPolicy.matches(simCallStartedMs, raw)

    private fun sendCallResult(requestId: String) {
        val error = callControlError
        send("CALL_RESULT", if (error == null) "OK" else "FAILED", requestId,
            error?.removePrefix("FAILED: ") ?: "Android accepted the request")
    }

    private fun setConn(isConnected: Boolean, text: String) {
        connected = isConnected
        connectionState = text
        Hub.log(text)
        updateStatus()
    }

    // ---------------------------------------------------------------- gateway: SIM calls

    @Volatile private var simCallStartedMs = 0L

    fun simCallStartedAt(): Long = simCallStartedMs

    private fun sendPendingSimEnrichment() {
        if (!connected || writer == null || role != ROLE_GATEWAY ||
            peerProtocol == null || peerProtocol!! < 9) return
        PendingSimEnrichment.snapshot(this).forEach { entry ->
            send("CALL_LOGGED", entry.startedAt.toString(),
                encodeText(entry.number), encodeText(entry.name),
                if (entry.privateCaller) "1" else "0",
                entry.simSlot?.toString().orEmpty(),
                encodeText(entry.simName), encodeText(entry.carrier),
                entry.direction, entry.id)
        }
    }

    /** One bounded lookup with one retry; the phone's CallLog provider must
     *  never block the Bluetooth writer/receiver, even if it hangs for seconds.
     */
    private fun enrichCompletedSimCall(
        startedAt: Long, direction: String, number: String, sim: SimCallIdentity?,
    ) {
        if (!running || role != ROLE_GATEWAY || !OptionalSimCallLog.canRead(this) ||
            startedAt <= 0L || direction !in setOf("INCOMING", "OUTGOING")) return
        val currentGeneration = generation
        fun schedule(delayMs: Long, retry: Boolean) {
            lateinit var task: Runnable
            task = Runnable {
                pendingCallLogTasks.remove(task)
                if (!running || generation != currentGeneration ||
                    role != ROLE_GATEWAY || !OptionalSimCallLog.canRead(this)) return@Runnable
                try {
                    callLogExecutor.execute {
                        if (!running || generation != currentGeneration) return@execute
                        val match = OptionalSimCallLog.read(this, startedAt, direction, number, sim)
                        trafficHandler.post {
                            if (!running || generation != currentGeneration) return@post
                            if (match == null) {
                                if (retry) schedule(10_000L, false)
                                return@post
                            }
                            val entry = PendingSimEnrichment.Entry(
                                startedAt, direction, match.number, match.name,
                                match.privateCaller, match.sim?.slotIndex,
                                match.sim?.displayName.orEmpty(),
                                match.sim?.carrier.orEmpty(), System.currentTimeMillis())
                            PendingSimEnrichment.add(this, entry)
                            sendPendingSimEnrichment()
                            Hub.log("SIM call history details queued for reliable Remote sync")
                        }
                    }
                } catch (_: RejectedExecutionException) {
                    Hub.log("Optional call history queue full; active call controls unaffected")
                }
            }
            pendingCallLogTasks.add(task)
            trafficHandler.postDelayed(task, delayMs)
        }
        schedule(2_500L, true)
    }



    /** A direct phone-state read supplements missing or delayed OEM broadcasts. */
    @SuppressLint("MissingPermission")
    private fun currentGatewayPhoneState(): String? {
        if (!has(Manifest.permission.READ_PHONE_STATE)) return null
        return try {
            @Suppress("DEPRECATION")
            when (getSystemService(TelephonyManager::class.java)?.callState) {
                TelephonyManager.CALL_STATE_RINGING -> CALL_RINGING
                TelephonyManager.CALL_STATE_OFFHOOK -> CALL_OFFHOOK
                TelephonyManager.CALL_STATE_IDLE -> CALL_IDLE
                else -> null
            }
        } catch (_: Exception) { null }
    }

    private val phoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val st = i.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            @Suppress("DEPRECATION")
            val num = i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            val next = when (st) {
                TelephonyManager.EXTRA_STATE_RINGING -> CALL_RINGING
                TelephonyManager.EXTRA_STATE_OFFHOOK -> CALL_OFFHOOK
                TelephonyManager.EXTRA_STATE_IDLE -> CALL_IDLE
                else -> return
            }
            // A delayed RINGING broadcast must not restart ringing after a confirmed answer.
            if (next == CALL_RINGING &&
                GatewayRingStateSync.isStaleRingingAfterAnswer(callState, currentGatewayPhoneState())) {
                Hub.log("Ignored stale ringing broadcast after SIM call connected")
                return
            }
            applyGatewayPhoneState(next, num)
        }
    }

    /** One state-transition path for both Android broadcasts and the ringing watchdog. */
    private fun applyGatewayPhoneState(newState: String, num: String?) {
        val previousState = callState
        if (SimCallConcurrencyPolicy.isWaiting(previousState, newState)) {
            if (!callWaitingSeen) {
                callWaitingSeen = true
                updateControlRestriction()
                Hub.log("SIM call waiting detected; Remote call controls disabled until idle")
                sendCallState()
            }
            scheduleGatewayPhoneStateRecheck()
            return
        }
        if (newState == CALL_IDLE) {
            callWaitingSeen = false
            multipleActiveSimCalls = false
            updateControlRestriction()
        }
        val priorStart = simCallStartedMs
        val priorDirection = callDirection
        val priorNumber = callNumber
        val priorSim = callSimIdentity
        if (newState == CALL_RINGING && previousState == CALL_IDLE) {
            simCallStartedMs = System.currentTimeMillis()
            callDirection = "INCOMING"
            callNumber = ""
            callName = ""
        } else if (newState == CALL_OFFHOOK && previousState == CALL_IDLE) {
            simCallStartedMs = System.currentTimeMillis()
            callDirection = "OUTGOING"
        } else if (newState == CALL_IDLE) {
            simCallStartedMs = 0L
            callDirection = ""
        }
        val gotNumber = !num.isNullOrEmpty() && CallerId.asNumber(num) != callNumber
        if (!num.isNullOrEmpty()) callNumber = CallerId.asNumber(num)
        val changed = newState != previousState
        if (changed || callSimIdentity == null) refreshPhoneDetails(newState, callNumber)
        if (changed && newState == CALL_RINGING) callControlError = null
        callState = newState
        scheduleGatewayPhoneStateRecheck()
        if (changed || gotNumber) {
            Hub.log("Phone call: $newState (${logCaller()})")
            sendCallState(if (newState == CALL_IDLE) priorStart else null)
        }
        if (newState == CALL_IDLE) {
            if (changed && priorStart > 0L) {
                enrichCompletedSimCall(priorStart, priorDirection, priorNumber, priorSim)
            }
            callNumber = ""
            callName = ""
            callSimIdentity = null
        }
        Hub.notifyUi()
    }

    /** Android may omit EXTRA_INCOMING_NUMBER; use only the matching SIM call-log row. */
    @SuppressLint("MissingPermission")
    private fun recoverRecentSimNumber(started: Long, ended: Long, direction: String) {
        if (!has(Manifest.permission.READ_CALL_LOG) || started <= 0L) return
        val expectedType = if (direction == "OUTGOING") CallLog.Calls.OUTGOING_TYPE else null
        try {
            contentResolver.query(CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE),
                "${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.DATE} <= ?",
                arrayOf((started - 3000L).toString(), (ended + 3000L).toString()),
                "${CallLog.Calls.DATE} DESC")?.use { cursor ->
                while (cursor.moveToNext()) {
                    val type = cursor.getInt(1)
                    if (expectedType != null && type != expectedType) continue
                    if (expectedType == null && type !in setOf(CallLog.Calls.INCOMING_TYPE,
                            CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE)) continue
                    val number = cursor.getString(0).orEmpty()
                    if (!RecentCallNumber.isDialable(number)) continue
                    callNumber = number
                    callName = lookupName(number)
                    Hub.log("Recovered SIM caller number from matching call log")
                    return
                }
            }
        } catch (e: Exception) {
            Hub.log("SIM call-log recovery unavailable: ${e.javaClass.simpleName}")
        }
    }

    private fun registerPhoneReceiver() {
        if (receiverRegistered) return
        val f = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(phoneReceiver, f, Context.RECEIVER_EXPORTED)
            else registerReceiver(phoneReceiver, f)
            receiverRegistered = true
            // Some devices do not redeliver the current phone state on registration.
            syncCurrentPhoneState()
        } catch (e: SecurityException) {
            Hub.log("Phone-state monitoring blocked by Android: ${e.message ?: "permission denied"}")
        } catch (e: RuntimeException) {
            Hub.log("Could not register phone-state monitoring: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun syncCurrentPhoneState() {
        try {
            val tm = getSystemService(TelephonyManager::class.java) ?: return
            @Suppress("DEPRECATION")
            val current = when (tm.callState) {
                TelephonyManager.CALL_STATE_RINGING -> CALL_RINGING
                TelephonyManager.CALL_STATE_OFFHOOK -> CALL_OFFHOOK
                else -> CALL_IDLE
            }
            refreshPhoneDetails(current)
            if (current != callState) {
                if (current != CALL_IDLE && simCallStartedMs <= 0L)
                    simCallStartedMs = System.currentTimeMillis()
                callState = current
                // A snapshot cannot reliably identify call direction or caller number.
                callDirection = ""
                if (current == CALL_IDLE) { callNumber = ""; callName = "" }
                Hub.log("Initial phone state: $current (details may be unavailable)")
                sendCallState()
            }
            scheduleGatewayPhoneStateRecheck()
        } catch (e: Exception) {
            Hub.log("Initial phone-state query unavailable: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Read potentially slow telecom and contact providers off the broadcast thread. */
    private fun refreshPhoneDetails(state: String, number: String = callNumber) {
        if (role != ROLE_GATEWAY || !running) return
        val gen = generation
        try {
            phoneDataExecutor.execute {
                val sim = SimCallDetector.identify(this, state)
                val concurrent = SimCallDetector.hasMultipleBusySubscriptions(this)
                val name = if (number.isNotBlank()) lookupName(number) else ""
                val slots = SimDialAccounts.availableSlots(this)
                trafficHandler.post {
                    if (!running || generation != gen || role != ROLE_GATEWAY) return@post
                    verifiedSimSlots = slots
                    if (callState == state) {
                        var changed = false
                        if (multipleActiveSimCalls != concurrent) {
                            multipleActiveSimCalls = concurrent
                            updateControlRestriction()
                            changed = true
                        }
                        if (state != CALL_IDLE && callNumber == number) {
                            if (sim != null && callSimIdentity != sim) {
                                callSimIdentity = sim
                                changed = true
                            }
                            if (name.isNotBlank() && callName != name) {
                                callName = name
                                changed = true
                            }
                        }
                        // Send verified available SIMs after the provider query completes.
                        if (changed || writer != null) sendCallState()
                    }
                }
            }
        } catch (_: RejectedExecutionException) { /* Service is shutting down. */ }
    }

    /** Gateway: enrich an active SIM call using metadata from the default dialer's notification. */
    private fun numbersMatch(first: String, second: String): Boolean {
        val a = ContactStore.digits(first)
        val b = ContactStore.digits(second)
        return a.isNotBlank() && b.isNotBlank() &&
            (a == b || (a.length >= 10 && b.length >= 10 &&
                (a.endsWith(b) || b.endsWith(a))))
    }

    fun updateCallerFromNotification(number: String, name: String) {
        if (role != ROLE_GATEWAY || callState == CALL_IDLE) return
        val safeNumber = number.trim().take(80)
        val safeName = name.trim().take(120)
        if (safeNumber.isEmpty() && safeName.isEmpty()) return
        if (safeNumber.isNotEmpty() && callNumber.isNotEmpty() &&
            !numbersMatch(callNumber, safeNumber)) return
        var changed = false
        if (callNumber.isEmpty() && safeNumber.isNotEmpty()) {
            callNumber = safeNumber
            changed = true
        }
        if (callName.isEmpty() && safeName.isNotEmpty() &&
            safeName.lowercase() !in setOf("unknown", "unknown number", "incoming call",
                "ongoing call", "phone", "private number", "no caller id")) {
            callName = safeName
            changed = true
        }
        if (callNumber.isEmpty() && callName.isNotBlank()) {
            val matched = resolveUniqueContactNumber(callName)
            if (matched.isNotBlank()) {
                callNumber = matched
                changed = true
                Hub.log("Caller number inferred from a unique contact match")
            }
        }
        if (changed) {
            Hub.log("Caller details recovered from phone notification")
            sendCallState()
            Hub.notifyUi()
        }
    }

    /**
     * Resolve a caller label from the default dialer's notification.
     * Prefer an exact full-name match, then a uniquely matching normalized label.
     * Never infer a number from a partial name or an ambiguous set of contacts.
     */
    private fun resolveUniqueContactNumber(name: String): String {
        if (name.isBlank() || !has(Manifest.permission.READ_CONTACTS)) return ""
        fun normalize(value: String): String = java.text.Normalizer
            .normalize(value, java.text.Normalizer.Form.NFKC)
            .trim().replace(Regex("\\s+"), " ")
            .lowercase(java.util.Locale.ROOT)
        val target = normalize(name)
        if (target.length < 2 || target in setOf("unknown", "unknown number",
                "private number", "incoming call", "ongoing call", "phone",
                "restricted", "withheld", "no caller id")) return ""
        val contacts = ContactStore.readDevice(this)
        val exact = contacts.filter { it.name.trim().equals(name.trim(), ignoreCase = true) }
        val matches = if (exact.isNotEmpty()) exact else contacts.filter {
            normalize(it.name) == target
        }
        val numbers = matches.map { ContactStore.digits(it.number) }
            .filter { it.isNotBlank() }.distinct()
        if (numbers.size > 1) Hub.log("Caller label matches multiple contact numbers; leaving number unknown")
        return if (numbers.size == 1) matches.first().number else ""
    }

    // Encode optional SIM labels as URL-safe Base64 to avoid protocol field delimiters.
    private fun encodeText(value: String): String =
        android.util.Base64.encodeToString(value.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)

    private fun decodeText(value: String): String = try {
        String(android.util.Base64.decode(value,
            android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE), Charsets.UTF_8)
    } catch (_: Exception) { "" }

    private fun sendCallState(completedStart: Long? = null) {
        if (writer != null) {
            val sim = callSimIdentity
            send("CALL", callState, callNumber, callName, callDirection,
                sim?.slotIndex?.toString().orEmpty(), encodeText(sim?.displayName.orEmpty()),
                encodeText(sim?.carrier.orEmpty()),
                verifiedSimSlots.sorted().joinToString(","),
                (completedStart ?: simCallStartedMs).takeIf { it > 0L }?.toString().orEmpty(),
                if (callControlsRestricted) "1" else "0")
        }
    }

    @SuppressLint("MissingPermission")
    private fun gatewayDial(number: String, selectedSlot: Int? = null) {
        if (callControlsRestricted) {
            callControlError = "FAILED: Concurrent SIM calls must be handled on the Gateway"
            return
        }
        val n = CallerId.asNumber(number)
        if (n.isEmpty()) { callControlError = "FAILED: Invalid SIM phone number"; return }
        if (!has(Manifest.permission.CALL_PHONE)) {
            callControlError = "FAILED: Phone permission missing on Gateway"
            return
        }
        try {
            // Default and selected-SIM dial requests share the same call-state guard.
            // A delayed Bluetooth request must not start a second call unexpectedly.
            if (!GatewayCallCommandPolicy.allows("DIAL", currentGatewayPhoneState() ?: callState)) {
                callControlError = "FAILED: A SIM call is already active"
                return
            }
            val extras = Bundle()
            if (selectedSlot != null) {
                val selection = SimDialAccounts.select(this, selectedSlot)
                val handle = selection.handle
                if (handle == null) {
                    callControlError = "FAILED: SIM ${selectedSlot + 1} cannot be verified (${selection.reason})"
                    Hub.log("SIM selection rejected: ${selection.reason}")
                    return
                }
                extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
            }
            val previousNumber = callNumber
            val previousName = callName
            val previousDirection = callDirection
            callNumber = n
            // Contact and SIM provider lookups must not block the Bluetooth reader.
            // Once the call starts, refreshPhoneDetails() fills in the contact name.
            callName = ""
            callDirection = "OUTGOING"
            try {
                getSystemService(TelecomManager::class.java)!!
                    .placeCall(Uri.fromParts("tel", n, null), extras)
            } catch (e: Exception) {
                callNumber = previousNumber
                callName = previousName
                callDirection = previousDirection
                throw e
            }
            callControlError = null
            Hub.log("Dialing ${logCaller(n)}")
        } catch (e: Exception) {
            callControlError = "FAILED: Android rejected the dial request"
            Hub.log("SIM dial failed: ${e.javaClass.simpleName}")
        }
    }

    private fun lookupName(number: String): String {
        if (!has(Manifest.permission.READ_CONTACTS)) return ""
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)
            )
            contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) ?: "" else "" } ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun gatewayAnswer() {
        if (callControlsRestricted) {
            callControlError = "FAILED: Concurrent SIM calls must be handled on the Gateway"
            return
        }
        if (!GatewayCallCommandPolicy.allows("ANSWER", currentGatewayPhoneState() ?: callState)) {
            callControlError = "FAILED: No ringing SIM call to answer"
            return
        }
        if (!has(Manifest.permission.ANSWER_PHONE_CALLS)) {
            callControlError = "FAILED: Phone permission missing on Gateway"
            return
        }
        try {
            getSystemService(TelecomManager::class.java)!!.acceptRingingCall()
            callControlError = null
            Hub.log("SIM answer requested through Android Telecom")
        } catch (e: Exception) {
            callControlError = "FAILED: ${e.message ?: "Android rejected the answer request"}"
            Hub.log("SIM answer failed: ${e.javaClass.simpleName}")
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun gatewayEnd(rejectRinging: Boolean = false) {
        if (callControlsRestricted) {
            callControlError = "FAILED: Concurrent SIM calls must be handled on the Gateway"
            return
        }
        val observed = currentGatewayPhoneState() ?: callState
        if (rejectRinging && !GatewayCallCommandPolicy.allows("REJECT", observed)) {
            callControlError = "FAILED: No ringing SIM call to reject"
            return
        }
        if (!rejectRinging && !GatewayCallCommandPolicy.allows("HANGUP", observed)) {
            callControlError = "FAILED: No connected SIM call to hang up"
            return
        }
        if (Build.VERSION.SDK_INT < 28) {
            callControlError = "FAILED: Ending SIM calls requires Android 9 or newer"
            return
        }
        if (!has(Manifest.permission.ANSWER_PHONE_CALLS)) {
            callControlError = "FAILED: Phone permission missing on Gateway"
            return
        }
        try {
            val ok = getSystemService(TelecomManager::class.java)!!.endCall()
            callControlError = if (ok) null else "FAILED: Android did not end the SIM call"
            Hub.log(if (ok) "SIM call end requested" else "Android rejected SIM call end")
        } catch (e: Exception) {
            callControlError = "FAILED: ${e.message ?: "Android rejected the end-call request"}"
            Hub.log("SIM call end failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * How a caller appears in logs: never the full number or the contact name, because the
     * activity log can be copied and shared, and Logcat is readable over USB debugging.
     */
    private fun logCaller(number: String = callNumber): String {
        val digits = number.filter { it.isDigit() }
        return when {
            digits.length >= 2 -> "number ending ${digits.takeLast(2)}"
            callName.isNotEmpty() -> "a saved contact"
            else -> "unknown number"
        }
    }

    fun display(): String = when {
        callName.isNotEmpty() && callNumber.isNotEmpty() -> "$callName ($callNumber)"
        callName.isNotEmpty() -> callName
        callNumber.isNotEmpty() -> callNumber
        else -> "Unknown number"
    }

    // ---------------------------------------------------------------- remote: ringing

    /**
     * Remote: the Gateway's SIM call changed. This phone rings for every incoming call, but
     * once connected it keeps the call controls visible even if the Gateway answered,
     * so volume adjustments remain accessible from the Remote.
     */
    private fun onRemoteCallUpdate(previous: String) {
        when (callState) {
            CALL_RINGING -> {
                if (previous != CALL_RINGING) {
                    simCallHere = false
                    simCallOnGateway = false
                    simDialAt = 0L
                    Hub.log("Incoming call (${logCaller()})")
                    if (!callControlsRestricted) startRinging()
                }
                if (callControlsRestricted) stopSimRinging()
                showIncoming()
            }
            CALL_OFFHOOK -> {
                stopSimRinging()
                if (remoteOwnsPendingCall()) simCallHere = true
                // The dial marker is cleared by confirmed ACK+OFFHOOK, failure,
                // call end or disconnect; not by an unrelated OFFHOOK event.
                if (simCallHere) {
                    simCallOnGateway = false
                    showOngoingCall()
                    if (previous != CALL_OFFHOOK) Hub.log("Call in progress on gateway (${logCaller()})")
                } else {
                    simCallOnGateway = true
                    showOngoingCall()
                    if (previous != CALL_OFFHOOK) {
                        Hub.log(if (previous == CALL_RINGING) "Call answered on the Gateway" else "Call placed on the Gateway")
                    }
                }
            }
            else -> {
                simCallHere = false
                simCallOnGateway = false
                simDialAt = 0L
                stopSimRinging()
                nm.cancel(ID_INCOMING)
                if (previous != CALL_IDLE) Hub.log("Call ended")
            }
        }
        updateStatus()
    }

    private fun showOngoingCall() {
        // Reuse the incoming-call notification ID so the Remote has one call notification,
        // updated in place when the Gateway call is answered or connects.
        val connectedAt = CallHistory.connectedSince
        val n = Notification.Builder(this, CH_CALLS)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("Call in progress")
            .setContentText(display())
            .setSubText("on the Gateway")
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setUsesChronometer(connectedAt > 0L)
            .setWhen(if (connectedAt > 0L) System.currentTimeMillis() -
                (SystemClock.elapsedRealtime() - connectedAt).coerceAtLeast(0L)
                else System.currentTimeMillis())
            .setContentIntent(openCallsPi())
            .addAction(action(android.R.drawable.ic_media_previous, "Volume −", actionPi(ACTION_AUDIO_DOWN, 31)))
            .addAction(action(android.R.drawable.ic_media_next, "Volume +", actionPi(ACTION_AUDIO_UP, 32)))
            .addAction(action(android.R.drawable.ic_lock_silent_mode_off, "Speaker / Earpiece", actionPi(ACTION_AUDIO_SPEAKER, 33)))
            .apply {
                if (!callControlsRestricted) addAction(action(android.R.drawable.ic_menu_close_clear_cancel,
                    "End call", actionPi(ACTION_HANGUP, 13)))
            }
            .build()
        try {
            nm.notify(ID_INCOMING, n)
        } catch (e: Exception) {
            Hub.log("Could not show ongoing-call notification: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun startRinging() {
        try {
            if (ringtone == null) {
                val r = RingtoneManager.getRingtone(
                    this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                )
                if (r != null && Build.VERSION.SDK_INT >= 28) r.isLooping = true
                r?.play()
                ringtone = r
            }
        } catch (e: Exception) {
            Hub.log("Ringtone error: ${e.message}")
        }
        try {
            vibrator()?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 800), 0))
        } catch (e: Exception) {
        }
    }

    private fun stopSimRinging() = stopRinging()

    private fun stopRinging() {
        try {
            ringtone?.stop()
        } catch (e: Exception) {
        }
        ringtone = null
        try {
            vibrator()?.cancel()
        } catch (e: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrator(): Vibrator? = getSystemService(Vibrator::class.java)

    // ---------------------------------------------------------------- contacts sync

    /**
     * Gateway, on connect: send only a fingerprint of the contact list. The Remote asks for
     * the full list only when it differs from its saved copy, instead of on every reconnect.
     */
    private fun sendContactsFingerprint() {
        if (!has(Manifest.permission.READ_CONTACTS)) {
            Hub.log("Contacts permission missing – Remote keeps its saved contacts")
            return
        }
        val gen = generation
        val w = writer ?: return
        try {
            contactsExecutor.execute {
                val list = ContactStore.readDevice(this)
                val data = ContactStore.toChunks(list).joinToString("")
                if (running && gen == generation && writer === w) {
                    send("CONTACTS_HASH", ContactStore.fingerprint(data), list.size.toString())
                }
            }
        } catch (_: RejectedExecutionException) { /* Quitting. */ }
    }

    /** Gateway: send the full contact list (on request from the Remote). */
    private fun sendContacts() {
        val w = writer
        if (w == null || !connected) {
            Hub.log("Contact sync unavailable: Bluetooth is disconnected")
            return
        }
        if (!has(Manifest.permission.READ_CONTACTS)) {
            send("CONTACTS_RESULT", "FAILED", "Contacts permission is not granted on Gateway")
            return
        }
        val gen = generation
        try {
            contactsExecutor.execute {
                val list = ContactStore.readDevice(this)
                val frames = listOf("CONTACTS_BEGIN") +
                    ContactStore.toChunks(list).map { "CONTACTS|$it" } +
                    listOf("CONTACTS_END", "CONTACTS_RESULT|OK|${list.size}")
                if (!running || gen != generation || writer !== w) return@execute
                // Yield the serial writer after two frames; urgent ANSWER, REJECT,
                // HANGUP and DIAL requests can be queued between contact batches.
                val cursor = frames.iterator()
                val sender = object : Runnable {
                    override fun run() {
                        if (!running || gen != generation || writer !== w) return
                        try {
                            repeat(2) {
                                if (!cursor.hasNext()) return@repeat
                                val frame = cursor.next()
                                w.writeCmd(frame)
                                txTotal.addAndGet(frame.toByteArray(Charsets.UTF_8).size.toLong() + 5L)
                            }
                            if (cursor.hasNext()) io.execute(this)
                            else Hub.log("Sent ${list.size} contacts to Remote")
                        } catch (e: Exception) {
                            Hub.log("Contact sync failed: ${e.javaClass.simpleName}")
                            if (writer === w) closeSocket()
                        }
                    }
                }
                try { io.execute(sender) }
                catch (_: RejectedExecutionException) { /* Quitting. */ }
            }
        } catch (_: RejectedExecutionException) { /* Quitting. */ }
    }

    // ---------------------------------------------------------------- notifications

    private fun createChannels() {
        // Channels from before the rename; Android keeps them until deleted.
        LEGACY_CHANNELS.forEach { runCatching { nm.deleteNotificationChannel(it) } }
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Connection status", NotificationManager.IMPORTANCE_LOW)
        )
        val calls = NotificationChannel(CH_CALLS, "Incoming calls", NotificationManager.IMPORTANCE_HIGH)
        calls.setSound(null, null)
        calls.enableVibration(false)
        nm.createNotificationChannel(calls)
    }

    /** Opens PCL on the Calls tab, where the live call has its buttons. */
    private fun incomingCallPi(): PendingIntent = PendingIntent.getActivity(
        this, 24,
        Intent(this, IncomingCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun openCallsPi(): PendingIntent = PendingIntent.getActivity(
        this, 23,
        Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_OPEN_TAB, "calls"),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun openAppPi(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun actionPi(action: String, req: Int): PendingIntent = PendingIntent.getService(
        this, req,
        Intent(this, LinkService::class.java).setAction(action)
            // Make each call's PendingIntent distinct. A stale notification must not
            // inherit the next call's updated extras through FLAG_UPDATE_CURRENT.
            .setData(Uri.parse("pcl-sim-call:${activeCallToken ?: 0L}/$action"))
            .putExtra(EXTRA_CALL_TOKEN, activeCallToken ?: 0L),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun action(icon: Int, title: String, pi: PendingIntent): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(this, icon), title, pi).build()

    private fun formatTrafficRate(bytesPerSecond: Long): String = when {
        bytesPerSecond >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MB/s", bytesPerSecond / (1024.0 * 1024.0))
        bytesPerSecond >= 1024L -> String.format(java.util.Locale.US, "%.1f KB/s", bytesPerSecond / 1024.0)
        else -> "$bytesPerSecond B/s"
    }

    private fun statusNotification(): Notification {
        val text = if (connected) "$connectionState · Down ${formatTrafficRate(downloadBytesPerSecond)} / Up ${formatTrafficRate(uploadBytesPerSecond)}" else "$connectionState · Down/Up rates unavailable while disconnected"
        return Notification.Builder(this, CH_STATUS)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle(if (role == ROLE_GATEWAY) "PCL · Gateway" else "PCL · Remote")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppPi())
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Quit", actionPi(ACTION_STOP, 10)))
            .build()
    }

    private fun goForeground() {
        val n = statusNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(ID_STATUS, n)
        }
    }

    private fun updateStatus() {
        if (running) {
            try {
                nm.notify(ID_STATUS, statusNotification())
            } catch (e: Exception) {
            }
        }
        Hub.notifyUi()
    }

    /** Remote SIM call notification. */
    private fun showIncoming() {
        if (callControlsRestricted) {
            nm.cancel(ID_INCOMING)
            return
        }
        val n = Notification.Builder(this, CH_CALLS)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("Incoming call")
            .setContentText(display())
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(openCallsPi())
            .setFullScreenIntent(incomingCallPi(), true)
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Reject", actionPi(ACTION_REJECT, 11)))
            .addAction(action(android.R.drawable.sym_action_call, "Answer", actionPi(ACTION_ANSWER, 12)))
            .build()
        try {
            nm.notify(ID_INCOMING, n)
        } catch (e: Exception) {
        }
    }

    private fun has(perm: String) =
        checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
}
