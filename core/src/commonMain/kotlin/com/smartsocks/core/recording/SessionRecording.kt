package com.smartsocks.core.recording

import com.smartsocks.core.session.SockSession
import kotlinx.coroutines.flow.merge

/**
 * Collects [SockSession.states] into [recorder] until cancelled, then closes it (and its sink). Suspends forever
 * otherwise; launch it in the scope that owns the recording.
 */
suspend fun SockSession.recordTo(recorder: CsvRecorder) {
    try {
        states.collect { recorder.record(it) }
    } finally {
        recorder.close()
    }
}

/** Same for the raw packets of this session. */
suspend fun SockSession.recordRawTo(recorder: RawPacketRecorder) {
    try {
        rawPackets.collect { recorder.record(it) }
    } finally {
        recorder.close()
    }
}

/**
 * Records several sessions (e.g. both feet) into one CSV through a single collector, so [recorder] never sees
 * concurrent calls. Sessions should share a clock base for the interleaved times to be meaningful.
 */
suspend fun Collection<SockSession>.recordTo(recorder: CsvRecorder) {
    try {
        merge(*map { it.states }.toTypedArray()).collect { recorder.record(it) }
    } finally {
        recorder.close()
    }
}
