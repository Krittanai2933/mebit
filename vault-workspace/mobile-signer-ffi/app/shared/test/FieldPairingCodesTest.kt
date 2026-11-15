package com.mebit.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class FieldPairingCodesTest {
    @Test
    fun jadeReturnsNullAndLeavesTheField() = runBlocking {
        var field = "123456"
        val codes = FieldPairingCodes(jade = true, read = { field }, consume = { field = "" })
        assertNull(codes.pairingCode())
        assertEquals("123456", field)
    }

    @Test
    fun trezorWaitsForSixDigitsThenClearsThem() = runBlocking {
        var field = "12"
        val codes = FieldPairingCodes(jade = false, read = { field }, consume = { field = "" })
        launch {
            delay(150)
            field = "654321"
        }
        assertEquals("654321", withTimeout(2_000) { codes.pairingCode() })
        assertEquals("", field)
        launch {
            delay(150)
            field = "000111"
        }
        assertEquals("000111", withTimeout(2_000) { codes.pairingCode() })
    }

    @Test
    fun onlyASafe7AskingForItsCodeTellsTheScreen() = runBlocking {
        var asked = 0
        val jade = FieldPairingCodes(jade = true, read = { "123456" }, consume = {}, onAsked = { asked++ })
        assertNull(jade.pairingCode())
        assertEquals(0, asked, "a Jade never asks")
        val safe7 = FieldPairingCodes(jade = false, read = { "123456" }, consume = {}, onAsked = { asked++ })
        assertEquals("123456", withTimeout(2_000) { safe7.pairingCode() })
        assertEquals(1, asked)
    }

    @Test
    fun multisigAccountPathIsTestnetBip48() {
        assertEquals("m/48'/1'/0'/2'", TESTNET_MULTISIG_ACCOUNT)
    }
}
