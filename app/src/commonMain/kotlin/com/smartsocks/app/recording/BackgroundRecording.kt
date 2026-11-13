package com.smartsocks.app.recording

import com.smartsocks.app.live.LiveController
import com.smartsocks.app.ui.feetText
import com.smartsocks.core.model.Foot
import com.smartsocks.core.settings.DeviceChoice
import com.smartsocks.core.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlin.concurrent.Volatile

/**
 * What keeps a background recording running, by what is recorded (Android: the foreground service type, see
 * [keepAliveType]).
 */
enum class KeepAliveType {
    /**
     * A real sock is recorded and the platform's prerequisite for it is met (Android 14+: the granted Bluetooth
     * permission): Android's `connectedDevice`.
     */
    ConnectedDevice,

    /**
     * Only simulated feet (or the prerequisite is missing): Android's `specialUse`. No time limit, unlike `dataSync`
     * (6 h a day from Android 15), and it needs no runtime permission.
     */
    SpecialUse,
}

/**
 * The type for recording [devices]: [KeepAliveType.ConnectedDevice] only for a real sock with the permission it
 * needs ([bluetoothPermission]); a simulated foot alone is no connected device.
 */
fun keepAliveType(devices: Collection<DeviceChoice>, bluetoothPermission: Boolean): KeepAliveType {
    val realSock = devices.any { it is DeviceChoice.Bluetooth || it is DeviceChoice.SerialPort }
    return if (realSock && bluetoothPermission) KeepAliveType.ConnectedDevice else KeepAliveType.SpecialUse
}

/** What the recording notification's actions ask for; see [BackgroundRecording.perform]. */
enum class RecordingCommand { Mark, Stop }

/**
 * Keeps the process running while a recording runs in the background (Android: a foreground service with a wake
 * lock). Each [start] carries a request number; the platform reports back with it
 * ([BackgroundRecording.onKeepAliveStarted], [BackgroundRecording.onKeepAliveFailed],
 * [BackgroundRecording.onKeepAliveEnded]), so a late answer about an earlier recording is ignored.
 */
interface KeepAlive {
    /** Asks for it; must not throw (a refusal is reported with [BackgroundRecording.onKeepAliveFailed]). */
    fun start(type: KeepAliveType, request: Long)

    /** The recording is over: release it. */
    fun stop()

    companion object {
        /** No background recording (desktop, previews): leaving the foreground stops the recording. */
        val None: KeepAlive = object : KeepAlive {
            override fun start(type: KeepAliveType, request: Long) = Unit

            override fun stop() = Unit
        }
    }
}

/** What to do with the feet and the recording, see [BackgroundPolicy.decide]. */
enum class BackgroundAction {
    /** Stream as asked (foreground). */
    Run,

    /** Recording in the background: only the recorded feet stream, the others pause (battery). */
    RunRecorded,

    /** Close the links but keep what the user asked for (see [LiveController.setPaused]). */
    Pause,

    /** The recording cannot go on in the background: finalize it ([StopReason.Background]), then pause. */
    StopForBackground,

    /** In the background no recorded foot is linked (any more): finalize ([StopReason.LinksLost]), then pause. */
    StopLinksLost,
}

/** The decision behind [BackgroundRecording], as a pure function. */
object BackgroundPolicy {
    /**
     * [recording]: a recording is starting or running (not stopping). [keptAlive]: the platform keeps the process
     * running in the background for it. [recordedLinksUp]: some recorded foot is connecting or streaming.
     */
    fun decide(
        foreground: Boolean,
        recording: Boolean,
        keptAlive: Boolean,
        recordedLinksUp: Boolean,
    ): BackgroundAction = when {
        foreground -> BackgroundAction.Run
        !recording -> BackgroundAction.Pause
        !keptAlive -> BackgroundAction.StopForBackground
        !recordedLinksUp -> BackgroundAction.StopLinksLost
        else -> BackgroundAction.RunRecorded
    }
}

/**
 * Counts the process's started screens (Android: activities, from the Application's lifecycle callbacks): the app
 * is in the foreground while one is. A screen stopped for a configuration change is started again at once: that is
 * not leaving. [onChange] gets each change of foreground. Main thread only.
 */
class StartedScreens(private val onChange: (foreground: Boolean) -> Unit) {
    private var started = 0
    private var restarting = 0

    fun onStarted() {
        if (restarting > 0) {
            restarting--
            return
        }
        if (started++ == 0) onChange(true)
    }

    fun onStopped(changingConfigurations: Boolean) {
        if (changingConfigurations) {
            restarting++
            return
        }
        if (started > 0 && --started == 0) onChange(false)
    }
}

/**
 * Lets a recording go on while the app is in the background (Android). Without a recording the feet pause in the
 * background, as before (battery). When a recording starts (from the foreground) it asks [keepAlive] to keep the
 * process running, and releases it when the recording ends. In the background:
 * - no recording: pause the feet;
 * - recording, but not kept alive (the platform refused, or it ended): finalize the recording, then pause;
 * - recording and kept alive: only the recorded feet stream, until the user stops (UI or [perform]), or no recorded
 *   foot is linked (a link does not reconnect by itself): then finalize ([StopReason.LinksLost]) and pause.
 *
 * Until the first [onForeground] the policy pauses the feet (create [live] `initiallyPaused`, so that nothing
 * connects before). The owner (Android: the Application object) outlives the activity, so do [live], [recorder]
 * and this.
 */
class BackgroundRecording(
    private val live: LiveController,
    private val recorder: RecordingController,
    private val settings: SettingsRepository,
    private val keepAlive: KeepAlive,
    private val bluetoothPermission: () -> Boolean,
    scope: CoroutineScope,
) {
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val scope = scope + job

    private val foreground = MutableStateFlow(false)
    private val _keptAlive = MutableStateFlow(false)

    /** The platform keeps the process running for the current recording. */
    val keptAlive: StateFlow<Boolean> = _keptAlive.asStateFlow()

    /** The keep-alive request of the current recording; null while none is asked for. */
    @Volatile
    private var current: Long? = null
    private var lastRequest = 0L

    private val _lastType = MutableStateFlow<KeepAliveType?>(null)

    /** What [keepAlive] was last asked for (for tests and diagnostics). */
    val lastType: StateFlow<KeepAliveType?> = _lastType.asStateFlow()

    init {
        this.scope.launch { followRecording() }
        this.scope.launch { applyPolicy() }
    }

    /** A screen of the app is visible (Android: see [StartedScreens]). */
    fun onForeground() {
        foreground.value = true
    }

    /** No screen of the app is visible any more. */
    fun onBackground() {
        foreground.value = false
    }

    /** The platform keeps the process running for [request] (ignored unless that is the current one). */
    fun onKeepAliveStarted(request: Long) {
        if (request == current) _keptAlive.value = true
    }

    /** The platform refused [request] (e.g. no foreground service allowed now): it stops in the background. */
    fun onKeepAliveFailed(request: Long) {
        if (request == current) _keptAlive.value = false
    }

    /** It ended without [KeepAlive.stop] (e.g. the service was destroyed): as [onKeepAliveFailed]. */
    fun onKeepAliveEnded(request: Long) {
        if (request == current) _keptAlive.value = false
    }

    /** A notification action: the same as the Live screen's Mark and Stop. Returns the mark's label, if any. */
    fun perform(command: RecordingCommand): String? = when (command) {
        RecordingCommand.Mark -> recorder.mark()

        RecordingCommand.Stop -> {
            recorder.stop(StopReason.User)
            null
        }
    }

    /** Asks for the keep-alive when a recording runs, releases it when the recording's files are closed. */
    private suspend fun followRecording() {
        recorder.state
            .map { (it as? RecordingState.Recording)?.feet ?: if (it == RecordingState.Idle) emptySet() else null }
            .distinctUntilChanged()
            .collect { feet ->
                when {
                    // Starting or stopping.
                    feet == null -> Unit

                    feet.isNotEmpty() && current == null -> {
                        val type = typeFor(feet)
                        val request = ++lastRequest
                        _keptAlive.value = false
                        current = request
                        _lastType.value = type
                        keepAlive.start(type, request)
                    }

                    feet.isEmpty() && current != null -> {
                        current = null
                        _keptAlive.value = false
                        keepAlive.stop()
                    }
                }
            }
    }

    private fun typeFor(feet: Set<Foot>): KeepAliveType {
        val s = settings.settings.value
        return keepAliveType(feet.map { s.device(it) }, bluetoothPermission())
    }

    private suspend fun applyPolicy() {
        val linksUp = combine(live.left.status, live.right.status, recorder.state) { l, r, state ->
            val feet = (state as? RecordingState.Recording)?.feet ?: Foot.entries.toSet()
            (Foot.LEFT in feet && l.state.isActive) || (Foot.RIGHT in feet && r.state.isActive)
        }.distinctUntilChanged()
        val recording = recorder.state
            .map { it == RecordingState.Starting || it is RecordingState.Recording }
            .distinctUntilChanged()
        combine(foreground, recording, _keptAlive, linksUp, BackgroundPolicy::decide)
            .distinctUntilChanged()
            .collect { action ->
                when (action) {
                    BackgroundAction.Run -> live.setPaused(false)

                    BackgroundAction.RunRecorded -> {
                        val all = Foot.entries.toSet()
                        val recorded = (recorder.state.value as? RecordingState.Recording)?.feet ?: all
                        live.setPaused(all - recorded)
                    }

                    BackgroundAction.Pause -> live.setPaused(true)

                    BackgroundAction.StopForBackground -> stopThenPause(StopReason.Background)

                    BackgroundAction.StopLinksLost -> stopThenPause(StopReason.LinksLost)
                }
            }
    }

    /** Finalizes the file before the links close (and so before the process may go). */
    private suspend fun stopThenPause(reason: StopReason) {
        recorder.stopAndJoin(reason)
        live.setPaused(true)
    }

    companion object {
        /** Between two posts of the notification at least this long. */
        const val NOTIFICATION_GAP_MILLIS = 1_000L

        /** A change of the drop counters only (they may grow all the time) is shown at most this often. */
        const val NOTIFICATION_DROPS_GAP_MILLIS = 10_000L

        /**
         * The recording notification's text: feet, marks and drops, e.g. "Both feet · 2 marks · 12 samples
         * dropped". Changes only with those (not with the size), so the notification is not re-posted every update.
         */
        fun notificationText(state: RecordingState.Recording): String = buildList {
            add(feetText(state.feet))
            if (state.markers > 0) add(if (state.markers == 1) "1 mark" else "${state.markers} marks")
            if (state.dropped > 0) add("${state.dropped} samples dropped")
            if (state.droppedRaw > 0) add("${state.droppedRaw} raw packets dropped")
        }.joinToString(" · ")

        /**
         * How long to wait before posting the notification for [next] when [posted] was posted [sincePostMillis]
         * ago (null: nothing posted yet: now). Another recording, feet or mark: [NOTIFICATION_GAP_MILLIS] after the
         * last post; only the drop counters changed: [NOTIFICATION_DROPS_GAP_MILLIS] after it.
         */
        fun notificationDelayMillis(
            posted: RecordingState.Recording?,
            next: RecordingState.Recording,
            sincePostMillis: Long,
        ): Long {
            if (posted == null) return 0
            val visible = posted.id != next.id || posted.feet != next.feet || posted.markers != next.markers
            val gap = if (visible) NOTIFICATION_GAP_MILLIS else NOTIFICATION_DROPS_GAP_MILLIS
            return (gap - sincePostMillis).coerceAtLeast(0)
        }
    }
}
