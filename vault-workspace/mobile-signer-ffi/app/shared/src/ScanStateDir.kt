package com.mebit.app

import androidx.compose.runtime.Composable

/**
 * The folder the core keeps each wallet's scan file in, and the app its pay-twice guard file ([com.mebit.app.data.PendingPayments]),
 * or null when the platform gives none. App storage that no backup copies and outside the secure store, because neither file holds
 * a key, address or amount: the chain gives the scans back, and the guards are txids and times.
 */
@Composable
internal expect fun rememberScanStateDir(): String?
