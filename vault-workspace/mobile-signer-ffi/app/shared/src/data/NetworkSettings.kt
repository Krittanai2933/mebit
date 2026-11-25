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

/** The text of Welcome's chip, Penpot 1.1.1 and 1.1.2. */
internal fun networkChip(network: KeyNetwork): String = chainName(network) + " ›"

/**
 * The network whose storage the gate opens: [chosen], unless this build cannot read that network's records. Such a build would
 * find every record Broken, and the recovery screen's one button erases them, so it stays on testnet and never opens the other.
 */
internal fun openableNetwork(chosen: KeyNetwork): KeyNetwork = if (chosen in SavedWallet.NETWORKS) chosen else KeyNetwork.TEST

/** What a tap on one side of 7.1's network switch does. */
enum class NetworkTap { Stay, Refuse, Ask, Switch }

/**
 * Pure. The picked side does nothing. A switch while [refused] shows the reason. Mainnet asks first (7.1.1), and Testnet4 is
 * taken straight away.
 */
fun networkTap(current: KeyNetwork, tapped: KeyNetwork, refused: Boolean): NetworkTap = when {
    tapped == current -> NetworkTap.Stay
    refused -> NetworkTap.Refuse
    tapped == KeyNetwork.MAIN -> NetworkTap.Ask
    else -> NetworkTap.Switch
}

/**
 * Pure. Why the network cannot change now, or null. A switch drops every wallet's words from memory, so it waits while some are
 * only in memory ([wordsOnlyInMemory]: the phone could not keep them) or the book's last save failed. Awaiting design review, no board.
 */
internal fun networkRefusal(wordsOnlyInMemory: Boolean, book: VaultsHealth): String? = when {
    wordsOnlyInMemory -> "เครื่องนี้ยังบันทึกกระเป๋าไม่ได้ ตั้งรหัสล็อกหน้าจอก่อน จึงจะเปลี่ยนเครือข่ายได้"
    book == VaultsHealth.SaveFailed -> "บันทึก Wallet และกุญแจล่าสุดไม่สำเร็จ ปิดแล้วเปิดแอปใหม่ก่อนเปลี่ยนเครือข่าย"
    else -> null
}
