package com.mebit.app.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.mebit.app.rememberSecureStore

/** The wallet saved on this phone, over this platform's store. */
@Composable
fun rememberSavedWallet(): SavedWallet {
    val store = rememberSecureStore()
    return remember(store) { SavedWallet(store) }
}
