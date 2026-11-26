package com.mebit.app.data

import com.mebit.app.PlainFile
import com.mebit.ffi.signer.KeyNetwork
import kotlin.test.Test
import kotlin.test.assertEquals

class NetworkSettingsTest {
    private class MemoryFile(var text: String? = "") : PlainFile {
        override fun read() = text
        override fun write(text: String): Boolean { this.text = text; return true }
        override fun delete(): Boolean { text = ""; return true }
    }

    @Test
    fun theDefaultIsTestnetAndAChoiceRoundTrips() {
        val file = MemoryFile()
        val settings = NetworkSettings(file)
        assertEquals(KeyNetwork.TEST, settings.current)
        settings.choose(KeyNetwork.MAIN)
        assertEquals("main", file.text)
        val after = NetworkSettings(file).also { it.restore() }
        assertEquals(KeyNetwork.MAIN, after.current)
        after.choose(KeyNetwork.TEST)
        assertEquals(KeyNetwork.TEST, NetworkSettings(file).also { it.restore() }.current)
    }

    @Test
    fun onlyAStoredMainIsMainnet() {
        listOf("", "  ", "Main", "mainnet", "test", "testnet4", null).forEach {
            assertEquals(KeyNetwork.TEST, NetworkSettings(MemoryFile(it)).also { s -> s.restore() }.current, "$it")
        }
        assertEquals(KeyNetwork.MAIN, NetworkSettings(MemoryFile(" main\n")).also { it.restore() }.current)
        assertEquals(KeyNetwork.TEST, NetworkSettings(null).also { it.restore() }.current)
    }

    @Test
    fun noFileKeepsTheChoiceInMemory() {
        val settings = NetworkSettings(null)
        settings.choose(KeyNetwork.MAIN)
        assertEquals(KeyNetwork.MAIN, settings.current)
    }

    // Until the records take mainnet (phase 3 widens SavedWallet.NETWORKS, and must change this test), a stored "main" leaves the
    // gate on testnet: opening mainnet storage it cannot read would show the recovery screen, whose one button erases it.
    @Test
    fun theGateOpensOnlyANetworkThisBuildCanRead() {
        assertEquals(KeyNetwork.TEST, openableNetwork(KeyNetwork.TEST))
        assertEquals(KeyNetwork.TEST, openableNetwork(KeyNetwork.MAIN))
    }
}
