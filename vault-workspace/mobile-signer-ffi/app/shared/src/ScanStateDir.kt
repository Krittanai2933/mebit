package com.mebit.app

import androidx.compose.runtime.Composable

/**
 * The folder the core keeps each wallet's scan file in, made when missing, or null when the platform gives none. App storage
 * that no backup copies and outside the secure store, because the file holds no key, address or amount, and the chain gives all of it back.
 */
@Composable
internal expect fun rememberScanStateDir(): String?
