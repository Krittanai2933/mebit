package com.mebit.app.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mebit.app.PlainFile
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

/** The node setting and its file. [file] is null where the phone has no place for it, and then a choice lasts until the app closes. */
class NodeSettings internal constructor(private val file: PlainFile?, private val use: (NodeChoice) -> Unit = ::chainUseNode) {
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
