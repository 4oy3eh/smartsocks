package com.smartsocks.app.ui

import androidx.compose.runtime.Composable

/** System back (Android); no-op on desktop. */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)

/** Keeps the display on while [keepScreenOn] and matches the system bar icons to the theme (Android). */
@Composable
expect fun PlatformWindowEffects(darkTheme: Boolean, keepScreenOn: Boolean)
