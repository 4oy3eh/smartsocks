package com.smartsocks.app.live

import com.smartsocks.app.platform.DeviceFactory
import com.smartsocks.app.platform.DeviceResult
import com.smartsocks.core.analytics.BalanceCalculator
import com.smartsocks.core.analytics.FootState
import com.smartsocks.core.model.Foot
import com.smartsocks.core.session.ConnectionState
import com.smartsocks.core.session.SockSession
import com.smartsocks.core.session.StreamSettings
import com.smartsocks.core.session.StreamStats
import com.smartsocks.core.settings.AppSettings
import com.smartsocks.core.settings.DeviceChoice
import com.smartsocks.core.settings.SettingsRepository
import com.smartsocks.core.sim.SimMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlin.concurrent.Volatile
import kotlin.math.roundToInt
import kotlin.time.TimeSource

/** What the UI shows about one foot's link. */
sealed interface LinkState {
    /** [DeviceChoice.None]: nothing to connect to. */
    data object NoDevice : LinkState

    /** The device cannot be used on this platform (or could not be created); connecting is pointless. */
    data class Unavailable(val message: String) : LinkState

    /** Ready, not connected (not auto-connected, or disconnected by the user). */
    data object Idle : LinkState

    data object Connecting : LinkState

    data object Configuring : LinkState

    data object Streaming : LinkState

    /** The link failed or was closed by the device; [reason] says why. */
    data class Disconnected(val reason: String?) : LinkState

    /** Connecting, configuring or streaming. */
    val isActive: Boolean get() = this == Connecting || this == Configuring || this == Streaming

    /** Connecting is possible from here. */
    val canConnect: Boolean get() = this == Idle || this is Disconnected
}

/**
 * The stream rate as the UI shows it. Only whole packets per second, so that it changes about once a second,
 * not with every [StreamStats] update (every 10 packets); the raw stats are on [LiveController.FootLink.session].
 */
data class LinkRate(val packetsPerSecond: Int = 0, val nominalHz: Int = 0, val rateMismatch: Boolean = false) {
    companion object {
        fun of(stats: StreamStats) = LinkRate(stats.packetsPerSecond.roundToInt(), stats.nominalHz, stats.rateMismatch)
    }
}

data class FootStatus(
    val foot: Foot,
    val device: DeviceChoice,
    /** The transport's name ("Simulated left", "COM5"); null without a transport. */
    val deviceName: String?,
    val state: LinkState,
    val rate: LinkRate = LinkRate(),
)

/**
 * The live screen's state: one independent [SockSession] per foot, built from [settings]. A foot's session is
 * rebuilt (and reconnected if it was connected) only when its device or the stream settings change. Processor
 * settings are applied to the running sessions (baseline kept), a simulator mode change to the running simulator.
 *
 * Connects automatically on start if [AppSettings.autoConnect]; afterwards only on [FootLink.connect]. A link that
 * fails or drops stays down (no automatic reconnect), and so does a foot whose device is unusable: a later settings
 * change does not connect it by surprise.
 *
 * Streaming runs in [scope] independently of the UI; the UI calls [sample] once per frame. Both feet share
 * [clockNanos] (pass a virtual one only in tests).
 */
class LiveController(
    private val settings: SettingsRepository,
    private val devices: DeviceFactory,
    scope: CoroutineScope,
    private val clockNanos: () -> Long = monotonicNanos(),
) {
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val scope = scope + job

    private val _paused = MutableStateFlow(false)

    /** See [setPaused]. */
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    val left = FootLink(Foot.LEFT)
    val right = FootLink(Foot.RIGHT)

    fun link(foot: Foot): FootLink = if (foot == Foot.LEFT) left else right

    private val sampler = LiveSampler()

    init {
        this.scope.launch {
            settings.awaitLoaded()
            val auto = settings.settings.value.autoConnect
            left.start(auto)
            right.start(auto)
        }
    }

    /**
     * While paused (the app is in the background) no foot streams: active links are closed, but what the user
     * asked for is kept, so un-pausing reconnects exactly the feet that were connected (or wanted to be).
     *
     * TODO(recording): background recording will need a foreground service that keeps the links up instead.
     */
    fun setPaused(paused: Boolean) {
        _paused.value = paused
    }

    /**
     * Builds the frame to draw at [frameNanos] (any monotonic clock, e.g. `withFrameNanos`; only differences
     * matter). Call from one thread, once per displayed frame.
     */
    fun sample(frameNanos: Long): LiveSnapshot =
        sampler.sample(frameNanos, left.status.value, left.latest, right.status.value, right.latest)

    /** Whether frames drawn after [snapshot] can differ without a status change: some foot is streaming. */
    fun isAnimating(snapshot: LiveSnapshot): Boolean =
        snapshot.left.status.state == LinkState.Streaming || snapshot.right.status.state == LinkState.Streaming

    /** Suspends until either foot's status differs from what [snapshot] shows. */
    suspend fun awaitStatusChange(snapshot: LiveSnapshot) {
        combine(left.status, right.status) { l, r -> l != snapshot.left.status || r != snapshot.right.status }
            .first { it }
    }

    /** Stops both sessions (each sends STOP and closes its transport) and waits for that. */
    suspend fun shutdown() {
        job.cancelAndJoin()
    }

    /** One foot: its session (if its device could be created) and connect/disconnect requests. */
    inner class FootLink internal constructor(val foot: Foot) {
        private val _status =
            MutableStateFlow(FootStatus(foot, settings.settings.value.device(foot), null, LinkState.Idle))

        /** Changes only when something shown changes (state, device, whole packets per second). */
        val status: StateFlow<FootStatus> = _status.asStateFlow()

        private val _session = MutableStateFlow<SockSession?>(null)

        /** The current session; replaced when the foot's settings change, null without a usable device. */
        val session: StateFlow<SockSession?> = _session.asStateFlow()

        /** Newest sample of the current session, null when not streaming. */
        val latest: FootState? get() = _session.value?.latest?.value

        /** What the user (or auto-connect) asked for; null until the controller has started (settings loaded). */
        private val request = MutableStateFlow<Request?>(null)

        /** The current device can be connected to; false while there is none or it is unavailable. */
        @Volatile
        private var usable = false

        /** The foot is meant to be connected (it may still be connecting, or paused). */
        val wantsConnection: Boolean get() = request.value?.connect == true

        /** Ignored when the device is unusable or a connection is already wanted. */
        fun connect() {
            if (!usable) return
            request.update { if (it?.connect == true) it else Request(true, it.nextId) }
        }

        fun disconnect() {
            request.update { if (it != null && !it.connect) it else Request(false, it.nextId) }
        }

        /**
         * Disconnects if a connection is wanted, connects otherwise. Decides from the request, not from the
         * (asynchronously updated) [status], so a quick second tap cancels the first.
         */
        fun toggle() {
            if (wantsConnection) disconnect() else connect()
        }

        internal fun start(autoConnect: Boolean) {
            request.compareAndSet(null, Request(autoConnect, 0))
            scope.launch {
                settings.settings
                    .map { FootConfig.of(foot, it) }
                    .distinctUntilChanged()
                    .collectLatest { config -> runConfig(config) }
            }
        }

        /** Nothing to connect to: forget a pending connect so a later device change does not connect by surprise. */
        private fun unusable(device: DeviceChoice, state: LinkState) {
            usable = false
            request.update { if (it?.connect == true) Request(false, it.nextId) else it }
            _session.value = null
            _status.value = FootStatus(foot, device, null, state)
        }

        private suspend fun runConfig(config: FootConfig) {
            // The real choice (with the simulator mode the config key leaves out).
            val current = settings.settings.value
            val device = current.device(foot)
            if (device == DeviceChoice.None) return unusable(device, LinkState.NoDevice)
            val result = try {
                devices.create(foot, device)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DeviceResult.Unavailable(e.message ?: "Cannot create device")
            }
            val ready = when (result) {
                is DeviceResult.Unavailable -> return unusable(device, LinkState.Unavailable(result.message))
                is DeviceResult.Ready -> result
            }
            val transport = ready.transport
            val session = SockSession(
                foot,
                transport,
                config.stream,
                processorSettings = current.processor,
                clockNanos = clockNanos,
            )
            usable = true
            _session.value = session
            try {
                coroutineScope {
                    launch {
                        settings.settings.map { it.processor }.distinctUntilChanged().collect {
                            if (it != session.processorSettings.value) session.updateProcessorSettings(it)
                        }
                    }
                    val simulator = ready.simulator
                    if (simulator != null) {
                        launch {
                            settings.settings
                                .map { (it.device(foot) as? DeviceChoice.Simulator)?.mode }
                                .filterNotNull()
                                .collect { simulator.mode = it }
                        }
                    }
                    launch {
                        combine(
                            session.connection,
                            session.stats.map(LinkRate::of).distinctUntilChanged(),
                            combine(request, _paused) { req, paused -> req?.connect == true && !paused },
                            settings.settings.map { it.device(foot) }.distinctUntilChanged(),
                        ) { connection, rate, wanted, shown ->
                            FootStatus(foot, shown, session.displayName, connection.toLinkState(wanted), rate)
                        }.collect { _status.value = it }
                    }
                    combine(request.filterNotNull(), _paused) { req, paused -> req to paused }
                        .collectLatest { (req, paused) ->
                            if (req.connect && !paused) {
                                session.run()
                                // The link ended by itself (failure, drop): it stays down until asked again.
                                request.compareAndSet(req, Request(false, req.nextId))
                            }
                        }
                }
            } finally {
                _session.compareAndSet(session, null)
            }
        }
    }

    /**
     * The part of [AppSettings] a session is built from. The simulator mode is left out (it is switched on the
     * running simulator), and so are the processor settings (applied to the running session).
     */
    private data class FootConfig(val device: DeviceChoice, val stream: StreamSettings) {
        companion object {
            fun of(foot: Foot, settings: AppSettings): FootConfig {
                val device = when (val d = settings.device(foot)) {
                    is DeviceChoice.Simulator -> d.copy(mode = SimMode.Walking)
                    else -> d
                }
                return FootConfig(device, settings.stream)
            }
        }
    }

    private companion object {
        fun monotonicNanos(): () -> Long {
            val start = TimeSource.Monotonic.markNow()
            return { start.elapsedNow().inWholeNanoseconds }
        }
    }
}

/** A connect/disconnect request of a foot; [id] makes a repeated request distinct. */
private data class Request(val connect: Boolean, val id: Long)

private val Request?.nextId: Long get() = (this?.id ?: 0) + 1

private fun ConnectionState.toLinkState(wanted: Boolean): LinkState = when (this) {
    ConnectionState.Idle -> LinkState.Idle

    ConnectionState.Connecting -> LinkState.Connecting

    ConnectionState.Configuring -> LinkState.Configuring

    ConnectionState.Streaming -> LinkState.Streaming

    // Cancelled on request (no reason): simply not connected.
    is ConnectionState.Disconnected -> if (reason == null && !wanted) LinkState.Idle else LinkState.Disconnected(reason)
}

/** L/R split and combined COP, from core, in the stance geometry the COP view draws. */
internal fun balanceOf(left: FootState?, right: FootState?) =
    BalanceCalculator.compute(left, right, LiveGeometry.STANCE_HALF_WIDTH)
