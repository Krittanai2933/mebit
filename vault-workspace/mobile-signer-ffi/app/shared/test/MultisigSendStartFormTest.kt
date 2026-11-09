package com.mebit.app.data

import kotlin.test.Test
import kotlin.test.assertEquals

class MultisigSendStartFormTest {
    @Test
    fun startFormEmptiesTheRecipientAndTheAmount() {
        val d = MultisigSendDraft()
        assertEquals(100_000L, d.amountSats, "the draft starts with the demo amount")
        d.startForm()
        assertEquals("", d.destination)
        assertEquals(0L, d.amountSats)
        assertEquals(null, d.lastError)
    }
}
