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
            val actual = gatewayRoute(audio)
            val fallback = false
            val verifiedRoute = gatewayRoute(audio)
            val outcome = when {
                verifiedRoute == route -> "APPLIED"
                fallback -> "FALLBACK_QUEUED"
                else -> "NOT_APPLIED"
            }
            Hub.log("Gateway audio route: requested=$route, actual=$verifiedRoute, outcome=$outcome")
            send("AUDIO_RESULT", "ROUTE", outcome, requestId)
            if (fallback) verifyGatewayRouteLater(route, requestId)
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
    private var callSimIdentity: SimCallIdentity? = null
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

    private var callControlRequest = 0

    @Volatile private var running = false
    @Volatile private var generation = 0
    @Volatile private var deviceAddress: String? = null
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var writer: FrameWriter? = null
    private var worker: Thread? = null
    private val io = Executors.newSingleThreadExecutor()
    private var receiverRegistered = false
    private var ringtone: Ringtone? = null

    /** Remote: this phone answered or placed the current SIM call, so it shows it until it ends. */
    @Volatile private var simCallHere = false
    private var simDialAt = 0L

    /** Remote: the current SIM call was answered or placed on the Gateway, so this phone stays quiet. */
    @Volatile var simCallOnGateway = false
        private set
    private var contactBuf: StringBuilder? = null

    val isActive: Boolean get() = running

    private val nm: NotificationManager by lazy { getSystemService(NotificationManager::class.java)!! }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Hub.service = this
        CallHistory.init(this)
        createChannels()
    }

    override fun onDestroy() {
        shutdown()
        io.shutdownNow()
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
            ACTION_ANSWER -> if (running) answer() else stopSelf()
            ACTION_REJECT -> if (running) reject() else stopSelf()
            ACTION_HANGUP -> if (running) hangUp() else stopSelf()
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
        if (role == ROLE_GATEWAY) registerPhoneReceiver()
        startWorker()
    }

    // ---------------------------------------------------------------- public actions (UI)

    fun answer() {
        stopSimRinging()
        if (role == ROLE_GATEWAY) {
            gatewayAnswer()
        } else {
            simCallHere = true
            requestGateway("ANSWER")
        }
    }

    fun reject() {
        stopSimRinging()
        if (role == ROLE_REMOTE) CallHistory.onDeclinedHere()
        if (role == ROLE_GATEWAY) gatewayEnd(rejectRinging = true) else requestGateway("REJECT")
    }

    fun hangUp() {
        if (role == ROLE_GATEWAY) gatewayEnd() else requestGateway("HANGUP")
    }

    /** [name] is only used for the Remote's call history. */
    fun dial(number: String, name: String = "") {
        if (role == ROLE_GATEWAY) {
            gatewayDial(number)
        } else {
            CallHistory.onDialRequested(number, name)
            simDialAt = SystemClock.elapsedRealtime()
            requestGateway("DIAL", number)
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

    private fun requestGateway(command: String, argument: String = "") {
        if (!connected) {
            callControlError = "FAILED: Bluetooth is disconnected"
            Hub.log("Call control failed: Bluetooth is disconnected")
            Hub.notifyUi()
            return
        }
        callControlError = "PENDING: Waiting for Gateway confirmation"
        if (command == "DIAL") send(command, argument) else send(command)
        val request = ++callControlRequest
        trafficHandler.postDelayed({
            // No answer at all: say so instead of waiting forever.
            if (request == callControlRequest && callControlError?.startsWith("PENDING:") == true) {
                callControlError = "FAILED: No reply from the Gateway. Check that both phones run the same PCL version."
                Hub.log("Call control: no reply from the Gateway")
                Hub.notifyUi()
            }
        }, CALL_CONTROL_TIMEOUT_MS)
        Hub.notifyUi()
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
                s = if (role == ROLE_GATEWAY) acceptOne(gen) else connectOnce()
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
            serverSocket = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectOnce(): BluetoothSocket {
        val a = adapter()
        val addr = deviceAddress ?: throw IOException("No gateway phone selected")
        val dev = a.getRemoteDevice(addr)
        setConn(false, "Connecting to ${dev.name ?: addr}…")
        val s = dev.createRfcommSocketToServiceRecord(Protocol.SERVICE_UUID)
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
        socket = s
        val w = FrameWriter(s.outputStream)
        writer = w
        val peer = try {
            s.remoteDevice.name ?: s.remoteDevice.address
        } catch (e: Exception) {
            "peer"
        }
        setConn(true, "Connected to $peer")
        helloReceived = false
        peerProtocol = null
        peerAppVersion = ""
        send("HELLO", role, Protocol.VERSION.toString(), appVersion)
        if (role == ROLE_GATEWAY) {
            sendCallState()
            sendContactsFingerprint()
        }
        val input = DataInputStream(BufferedInputStream(s.inputStream, 16384))
        try {
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
            if (writer === w) writer = null
            if (socket === s) socket = null
            connected = false
            clearPendingAudioRequests()
            contactBuf = null
            helloReceived = false
            if (role == ROLE_REMOTE) {
                if (callControlError?.startsWith("PENDING:") == true) callControlError = "FAILED: Connection lost before Gateway confirmation"
                stopRinging()
                callState = CALL_IDLE
                nm.cancel(ID_INCOMING)
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
        trafficHandler.removeCallbacks(trafficSampler)
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
        clearPendingAudioRequests()
        stopRinging()
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
                try {
                    w.writeCmd(msg)
                    txTotal.addAndGet(msg.toByteArray(Charsets.UTF_8).size.toLong() + 5L)
                } catch (e: Exception) {
                    Hub.log("Send failed: ${e.message}")
                    closeSocket()
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
        when (cmd) {
            "HELLO" -> {
                // Older versions sent only the role; they have no protocol number.
                peerProtocol = p.getOrNull(2)?.toIntOrNull()
                peerAppVersion = p.getOrNull(3).orEmpty().take(20)
                helloReceived = true
                Hub.log("Peer is the $arg1 phone (PCL ${peerAppVersion.ifEmpty { "older version" }})")
                versionWarning?.let { Hub.log("Version mismatch: $it") }
            }
            // gateway -> remote
            "CALL" -> {
                val previousState = callState
                callState = arg1.takeIf { it in setOf(CALL_IDLE, CALL_RINGING, CALL_OFFHOOK) } ?: CALL_IDLE
                if (callState == CALL_RINGING) callControlError = null
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
                CallHistory.onSimState(callState, callNumber, callName, callSimIdentity,
                    p.getOrNull(9)?.toLongOrNull()?.takeIf { it > 0L })

                onRemoteCallUpdate(previousState)
            }
            // remote -> gateway
            "ANSWER" -> { gatewayAnswer(); sendCallResult() }
            "REJECT", "HANGUP" -> { gatewayEnd(rejectRinging = cmd == "REJECT"); sendCallResult() }
            "DIAL" -> { gatewayDial(arg1); sendCallResult() }
            "SYNC_CONTACTS" -> sendContacts()
            "AUDIO_QUERY" -> gatewayAudioStatus()
            "AUDIO_MODE" -> gatewaySetVolumeMode(arg1, p.getOrNull(2).orEmpty())
            "AUDIO_VOLUME" -> gatewaySetAudioVolume(arg1, p.getOrNull(2).orEmpty())
            "AUDIO_STEP" -> gatewayAudioStep(arg1, p.getOrNull(2).orEmpty())
            "AUDIO_ROUTE" -> gatewaySetAudioRoute(arg1, p.getOrNull(2).orEmpty())
            "AUDIO_RESULT" -> {
                if (arg1 == "ROUTE_VERIFY") {
                    Hub.log("Gateway delayed speaker verification: ${p.getOrNull(2).orEmpty()}")
                    return
                }
                val id = p.getOrNull(3).orEmpty()
                val timeout = pendingAudioRequests.remove(id)
                if (timeout != null) {
                    audioTimeoutHandler.removeCallbacks(timeout)
                    Hub.log("Gateway audio result: ${arg1} — ${p.getOrNull(2).orEmpty()}")
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
                val detail = p.drop(2).joinToString("|")
                callControlError = if (arg1 == "FAILED") "FAILED: $detail" else null
                Hub.log(if (arg1 == "FAILED") "Gateway call control failed: $detail" else "Gateway accepted call-control request")
            }
            // contacts: gateway -> remote
            "CONTACTS_HASH" -> {
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
                val data = contactBuf?.toString() ?: ""
                contactBuf = null
                ContactStore.saveSynced(this, data)
                Prefs.of(this).edit().putString(Prefs.CONTACTS_HASH, ContactStore.fingerprint(data)).apply()
                Hub.contactsVersion++
                Hub.log("Received ${ContactStore.parse(data).size} contacts from gateway")
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

    private fun sendCallResult() {
        val error = callControlError
        send("CALL_RESULT", if (error == null) "OK" else "FAILED", error?.removePrefix("FAILED: ") ?: "Android accepted the request")
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

    private val phoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val st = i.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            @Suppress("DEPRECATION")
            val num = i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            val previousState = callState
            val newState = when (st) {
                TelephonyManager.EXTRA_STATE_RINGING -> CALL_RINGING
                TelephonyManager.EXTRA_STATE_OFFHOOK -> CALL_OFFHOOK
                else -> CALL_IDLE
            }
            val priorStart = simCallStartedMs
            val priorDirection = callDirection
            if (newState == CALL_RINGING && previousState == CALL_IDLE) { simCallStartedMs = System.currentTimeMillis(); callDirection = "INCOMING"; callNumber = ""; callName = "" }
            else if (newState == CALL_OFFHOOK && previousState == CALL_IDLE) { simCallStartedMs = System.currentTimeMillis(); callDirection = "OUTGOING" }
            else if (newState == CALL_IDLE) { simCallStartedMs = 0L; callDirection = "" }
            val gotNumber = !num.isNullOrEmpty() && num != callNumber
            if (!num.isNullOrEmpty()) {
                callNumber = num
                callName = lookupName(num)
            }
            val changed = newState != callState
            if (newState != CALL_IDLE && (changed || callSimIdentity == null))
                callSimIdentity = SimCallDetector.identify(this@LinkService, newState)
            if (changed && newState == CALL_RINGING) callControlError = null
            callState = newState
            if (changed || gotNumber) {
                Hub.log("Phone call: $newState (${logCaller()})")
                sendCallState()
            }
            if (newState == CALL_IDLE) {
                callNumber = ""
                callName = ""
                callSimIdentity = null
            }
            Hub.notifyUi()
        }
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
            if (current != callState) {
                callState = current
                // A snapshot cannot reliably identify call direction or caller number.
                callDirection = ""
                if (current == CALL_IDLE) { callNumber = ""; callName = "" }
                Hub.log("Initial phone state: $current (details may be unavailable)")
                sendCallState()
            }
        } catch (e: Exception) {
            Hub.log("Initial phone-state query unavailable: ${e.message ?: e.javaClass.simpleName}")
        }
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

    private fun sendCallState() {
        if (writer != null) {
            val sim = callSimIdentity
            send("CALL", callState, callNumber, callName, callDirection,
                sim?.slotIndex?.toString().orEmpty(), encodeText(sim?.displayName.orEmpty()),
                encodeText(sim?.carrier.orEmpty()),
                SimDialAccounts.availableSlots(this).sorted().joinToString(","),
                simCallStartedMs.takeIf { it > 0L }?.toString().orEmpty())
        }
    }

    @SuppressLint("MissingPermission")
    private fun gatewayDial(number: String) {
        val n = number.trim()
        if (n.isEmpty()) { callControlError = "FAILED: Empty phone number"; return }
        if (!has(Manifest.permission.CALL_PHONE)) {
            callControlError = "FAILED: Phone permission missing on Gateway"
            Hub.log("Dial failed: 'Phone' permission missing on gateway")
            return
        }
        try {
            callNumber = n
            callName = lookupName(n)
            callDirection = "OUTGOING"
            getSystemService(TelecomManager::class.java)!!
                .placeCall(Uri.fromParts("tel", n, null), Bundle())
            callControlError = null
            Hub.log("Dialing ${logCaller(n)}")
        } catch (e: Exception) {
            callControlError = "FAILED: ${e.message ?: "Android rejected the dial request"}"
            Hub.log("Dial failed: ${e.message}")
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
                    Hub.log("Incoming call (${logCaller()})")
                }
                showIncoming()
                startRinging()
            }
            CALL_OFFHOOK -> {
                stopSimRinging()
                if (previous == CALL_IDLE) {
                    // Placed from here if this phone asked to dial just now (or answered it
                    // before a link drop).
                    simCallHere = simCallHere || SystemClock.elapsedRealtime() - simDialAt < DIAL_WINDOW_MS
                }
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
        val n = Notification.Builder(this, CH_CALLS)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("Call in progress")
            .setContentText(display())
            .setSubText("on the Gateway")
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setUsesChronometer(CallHistory.connectedSince > 0)
            .setWhen(System.currentTimeMillis() - (SystemClock.elapsedRealtime() - CallHistory.connectedSince).coerceAtLeast(0))
            .setContentIntent(openCallsPi())
            .addAction(action(android.R.drawable.ic_media_previous, "Volume −", actionPi(ACTION_AUDIO_DOWN, 31)))
            .addAction(action(android.R.drawable.ic_media_next, "Volume +", actionPi(ACTION_AUDIO_UP, 32)))
            .addAction(action(android.R.drawable.ic_lock_silent_mode_off, "Speaker / Earpiece", actionPi(ACTION_AUDIO_SPEAKER, 33)))
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "End call", actionPi(ACTION_HANGUP, 13)))
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
        val list = ContactStore.readDevice(this)
        val data = ContactStore.toChunks(list).joinToString("")
        send("CONTACTS_HASH", ContactStore.fingerprint(data), list.size.toString())
    }

    /** Gateway: send the full contact list (on request from the Remote). */
    private fun sendContacts() {
        val w = writer
        if (w == null || !connected) {
            Hub.log("Contact sync unavailable: Bluetooth is disconnected")
            return
        }
        if (!has(Manifest.permission.READ_CONTACTS)) {
            Hub.log("Contacts permission missing – contacts not sent to Remote")
            send("CONTACTS_RESULT", "FAILED", "Contacts permission is not granted on Gateway")
            return
        }
        val list = ContactStore.readDevice(this)
        val chunks = ContactStore.toChunks(list)
        try {
            io.execute {
                try {
                    val frames = listOf("CONTACTS_BEGIN") + chunks.map { "CONTACTS|$it" } +
                        listOf("CONTACTS_END", "CONTACTS_RESULT|OK|${list.size}")
                    for (frame in frames) {
                        w.writeCmd(frame)
                        txTotal.addAndGet(frame.toByteArray(Charsets.UTF_8).size.toLong() + 5L)
                    }
                    Hub.log("Sent ${list.size} contacts to Remote")
                } catch (e: Exception) {
                    Hub.log("Contacts sync failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Hub.log("Could not queue contact sync: ${e.message}")
        }
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
        Intent(this, LinkService::class.java).setAction(action),
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
        val n = Notification.Builder(this, CH_CALLS)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("Incoming call")
            .setContentText(display())
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(openCallsPi())
            .setFullScreenIntent(openCallsPi(), true)
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
