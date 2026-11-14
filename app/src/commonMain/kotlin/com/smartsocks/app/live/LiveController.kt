package com.smartsocks.app.live

import com.smartsocks.app.platform.DeviceFactory
import com.smartsocks.app.platform.DeviceResult
import com.smartsocks.app.platform.UnavailableReason
import com.smartsocks.app.settings.deviceInUseByOtherFoot
import com.smartsocks.app.settings.identity
import com.smartsocks.core.analytics.BalanceCalculator
import com.smartsocks.core.analytics.Baseline
import com.smartsocks.core.analytics.FootState
import com.smartsocks.core.model.Foot
import com.smartsocks.core.protocol.MeasurementRange
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.time.TimeSource

/** What the UI shows about one foot's link. */
sealed interface LinkState {
    /** [DeviceChoice.None]: nothing to connect to. */
    data object NoDevice : LinkState

    /**
     * The device cannot be used (now): connecting is pointless until [reason] is fixed. A
     * [UnavailableReason.recoverable] one keeps a pending connect: it happens once
     * [LiveController.recheckDevices] finds the device usable.
     */
    data class Unavailable(val message: String, val reason: UnavailableReason = UnavailableReason.Unsupported) :
        LinkState

    /** Ready, not connected (not auto-connected, or disconnected by the user). */
    data object Idle : LinkState

    data object Connecting : LinkState

    data object Configuring : LinkState

    data object Streaming : LinkState

    /** The link failed or was closed by the device; [reason] says why. */
    data class Disconnected(val reason: String?) : LinkState

    /**
     * The link dropped (or did not open) while the foot is meant to be connected, and [AppSettings.autoReconnect] is
     * on: retry number [attempt] starts in [secondsLeft] seconds (counting down once a second, see
     * [ReconnectBackoff]). While the retry itself connects, the foot shows [Connecting] / [Configuring] as usual.
     * [lastError] is why the previous attempt ended. [LiveController.FootLink.disconnect] calls it off,
     * [LiveController.FootLink.retryNow] skips the wait.
     */
    data class Reconnecting(val attempt: Int, val secondsLeft: Int, val lastError: String?) : LinkState

    /** Connecting, configuring or streaming. */
    val isActive: Boolean get() = this == Connecting || this == Configuring || this == Streaming

    /** Meant to be linked and on the way there: connecting, configuring, or waiting to reconnect. */
    val isRecovering: Boolean get() = this == Connecting || this == Configuring || this is Reconnecting

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

/** A foot's explicit calibration, as the settings show it; see [LiveController.FootLink.calibrate]. */
sealed interface CalibrationState {
    data object Idle : CalibrationState

    /** Collecting the unloaded samples; [progress] 0..1 in steps of 2 %. */
    data class Measuring(val progress: Double) : CalibrationState

    /** The new zero point is in effect. */
    data object Done : CalibrationState

    data class Failed(val message: String) : CalibrationState
}

data class FootStatus(
    val foot: Foot,
    val device: DeviceChoice,
    /** The transport's name ("Simulated left", "COM5"); null without a transport. */
    val deviceName: String?,
    val state: LinkState,
    val rate: LinkRate = LinkRate(),
    /**
     * The device is unavailable for a fixable reason and a connect is waiting for the fix (see
     * [LinkState.Unavailable]); [LiveController.FootLink.disconnect] drops it.
     */
    val pendingConnect: Boolean = false,
    /**
     * A connection is wanted (by the user or auto-connect): see [LiveController.FootLink.wantsConnection]. Also while
     * paused, between retries, and for a moment while the session is rebuilt, unlike [state].
     */
    val wanted: Boolean = false,
)

/**
 * The live screen's state: one independent [SockSession] per foot, built from [settings]. A foot's session is
 * rebuilt (and reconnected if it was connected) only when its device or the stream settings change. Processor
 * settings are applied to the running sessions (baseline kept), a simulator mode change to the running simulator.
 *
 * Connects automatically on start if [AppSettings.autoConnect]; afterwards only on [FootLink.connect]. A link that
 * fails to open or drops while the foot is meant to be connected is retried if [AppSettings.autoReconnect]:
 * [LinkState.Reconnecting] waits [ReconnectBackoff.delayMillis] (1 s, 2 s, 4 s … 30 s, as long as the connection is
 * wanted; back to 1 s after a link that streamed [ReconnectBackoff.STABLE_MILLIS]), then the same session runs
 * again. The retries end with [FootLink.disconnect], a device or stream change (new session: it connects at once,
 * as any rebuild of a wanted foot), a pause (un-pausing connects at once), [shutdown], the setting switched off,
 * or a device check that fails before a retry (Bluetooth off etc., below). With the setting off a link that fails
 * or drops stays down until asked again.
 *
 * A foot whose device is unusable stays down too: a later settings change does not connect it by surprise.
 * Exception: a device that is only unavailable for a fixable reason (missing permission, Bluetooth off; checked when
 * the session is built and before every connect and retry) keeps the pending connect, and [recheckDevices] (called
 * when the user may have fixed it) connects it; choosing another device drops that pending connect. If both feet
 * name the same sock, the right foot is unavailable ([UnavailableReason.InUse]).
 *
 * An explicit calibration survives a session rebuild that keeps the sock (by its identity: port or address, not
 * its display name) and the measurement range (e.g. a new sample rate).
 *
 * Streaming runs in [scope] independently of the UI; the UI calls [sample] once per frame. Every sample of both
 * feet also goes into [history] (for the charts), from the stream's own coroutines. Both feet share [clockNanos]
 * (pass a virtual one only in tests); [nowNanos] reads it, on the time axis of the samples' timestamps.
 * [reconnectDelayMillis] gives the wait before a retry at a [ReconnectBackoff.Progress.step] (by default with
 * random jitter); tests pass a deterministic one.
 */
class LiveController(
    private val settings: SettingsRepository,
    private val devices: DeviceFactory,
    scope: CoroutineScope,
    initiallyPaused: Boolean = false,
    private val reconnectDelayMillis: (step: Int) -> Long = { ReconnectBackoff.delayMillis(it, Random.nextDouble()) },
    private val clockNanos: () -> Long = monotonicNanos(),
) {
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val scope = scope + job

    private val _pausedFeet = MutableStateFlow(if (initiallyPaused) Foot.entries.toSet() else emptySet())

    /** The feet that are paused, see [setPaused]. */
    val pausedFeet: StateFlow<Set<Foot>> = _pausedFeet.asStateFlow()

    private val _paused = MutableStateFlow(initiallyPaused)

    /** Both feet are paused, see [setPaused]. */
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    val left = FootLink(Foot.LEFT)
    val right = FootLink(Foot.RIGHT)

    fun link(foot: Foot): FootLink = if (foot == Foot.LEFT) left else right

    private val sampler = LiveSampler()

    /**
     * The last [FootHistory.capacity] samples of each foot at full stream rate, kept across reconnects and session
     * rebuilds (with a gap), see [FootHistory].
     */
    val history = LiveHistory()

    /** Now, on the clock the samples are stamped from (their [FootState.timestampNanos]). */
    fun nowNanos(): Long = clockNanos()

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
     * asked for is kept, so un-pausing reconnects exactly the feet that were connected (or wanted to be). On Android
     * [com.smartsocks.app.recording.BackgroundRecording] decides it (a recording keeps the links up).
     * [initiallyPaused]: paused from the start, so nothing connects before the owner un-pauses it.
     */
    fun setPaused(paused: Boolean) {
        setPaused(if (paused) Foot.entries.toSet() else emptySet())
    }

    /** Pauses exactly [feet] (the others stream as asked), e.g. all but the recorded ones in the background. */
    fun setPaused(feet: Set<Foot>) {
        _pausedFeet.value = feet
        _paused.value = feet.containsAll(Foot.entries)
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

    /**
     * Tries again to build the sessions of feet whose device was unavailable for a fixable reason (permission now
     * granted, Bluetooth switched on); such a foot connects if a connection was wanted. Call it after the system
     * dialogs return and when the app comes back to the foreground.
     */
    fun recheckDevices() {
        left.recheck()
        right.recheck()
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

        /** Bumped by [recheck]: part of the session's config, so it rebuilds the session. */
        private val retry = MutableStateFlow(0)

        /**
         * The calibration state together with the token of the watcher allowed to change it: changed only with
         * [updateCalibration], so a watcher's late write can never undo a [clearCalibration] from another thread.
         */
        private val taggedCalibration = MutableStateFlow(TaggedCalibration(null, CalibrationState.Idle))

        private val _calibration = MutableStateFlow<CalibrationState>(CalibrationState.Idle)

        /** The last [calibrate] or [clearCalibration] and how it went. */
        val calibration: StateFlow<CalibrationState> = _calibration.asStateFlow()

        private val _calibrated = MutableStateFlow(false)

        /** An explicit calibration is in effect for the current device (also while not connected). */
        val calibrated: StateFlow<Boolean> = _calibrated.asStateFlow()

        @Volatile
        private var calibrationJob: Job? = null

        /** The device of a recoverable unavailability that keeps a pending connect; see [runConfig]. */
        @Volatile
        private var blockedDevice: DeviceChoice? = null

        /** The last explicit calibration and what it is valid for; handed to the next session of the same. */
        @Volatile
        private var carried: CarriedBaseline? = null

        private val pausedHere = _pausedFeet.map { foot in it }.distinctUntilChanged()

        /** The foot is meant to be connected (it may still be connecting, reconnecting, or paused). */
        val wantsConnection: Boolean get() = request.value?.connect == true

        /**
         * Non-null while [keepConnected] runs and may still retry: the session's "down" states (idle, disconnected)
         * then show as on the way back, never as down, whatever order the status sees its inputs change in.
         */
        private val loop = MutableStateFlow<RetryLoop?>(null)

        /** Bumped by [retryNow]. */
        private val retryNowRequests = MutableStateFlow(0)

        /** While [LinkState.Reconnecting]: retry at once instead of waiting out the countdown. Ignored otherwise. */
        fun retryNow() {
            if (loop.value?.waiting != null) retryNowRequests.update { it + 1 }
        }

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

        /**
         * Starts an explicit calibration: the next [CALIBRATION_SECONDS] of samples become the foot's zero point, so
         * the foot must be lifted (or the insole taken off) and kept still. Only while streaming; otherwise
         * [calibration] becomes [CalibrationState.Failed] and this returns false. Progress and outcome are published
         * in [calibration]; a link drop or device change cuts it short (the previous calibration, if any, stays).
         */
        fun calibrate(): Boolean {
            val session = _session.value
            if (session == null || session.connection.value != ConnectionState.Streaming) {
                return refuseCalibration()
            }
            val before = session.baseline.value
            val samples = max(1, (CALIBRATION_SECONDS * session.settings.rate.hz).roundToInt())
            val token = Any()
            calibrationJob?.cancel()
            if (!session.calibrate(samples)) return refuseCalibration()
            updateCalibration { TaggedCalibration(token, CalibrationState.Measuring(0.0)) }
            calibrationJob = scope.launch {
                combine(
                    session.calibrationProgress.map { quantize(it ?: 0.0) }.distinctUntilChanged(),
                    session.baseline,
                    session.connection,
                    _session,
                ) { progress, baseline, connection, current ->
                    when {
                        baseline != null && baseline !== before -> CalibrationState.Done

                        current !== session -> CalibrationState.Failed("The connection was restarted")

                        connection is ConnectionState.Disconnected && connection.reason != null ->
                            CalibrationState.Failed("The link dropped during calibration")

                        // Disconnected on request, paused, or the session replaced.
                        connection != ConnectionState.Streaming ->
                            CalibrationState.Failed("The foot was disconnected")

                        else -> CalibrationState.Measuring(progress)
                    }
                }
                    .transformWhile {
                        emit(it)
                        it is CalibrationState.Measuring
                    }
                    .collect { state ->
                        updateCalibration {
                            if (it.token ===
                                token
                            ) {
                                TaggedCalibration(token, state)
                            } else {
                                it
                            }
                        }
                    }
            }
            return true
        }

        /** Drops the explicit calibration (and stops one in progress): auto-zero, if enabled, applies again. */
        fun clearCalibration() {
            updateCalibration { TaggedCalibration(null, CalibrationState.Idle) }
            calibrationJob?.cancel()
            carried = null
            _session.value?.clearCalibration()
            _calibrated.value = false
        }

        private fun refuseCalibration(): Boolean {
            updateCalibration { TaggedCalibration(null, CalibrationState.Failed(NOT_STREAMING)) }
            return false
        }

        /**
         * Atomically changes [taggedCalibration], then copies its state to [calibration]. Another thread may copy an
         * older state between our read and write, so repeat until what we wrote is still current: the last writer
         * always leaves the newest state.
         */
        private fun updateCalibration(transform: (TaggedCalibration) -> TaggedCalibration) {
            taggedCalibration.update(transform)
            while (true) {
                val current = taggedCalibration.value
                _calibration.value = current.state
                if (taggedCalibration.value === current) return
            }
        }

        /**
         * A new session (or none): "done" only stays while the calibration is in effect ([inEffect]). A refusal is
         * stale now. The failure of a calibration this rebuild interrupted (reported by its watcher, possibly only
         * after this) stays visible until the next rebuild: here it only loses its watcher token.
         */
        private fun resetStaleCalibration(inEffect: Boolean) {
            updateCalibration {
                when {
                    it.state is CalibrationState.Failed && it.token != null -> TaggedCalibration(null, it.state)
                    it.state is CalibrationState.Failed -> TaggedCalibration(null, CalibrationState.Idle)
                    it.state == CalibrationState.Done && !inEffect -> TaggedCalibration(null, CalibrationState.Idle)
                    else -> it
                }
            }
        }

        internal fun recheck() {
            val state = _status.value.state
            if (state is LinkState.Unavailable && state.reason.recoverable) retry.update { it + 1 }
        }

        internal fun start(autoConnect: Boolean) {
            request.compareAndSet(null, Request(autoConnect, 0))
            scope.launch {
                combine(settings.settings, retry) { s, r -> FootConfig.of(foot, s, r) }
                    .distinctUntilChanged()
                    .collectLatest { config -> runConfig(config) }
            }
        }

        /**
         * Nothing to connect to: forget a pending connect so a later device change does not connect by surprise
         * (unless the device is only temporarily unusable, see [UnavailableReason.recoverable]).
         */
        private suspend fun unusable(config: FootConfig, device: DeviceChoice, state: LinkState) {
            usable = false
            _session.value = null
            _calibrated.value = false
            resetStaleCalibration(inEffect = false)
            val keep = state is LinkState.Unavailable && state.reason.recoverable
            if (!keep) {
                request.update { if (it?.connect == true) Request(false, it.nextId) else it }
                _status.value = FootStatus(foot, device, null, state)
                return
            }
            blockedDevice = config.device
            // Until the config changes: show whether a connect is waiting for the fix.
            request.collect { req ->
                val wanted = req?.connect == true
                _status.value = FootStatus(foot, device, null, state, pendingConnect = wanted, wanted = wanted)
            }
        }

        private suspend fun runConfig(config: FootConfig) {
            // A connect kept for a device that was only temporarily unusable is not meant for another device.
            val blocked = blockedDevice
            blockedDevice = null
            if (blocked != null && blocked != config.device) {
                request.update { if (it?.connect == true) Request(false, it.nextId) else it }
            }
            // The real choice (with the simulator mode the config key leaves out).
            val current = settings.settings.value
            val device = current.device(foot)
            if (device == DeviceChoice.None) return unusable(config, device, LinkState.NoDevice)
            if (config.inUse) {
                val inUse = LinkState.Unavailable("Same device as the left foot", UnavailableReason.InUse)
                return unusable(config, device, inUse)
            }
            val result = try {
                devices.create(foot, device)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DeviceResult.Unavailable(e.message ?: "Cannot create device")
            }
            val ready = when (result) {
                is DeviceResult.Unavailable -> return unusable(config, device, result.toLinkState())
                is DeviceResult.Ready -> result
            }
            val transport = ready.transport
            val baselineKey = BaselineKey(config.device.identity, config.stream.range)
            val session = SockSession(
                foot,
                transport,
                config.stream,
                processorSettings = current.processor,
                clockNanos = clockNanos,
                baseline = carried?.takeIf { it.key == baselineKey }?.baseline,
            )
            usable = true
            _session.value = session
            resetStaleCalibration(inEffect = session.baseline.value != null)
            try {
                coroutineScope {
                    val footHistory = history.foot(foot)
                    // Every sample, not the conflated latest; subscribed before the session can emit anything.
                    launch(start = CoroutineStart.UNDISPATCHED) { session.states.collect(footHistory::append) }
                    launch {
                        session.baseline.collect { baseline ->
                            _calibrated.value = baseline != null
                            if (baseline != null) {
                                carried = CarriedBaseline(baselineKey, baseline)
                            } else if (carried?.key == baselineKey) {
                                carried = null // cleared: must not come back with the next session
                            }
                        }
                    }
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
                            combine(request, pausedHere) { req, paused -> Intent(req?.connect == true, paused) },
                            settings.settings.map { it.device(foot) }.distinctUntilChanged(),
                            loop,
                        ) { connection, rate, intent, shown, retry ->
                            val state = linkState(connection, intent.wanted && !intent.paused, retry)
                            FootStatus(foot, shown, session.displayName, state, rate, wanted = intent.wanted)
                        }.collect { _status.value = it }
                    }
                    combine(request.filterNotNull(), pausedHere) { req, paused -> req to paused }
                        .collectLatest { (req, paused) ->
                            if (req.connect && !paused) keepConnected(req, session, device, footHistory)
                        }
                }
            } catch (e: DeviceBlocked) {
                _session.compareAndSet(session, null)
                unusable(config, device, e.result.toLinkState())
            } finally {
                _session.compareAndSet(session, null)
            }
        }

        /**
         * Runs [session] for [req] and, while [AppSettings.autoReconnect] is on, again after every drop or failed
         * open, with [ReconnectBackoff] waits in between. Returns once the link ended and no retry follows; then the
         * request is dropped (the foot stays down until asked again). Cancelled by a new request, a pause or a new
         * config; throws [DeviceBlocked] if the device check before a try, or right after a link ended, fails.
         */
        private suspend fun keepConnected(
            req: Request,
            session: SockSession,
            device: DeviceChoice,
            footHistory: FootHistory,
        ) {
            val job = currentCoroutineContext()[Job]
            var progress = ReconnectBackoff.Progress()
            try {
                while (true) {
                    // Shown as connecting from here, also before the session itself says so.
                    loop.value = RetryLoop(null)
                    // Bluetooth switched off (or the permission revoked) since the session was built or the last try.
                    checkDevice(device)?.let { throw DeviceBlocked(it) }
                    // Whatever came before (another run, another session) is not joined to this run.
                    footHistory.markBreak()
                    var blocked: DeviceResult.Unavailable? = null
                    var planned: PlannedRetry? = null
                    coroutineScope {
                        val since = StreamingSince()
                        val watch = launch(start = CoroutineStart.UNDISPATCHED) {
                            session.connection.first { it == ConnectionState.Streaming }
                            since.nanos = clockNanos()
                        }
                        // The link ended by itself (failure, drop), or the run was cancelled. Decided here, before
                        // the session shows it as down, so that the status goes straight to "reconnecting".
                        session.run { reason ->
                            if (job?.isActive != true) return@run
                            blocked = checkDevice(device)
                            if (blocked != null || !settings.settings.value.autoReconnect) {
                                loop.value = null // down for good: shown as it is
                                return@run
                            }
                            val streamed = since.nanos.takeIf { it >= 0 }?.let { (clockNanos() - it) / 1_000_000 }
                            progress = progress.next(streamed)
                            val wait = reconnectDelayMillis(progress.step)
                            val state = LinkState.Reconnecting(
                                progress.attempt,
                                ReconnectBackoff.secondsLeft(wait),
                                reason,
                            )
                            // Before the state is shown: a "retry now" from then on counts.
                            planned = PlannedRetry(state, wait, retryNowRequests.value)
                            loop.value = RetryLoop(state)
                        }
                        watch.cancel()
                    }
                    currentCoroutineContext().ensureActive()
                    blocked?.let { throw DeviceBlocked(it) }
                    if (!awaitRetry(planned ?: break)) break
                }
                request.compareAndSet(req, Request(false, req.nextId))
            } finally {
                loop.value = null
            }
        }

        /** [DeviceFactory.check], with a failure of the check itself as a reason not to connect. */
        private fun checkDevice(device: DeviceChoice): DeviceResult.Unavailable? = try {
            devices.check(foot, device)
        } catch (e: Exception) {
            DeviceResult.Unavailable(e.message ?: "Cannot check the device")
        }

        /**
         * Shows [LinkState.Reconnecting] counting down [PlannedRetry.waitMillis] in whole seconds. Returns early on
         * [retryNow] (one since [PlannedRetry.retryNowSeen]) or when [AppSettings.autoReconnect] goes off; true if
         * the retry should go ahead.
         */
        private suspend fun awaitRetry(planned: PlannedRetry): Boolean {
            val seen = planned.retryNowSeen
            val interrupted = combine(retryNowRequests, settings.settings) { n, s -> n != seen || !s.autoReconnect }
            var remaining = planned.waitMillis
            while (remaining > 0) {
                val seconds = ReconnectBackoff.secondsLeft(remaining)
                loop.value = RetryLoop(planned.state.copy(secondsLeft = seconds))
                // Up to the next whole second of the countdown.
                val step = remaining - (seconds - 1) * 1_000L
                if (withTimeoutOrNull(step) { interrupted.first { it } } != null) break
                remaining -= step
            }
            return settings.settings.value.autoReconnect
        }
    }

    /** A foot's connect request and whether it is paused. */
    private data class Intent(val wanted: Boolean, val paused: Boolean)

    /** [FootLink.keepConnected] is running and may retry; [waiting]: it is waiting for the next try. */
    private data class RetryLoop(val waiting: LinkState.Reconnecting?)

    /** A retry decided when a link ended: what to show, how long to wait, the [FootLink.retryNow] count then. */
    private class PlannedRetry(val state: LinkState.Reconnecting, val waitMillis: Long, val retryNowSeen: Int)

    /** When the current run reached [ConnectionState.Streaming], on the controller's clock; -1: not (yet). */
    private class StreamingSince {
        @Volatile
        var nanos = -1L
    }

    /** Thrown out of a session's coroutines to turn the foot unavailable. */
    private class DeviceBlocked(val result: DeviceResult.Unavailable) : Exception(result.message)

    /** What an explicit calibration is valid for: the same sock ([identity]) at the same measurement range. */
    private data class BaselineKey(val device: String, val range: MeasurementRange)

    /** A calibration state and the token of the calibration watcher that may change it (null: none may). */
    private class TaggedCalibration(val token: Any?, val state: CalibrationState)

    private class CarriedBaseline(val key: BaselineKey, val baseline: Baseline)

    /**
     * The part of [AppSettings] a session is built from. The simulator mode is left out (it is switched on the
     * running simulator), and so are the processor settings (applied to the running session).
     */
    private data class FootConfig(
        val device: DeviceChoice,
        val stream: StreamSettings,
        /** The right foot names the left foot's sock. */
        val inUse: Boolean,
        val retry: Int,
    ) {
        companion object {
            fun of(foot: Foot, settings: AppSettings, retry: Int): FootConfig {
                val device = when (val d = settings.device(foot)) {
                    is DeviceChoice.Simulator -> d.copy(mode = SimMode.Walking)
                    else -> d
                }
                val inUse = foot == Foot.RIGHT && settings.deviceInUseByOtherFoot(foot)
                return FootConfig(device, settings.stream, inUse, retry)
            }
        }
    }

    companion object {
        /** How long [FootLink.calibrate] measures the lifted foot. */
        const val CALIBRATION_SECONDS = 2.0

        const val NOT_STREAMING = "Connect the foot first: the zero point is measured from the live stream"

        private fun quantize(progress: Double): Double = (progress * 50).toInt().coerceIn(0, 50) / 50.0

        private fun DeviceResult.Unavailable.toLinkState() = LinkState.Unavailable(message, reason)

        /**
         * The foot's state from its session's [connection]. While [loop] runs for a [wanted] connection the session
         * is never shown as down: that is the moment between two tries (waiting, or about to connect).
         */
        private fun linkState(connection: ConnectionState, wanted: Boolean, loop: RetryLoop?): LinkState {
            val down = connection == ConnectionState.Idle || connection is ConnectionState.Disconnected
            if (wanted && down && loop != null) return loop.waiting ?: LinkState.Connecting
            return connection.toLinkState(wanted)
        }

        private fun monotonicNanos(): () -> Long {
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
