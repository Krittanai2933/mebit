package com.mebit.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mebit.app.PlainFile
import com.mebit.ffi.signer.KeyNetwork

/**
 * The network the whole app works on, Penpot 7.1, and its file. [file] is null where the phone has no place for it, and then a choice
 * lasts until the app closes. Choosing never touches either network's data: each has its own storage ([com.mebit.app.folder]).
 */
class NetworkSettings internal constructor(private val file: PlainFile?) {
    var current: KeyNetwork by mutableStateOf(KeyNetwork.TEST)
        private set

    /** Reads the file. Only a stored "main" is mainnet, so a missing or unreadable file, or a value a later build wrote, is testnet, as every build ran before. */
    fun restore() {
        current = if (file?.read()?.trim() == MAIN_TEXT) KeyNetwork.MAIN else KeyNetwork.TEST
    }

    fun choose(network: KeyNetwork) {
        current = network
        file?.write(if (network == KeyNetwork.MAIN) MAIN_TEXT else "test")
    }

    private companion object {
        const val MAIN_TEXT = "main"
    }
}

/** "testnet" or "mainnet": the word the app's lower-case lines use. */
internal fun networkWord(network: KeyNetwork): String = if (network == KeyNetwork.MAIN) "mainnet" else "testnet"

/** "Mainnet" or "Testnet4": the chain's name as the chip, the switch and the node texts print it. */
internal fun chainName(network: KeyNetwork): String = if (network == KeyNetwork.MAIN) "Mainnet" else "Testnet4"

/**
 * The network whose storage the gate opens: [chosen], unless this build cannot read that network's records. Such a build would
 * find every record Broken, and the recovery screen's one button erases them, so it stays on testnet and never opens the other.
 */
internal fun openableNetwork(chosen: KeyNetwork): KeyNetwork = if (chosen in SavedWallet.NETWORKS) chosen else KeyNetwork.TEST
