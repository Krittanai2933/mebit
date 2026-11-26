package com.mebit.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mebit.app.PlainFile
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.NodeChoice
import com.mebit.ffi.signer.chainUseNode

/** The Esplora node the wallet reads from, Penpot 7.2. Kept in a plain file, because a URL the user typed is theirs and no secret. */
sealed interface NodeSetting {
    data object Auto : NodeSetting
    data object Blockstream : NodeSetting
    data object MempoolSpace : NodeSetting

    /** [base] has passed the test on this phone: the screen lets the card be chosen only then. */
    data class Own(val base: String) : NodeSetting
}

internal fun NodeSetting.encode(): String = when (this) {
    NodeSetting.Auto -> "auto"
    NodeSetting.Blockstream -> "blockstream"
    NodeSetting.MempoolSpace -> "mempool"
    is NodeSetting.Own -> "own $base"
}

/** What [encode] wrote. Anything else, an empty file or a name a later build wrote, is Auto, which is what every build did before. */
internal fun decodeNodeSetting(text: String?): NodeSetting {
    val line = text?.trim().orEmpty()
    return when {
        line == "blockstream" -> NodeSetting.Blockstream
        line == "mempool" -> NodeSetting.MempoolSpace
        line.startsWith("own ") && line.length > 4 -> NodeSetting.Own(line.substring(4).trim())
        else -> NodeSetting.Auto
    }
}

internal fun NodeSetting.toChoice(): NodeChoice = when (this) {
    NodeSetting.Auto -> NodeChoice.Auto
    NodeSetting.Blockstream -> NodeChoice.Blockstream
    NodeSetting.MempoolSpace -> NodeChoice.MempoolSpace
    is NodeSetting.Own -> NodeChoice.Own(base)
}

/**
 * The explorer page of [txid] on this node, which the slip's QR opens. Auto names blockstream.info, the first public host it tries: the
 * build's own node, which Auto may try first, is private, and a slip is shared with people who cannot reach it. An own node on Tailscale
 * names blockstream.info too, for the same reason.
 */
fun NodeSetting.txPageUrl(txid: String): String = when (this) {
    NodeSetting.Auto, NodeSetting.Blockstream -> "https://blockstream.info/testnet4/tx/$txid"
    NodeSetting.MempoolSpace -> "https://mempool.space/testnet4/tx/$txid"
    is NodeSetting.Own ->
        if (onTailnet(base)) NodeSetting.Blockstream.txPageUrl(txid)
        // shortcut: assumes the node serves its explorer's web pages beside its /api, as Esplora and mempool do; an API-only node gives a dead link. Add a page URL to the setting when one shows up.
        else base.trimEnd('/').removeSuffix("/api") + "/tx/$txid"
}

/** A host only the user's own tailnet reaches: 100.64.0.0/10 or `*.ts.net`, the Tailscale addresses the core lets an own node use. */
private fun onTailnet(base: String): Boolean {
    val host = base.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
    val octets = host.split('.').map { it.toIntOrNull() }
    return host.endsWith(".ts.net") || (octets.size == 4 && octets[0] == 100 && (octets[1] ?: -1) in 64..127)
}

/** The node setting and its file. [file] is null where the phone has no place for it, and then a choice lasts until the app closes. */
class NodeSettings internal constructor(private val file: PlainFile?, private val use: (NodeChoice) -> Unit = { chainUseNode(it, KeyNetwork.TEST) }) {
    var current: NodeSetting by mutableStateOf(NodeSetting.Auto)
        private set

    /**
     * Reads the file and puts the core on it. Call once before any screen asks the core for a chain read. A stored node the core
     * refuses (a URL its rules no longer allow) leaves Auto in force.
     */
    fun restore() {
        val stored = decodeNodeSetting(file?.read())
        current = if (runCatching { use(stored.toChoice()) }.isSuccess) stored else NodeSetting.Auto
    }

    /** Puts the core on [setting], then keeps it. False when the core refuses it, and then nothing changed. */
    fun choose(setting: NodeSetting): Boolean {
        if (runCatching { use(setting.toChoice()) }.isFailure) return false
        current = setting
        file?.write(setting.encode())
        return true
    }
}
