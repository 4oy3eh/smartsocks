package com.smartsocks.app.platform

import com.smartsocks.core.settings.AppSettings
import com.smartsocks.core.settings.SettingsCodec
import com.smartsocks.core.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Settings as a UTF-8 text file in the [SettingsCodec] format. A missing file means defaults; other I/O errors are
 * thrown (the repository then refuses to overwrite the file).
 *
 * A save writes a uniquely named temporary file next to the target, syncs it to disk and moves it over the old
 * file, so a crash or power loss mid-save leaves either the old or the new settings, never a truncated file. On
 * Windows an antivirus or indexer briefly holding the target makes the move fail with an access error: the move is
 * retried [moveRetries] times, [retryDelayMillis] apart.
 */
class FileSettingsStore internal constructor(
    val file: File,
    private val moveRetries: Int,
    private val retryDelayMillis: Long,
    private val move: (from: Path, to: Path) -> Unit,
) : SettingsStore {
    constructor(file: File) : this(file, DEFAULT_MOVE_RETRIES, DEFAULT_RETRY_DELAY_MILLIS, ::replaceFile)

    override suspend fun load(): AppSettings = withContext(Dispatchers.IO) {
        if (!file.exists()) AppSettings() else SettingsCodec.decode(file.readText(Charsets.UTF_8))
    }

    override suspend fun save(settings: AppSettings): Unit = withContext(Dispatchers.IO) {
        val dir = file.absoluteFile.parentFile
        if (dir != null && !dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
        val tmp = File.createTempFile(file.name + ".", ".tmp", dir)
        try {
            FileOutputStream(tmp).use { out ->
                out.write(SettingsCodec.encode(settings).toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            moveWithRetries(tmp.toPath(), file.toPath())
        } finally {
            // Gone after a successful move; after a failure it must not pile up.
            tmp.delete()
        }
    }

    private suspend fun moveWithRetries(from: Path, to: Path) {
        var attempt = 0
        while (true) {
            try {
                move(from, to)
                return
            } catch (e: FileSystemException) {
                if (attempt++ >= moveRetries) throw e
                delay(retryDelayMillis)
            }
        }
    }

    internal companion object {
        const val DEFAULT_MOVE_RETRIES = 3
        const val DEFAULT_RETRY_DELAY_MILLIS = 75L

        /** Atomic replace where the file system supports it, a plain replace otherwise. */
        fun replaceFile(from: Path, to: Path) {
            try {
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
