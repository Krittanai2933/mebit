@file:OptIn(ExperimentalForeignApi::class)

package com.mebit.app

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfURL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ExportFileIosTest {
    private fun read(name: String, text: String): Pair<String?, String?> {
        val url = assertNotNull(writeExportFile(name, text))
        return url.lastPathComponent to NSString.stringWithContentsOfURL(url, encoding = NSUTF8StringEncoding, error = null)
    }

    @Test
    fun theFileKeepsItsNameAndHoldsTheText() {
        assertEquals("Wallet_2.bsms" to "BSMS 1.0\nwsh(sortedmulti(2,x))", read("Wallet_2.bsms", "BSMS 1.0\nwsh(sortedmulti(2,x))"))
    }

    @Test
    fun aSecondExportReplacesTheFirstCopy() {
        read("Wallet_3.bsms", "a much longer first text")
        assertEquals("Wallet_3.bsms" to "short", read("Wallet_3.bsms", "short"))
    }

    @Test
    fun thaiTextSurvivesTheRoundTrip() {
        assertEquals("Wallet_4.bsms" to "# mebit · กุญแจ", read("Wallet_4.bsms", "# mebit · กุญแจ"))
    }
}
