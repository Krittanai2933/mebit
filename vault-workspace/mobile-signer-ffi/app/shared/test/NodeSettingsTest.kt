package com.mebit.app.data

import com.mebit.app.PlainFile
import com.mebit.app.encodeQr
import com.mebit.app.feature.settings.typedBase
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.NodeCheck
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
        assertEquals("https://blockstream.info/testnet4/tx/$txid", NodeSetting.Auto.txPageUrl(txid, KeyNetwork.TEST))
        assertEquals("https://blockstream.info/testnet4/tx/$txid", NodeSetting.Blockstream.txPageUrl(txid, KeyNetwork.TEST))
        assertEquals("https://mempool.space/testnet4/tx/$txid", NodeSetting.MempoolSpace.txPageUrl(txid, KeyNetwork.TEST))
        assertEquals("https://node.example.org/testnet4/tx/$txid", NodeSetting.Own("https://node.example.org/testnet4/api").txPageUrl(txid, KeyNetwork.TEST))
        assertEquals("https://node.example.org:3000/tx/$txid", NodeSetting.Own("https://node.example.org:3000/api/").txPageUrl(txid, KeyNetwork.TEST))
        // A Tailscale node is unreachable to whoever gets the slip, so its QR names the public explorer.
        listOf("http://100.64.0.1:3002", "http://100.127.255.254/api", "https://box.tail1234.ts.net/api")
            .forEach { assertEquals("https://blockstream.info/testnet4/tx/$txid", NodeSetting.Own(it).txPageUrl(txid, KeyNetwork.TEST), it) }
        assertEquals("https://100.128.0.1/tx/$txid", NodeSetting.Own("https://100.128.0.1/api").txPageUrl(txid, KeyNetwork.TEST))
        // A mainnet send links the explorers' mainnet pages, so the QR never opens testnet4 for a mainnet txid.
        assertEquals("https://blockstream.info/tx/$txid", NodeSetting.Auto.txPageUrl(txid, KeyNetwork.MAIN))
        assertEquals("https://blockstream.info/tx/$txid", NodeSetting.Blockstream.txPageUrl(txid, KeyNetwork.MAIN))
        assertEquals("https://mempool.space/tx/$txid", NodeSetting.MempoolSpace.txPageUrl(txid, KeyNetwork.MAIN))
        assertEquals("https://blockstream.info/tx/$txid", NodeSetting.Own("http://100.64.0.1:3002").txPageUrl(txid, KeyNetwork.MAIN))
        assertEquals("https://node.example.org/tx/$txid", NodeSetting.Own("https://node.example.org/api").txPageUrl(txid, KeyNetwork.MAIN))
        // The slip draws an empty tile when the link does not fit a QR, so the public links must.
        listOf(NodeSetting.Blockstream, NodeSetting.MempoolSpace).forEach { encodeQr(it.txPageUrl(txid, KeyNetwork.TEST)) }
    }

    @Test
    fun anUnknownOrEmptyValueIsAuto() {
        listOf(null, "", "  ", "tor", "own", "own ", "Blockstream").forEach { assertEquals(NodeSetting.Auto, decodeNodeSetting(it), "$it") }
    }

    @Test
    fun aChoiceIsKeptAndPutOnTheCoreAndRestoredAtStart() {
        val file = MemoryFile()
        val used = mutableListOf<NodeChoice>()
        val settings = NodeSettings(file, KeyNetwork.TEST) { used += it }
        assertEquals(true, settings.choose(NodeSetting.MempoolSpace))
        assertEquals(listOf<NodeChoice>(NodeChoice.MempoolSpace), used)
        val after = NodeSettings(file, KeyNetwork.TEST) { used += it }
        after.restore()
        assertEquals(NodeSetting.MempoolSpace, after.current)
        assertEquals(NodeChoice.MempoolSpace, used.last())
    }

    @Test
    fun aNodePassesOnlyOnItsOwnNetwork() {
        assertEquals(listOf(true, false, false, false), listOf(NodeCheck.Testnet4, NodeCheck.Mainnet, NodeCheck.OtherChain, NodeCheck.BadUrl).map { it.passes(KeyNetwork.TEST) })
        assertEquals(listOf(false, true, false, false), listOf(NodeCheck.Testnet4, NodeCheck.Mainnet, NodeCheck.OtherChain, NodeCheck.BadUrl).map { it.passes(KeyNetwork.MAIN) })
        assertEquals(false, NodeCheck.Unreachable("down").passes(KeyNetwork.MAIN))
    }

    @Test
    fun aChoiceTheCoreRefusesChangesNothing() {
        val file = MemoryFile("auto")
        val settings = NodeSettings(file, KeyNetwork.TEST) { throw IllegalStateException("refused") }
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
