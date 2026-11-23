package com.mebit.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyringTextTest {
    private fun key(id: String, verified: Boolean, source: KeySourceLabel = KeySourceLabel.Jade) =
        SavedKey(id, id, "7f2ca91b", "xpub", "m/48'/1'/0'/2'", source, verified)

    @Test
    fun unconfirmedKeysComeFirstAndEachGroupKeepsItsOrder() {
        val keys = listOf(key("a", true), key("b", false), key("c", true), key("d", false), key("e", true))
        assertEquals(listOf("b", "d", "a", "c", "e"), keyringOrder(keys).map { it.id })
        assertEquals(listOf("a", "b", "c"), keyringOrder(listOf(key("a", true), key("b", true), key("c", true))).map { it.id })
        assertEquals(emptyList(), keyringOrder(emptyList()))
    }

    @Test
    fun aTileReadsThePhonesTextForAKeyMadeHereMBForAnotherMebitPhoneAndNoLettersForADevice() {
        assertEquals("iOS", keyMonogram(KeySourceLabel.ThisDevice, phone = "iOS"))
        assertEquals("AND", keyMonogram(KeySourceLabel.ThisDevice, phone = "AND"))
        assertEquals("MB", keyMonogram(KeySourceLabel.MebitOther, phone = "iOS"))
        assertNull(keyMonogram(KeySourceLabel.Jade, phone = "iOS"))
        assertNull(keyMonogram(KeySourceLabel.TrezorSafe7, phone = "AND"))
    }

    @Test
    fun theCardsPillSaysConfirmedOrNot() {
        assertEquals("ยืนยันแล้ว", keyBadge(true))
        assertEquals("ยังไม่ยืนยัน", keyBadge(false))
    }
}
