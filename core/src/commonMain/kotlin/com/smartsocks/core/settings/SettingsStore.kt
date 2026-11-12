package com.smartsocks.core.settings

/** Where [AppSettings] live between runs. Platform stores (a file in the app's data directory) live in the app. */
interface SettingsStore {
    /** The stored settings, or the defaults if there are none. Should not throw on a damaged file. */
    suspend fun load(): AppSettings

    suspend fun save(settings: AppSettings)
}

/** Keeps the encoded text in memory, exactly as a file store would write it. For tests and previews. */
class InMemorySettingsStore(initialText: String? = null) : SettingsStore {
    /** What was saved last ([SettingsCodec] format), or null if nothing was. */
    var text: String? = initialText
        private set

    var saveCount: Int = 0
        private set

    override suspend fun load(): AppSettings = text?.let(SettingsCodec::decode) ?: AppSettings()

    override suspend fun save(settings: AppSettings) {
        text = SettingsCodec.encode(settings)
        saveCount++
    }
}
