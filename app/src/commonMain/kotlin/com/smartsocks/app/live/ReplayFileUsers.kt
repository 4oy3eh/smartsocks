package com.smartsocks.app.live

import com.smartsocks.app.recording.SessionFileUsers
import com.smartsocks.core.model.Foot
import com.smartsocks.core.settings.AppSettings
import com.smartsocks.core.settings.DeviceChoice
import com.smartsocks.core.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The feet that replay a recording hold its raw file open: before the recording is deleted or renamed they are
 * disconnected (and waited for until their links are down), and after a rename their device names the new id.
 */
class ReplayFileUsers(
    private val live: LiveController,
    private val settings: SettingsRepository,
    private val timeoutMillis: Long = RELEASE_TIMEOUT_MILLIS,
) : SessionFileUsers {
    override suspend fun release(id: String) {
        val feet = Foot.entries.filter { (settings.settings.value.device(it) as? DeviceChoice.Replay)?.sessionId == id }
        for (foot in feet) live.link(foot).disconnect()
        withTimeoutOrNull(timeoutMillis) {
            for (foot in feet) {
                live.link(foot).status.first { !it.state.isActive && it.state !is LinkState.Reconnecting }
            }
        }
    }

    override fun renamed(oldId: String, newId: String) {
        settings.update { it.withReplayRenamed(oldId, newId) }
    }

    companion object {
        const val RELEASE_TIMEOUT_MILLIS = 2_000L
    }
}

/** Every foot replaying recording [oldId] replays [newId] instead. */
fun AppSettings.withReplayRenamed(oldId: String, newId: String): AppSettings = Foot.entries.fold(this) { s, foot ->
    val d = s.device(foot)
    if (d is DeviceChoice.Replay && d.sessionId == oldId) s.withDevice(foot, d.copy(sessionId = newId)) else s
}
