package com.mebit.app.data

import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun theCardsPillSaysConfirmedOrNot() {
        assertEquals("ยืนยันแล้ว", keyBadge(true))
        assertEquals("ยังไม่ยืนยัน", keyBadge(false))
    }
}
