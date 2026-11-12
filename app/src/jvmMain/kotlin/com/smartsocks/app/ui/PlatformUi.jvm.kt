package com.smartsocks.app.ui

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit

@Composable
actual fun PlatformWindowEffects(darkTheme: Boolean, keepScreenOn: Boolean) = Unit
