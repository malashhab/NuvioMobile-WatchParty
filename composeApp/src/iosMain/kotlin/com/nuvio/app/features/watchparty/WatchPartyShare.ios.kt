package com.nuvio.app.features.watchparty

import androidx.compose.runtime.Composable

// Sharing isn't wired up on iOS yet; the room code can still be copied.
@Composable
actual fun rememberTextSharer(): ((String) -> Unit)? = null
