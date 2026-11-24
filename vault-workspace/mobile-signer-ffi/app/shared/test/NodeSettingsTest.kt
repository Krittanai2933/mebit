package com.mebit.app.data

import com.mebit.app.PlainFile
import com.mebit.app.encodeQr
import com.mebit.app.feature.settings.typedBase
import com.mebit.ffi.signer.NodeChoice
import kotlin.test.Test
import kotlin.test.assertEquals

class NodeSettingsTest {
    private class MemoryFile(var text: String = "") : PlainFile {
        override fun read() = text
        override fun write(text: String): Boolean { this.text = text; return true }
        override fun delete(): Boolean { text = ""; return true }
    }

    @Test
    fun everySettingRoundTrips() {
        listOf(NodeSetting.Auto, NodeSetting.Blockstream, NodeSetting.MempoolSpace, NodeSetting.Own("https://n.example.org/api"))
            .forEach { assertEquals(it, decodeNodeSetting(it.encode()), it.toString()) }
    }

    @Test
    fun theSlipLinksTheTxidOnTheChosenNodesExplorer() {
        val txid = "e089b6d14c7a9f032e58d1a76bc40f927e3d85a1c6f04b29d8731e6c0a955a2f"
        assertEquals("https://blockstream.info/testnet4/tx/$txid", NodeSetting.Auto.txPageUrl(txid))
        assertEquals("https://blockstream.info/testnet4/tx/$txid", NodeSetting.Blockstream.txPageUrl(txid))
        assertEquals("https://mempool.space/testnet4/tx/$txid", NodeSetting.MempoolSpace.txPageUrl(txid))
        assertEquals("https://node.example.org/testnet4/tx/$txid", NodeSetting.Own("https://node.example.org/testnet4/api").txPageUrl(txid))
        assertEquals("https://node.example.org:3000/tx/$txid", NodeSetting.Own("https://node.example.org:3000/api/").txPageUrl(txid))
        assertEquals("http://100.64.0.1:3002/tx/$txid", NodeSetting.Own("http://100.64.0.1:3002").txPageUrl(txid))
        // The slip draws an empty tile when the link does not fit a QR, so the public links must.
        listOf(NodeSetting.Blockstream, NodeSetting.MempoolSpace).forEach { encodeQr(it.txPageUrl(txid)) }
    }

    @Test
    fun anUnknownOrEmptyValueIsAuto() {
        listOf(null, "", "  ", "tor", "own", "own ", "Blockstream").forEach { assertEquals(NodeSetting.Auto, decodeNodeSetting(it), "$it") }
    }

    @Test
    fun aChoiceIsKeptAndPutOnTheCoreAndRestoredAtStart() {
        val file = MemoryFile()
        val used = mutableListOf<NodeChoice>()
        val settings = NodeSettings(file) { used += it }
        assertEquals(true, settings.choose(NodeSetting.MempoolSpace))
        assertEquals(listOf<NodeChoice>(NodeChoice.MempoolSpace), used)
        val after = NodeSettings(file) { used += it }
        after.restore()
        assertEquals(NodeSetting.MempoolSpace, after.current)
        assertEquals(NodeChoice.MempoolSpace, used.last())
    }

    @Test
    fun aChoiceTheCoreRefusesChangesNothing() {
        val file = MemoryFile("auto")
        val settings = NodeSettings(file) { throw IllegalStateException("refused") }
        assertEquals(false, settings.choose(NodeSetting.Own("http://n.org")))
        assertEquals(NodeSetting.Auto, settings.current)
        assertEquals("auto", file.text)
        // A stored node the core now refuses leaves Auto in force.
        file.text = "own http://n.org"
        settings.restore()
        assertEquals(NodeSetting.Auto, settings.current)
    }

    @Test
    fun aTypedUrlLosesSpacesAndTheClosingSlash() {
        assertEquals("https://a.org/api", typedBase(" https://a.org/api/ "))
    }
}
