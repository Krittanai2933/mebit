package com.mebit.app.data

import com.mebit.app.PlainFile
import kotlin.test.Test
import kotlin.test.assertEquals

class BtcUnitTest {
    private class MemoryFile(var text: String? = "") : PlainFile {
        override fun read() = text
        override fun write(text: String): Boolean { this.text = text; return true }
        override fun delete(): Boolean { text = ""; return true }
    }

    @Test
    fun satsAreWholeAndGrouped() {
        assertEquals("0", amountNumber(0, BtcUnit.Sat))
        assertEquals("15,653", amountNumber(15_653, BtcUnit.Sat))
        assertEquals("100,000,000", amountNumber(100_000_000, BtcUnit.Sat))
        assertEquals("2,100,000,000,000,000", amountNumber(2_100_000_000_000_000, BtcUnit.Sat))
        assertEquals("15,653 sat", amountText(15_653, BtcUnit.Sat))
    }

    @Test
    fun btcIsWhatTheFormatterMakes() {
        listOf(0L, 1L, 500_000L, 3_118_872L, 100_000_000L).forEach {
            assertEquals(formatBtcCompact(it), amountNumber(it, BtcUnit.Btc))
            assertEquals("${formatBtcCompact(it)} BTC", amountText(it, BtcUnit.Btc))
        }
        assertEquals("0.00501128 BTC", amountText(501_128, BtcUnit.Btc, ::formatBtc))
    }

    @Test
    fun theDefaultIsBtcAndAChoiceRoundTrips() {
        val file = MemoryFile()
        val settings = UnitSettings(file)
        assertEquals(BtcUnit.Btc, settings.current)
        settings.choose(BtcUnit.Sat)
        val after = UnitSettings(file).also { it.restore() }
        assertEquals(BtcUnit.Sat, after.current)
        after.choose(BtcUnit.Btc)
        assertEquals(BtcUnit.Btc, UnitSettings(file).also { it.restore() }.current)
    }

    @Test
    fun anUnknownMissingOrUnreadableValueIsBtc() {
        listOf("", "  ", "msat", "Sat", null).forEach {
            assertEquals(BtcUnit.Btc, UnitSettings(MemoryFile(it)).also { s -> s.restore() }.current, "$it")
        }
        assertEquals(BtcUnit.Btc, UnitSettings(null).also { it.restore() }.current)
    }

    @Test
    fun noFileKeepsTheChoiceInMemory() {
        val settings = UnitSettings(null)
        settings.choose(BtcUnit.Sat)
        assertEquals(BtcUnit.Sat, settings.current)
    }
}
