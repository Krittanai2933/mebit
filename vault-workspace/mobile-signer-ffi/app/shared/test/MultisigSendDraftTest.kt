package com.mebit.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The signer threshold of one vault send. Each case after the first is a way a broadcast used to happen with too few vault signers. */
class MultisigSendDraftTest {
    private val vaultA = vault("vault_a", m = 2, keys = listOf("73c5da0a", "2f1a9b3c", "e4d5c6b7"))
    private val vaultB = vault("vault_b", m = 2, keys = listOf("0a0a0a0a", "0b0b0b0b"))

    @Test
    fun twoDistinctVaultKeysReachTheThreshold() {
        val d = MultisigSendDraft()
        d.resetSignatures(vaultA)
        assertTrue(d.markSigned("73c5da0a"))
        assertFalse(d.readyToBroadcast(), "one signer of two")
        assertEquals(1, d.signaturesNeeded())
        assertTrue(d.markSigned("2F1A9B3C"))
        assertTrue(d.readyToBroadcast())
        assertTrue(d.broadcastMock())
        assertTrue(d.broadcastTxid.isNotBlank())
    }

    @Test
    fun keysOutsideTheVaultNeverCount() {
        val d = MultisigSendDraft()
        d.resetSignatures(vaultA)
        assertFalse(d.markSigned("DEMO0001"))
        assertFalse(d.markSigned("DEMO0002"))
        assertFalse(d.markSigned("JADEDEMO"))
        assertEquals(emptyList(), d.signedFingerprints.toList())
        assertEquals(2, d.signaturesNeeded(), "no vault key has signed")
        assertFalse(d.readyToBroadcast())
        assertFalse(d.broadcastMock())
        assertEquals("", d.broadcastTxid)
    }

    @Test
    fun oneKeySignedTwiceCountsOnce() {
        val d = MultisigSendDraft()
        d.resetSignatures(vaultA)
        d.markSigned("73c5da0a")
        d.markSigned("73C5DA0A")
        d.markSigned("73c5da0a")
        assertEquals(listOf("73C5DA0A"), d.signedFingerprints.toList())
        assertFalse(d.readyToBroadcast())
        assertFalse(d.broadcastMock())
    }

    @Test
    fun nothingCountsBeforeASendIsStarted() {
        val d = MultisigSendDraft()
        assertFalse(d.markSigned("73c5da0a"))
        assertFalse(d.readyToBroadcast(), "no vault is bound, so there is no threshold to meet")
        assertFalse(d.broadcastMock())
    }

    @Test
    fun startingAnotherVaultDropsTheFirstVaultsSigners() {
        val d = MultisigSendDraft()
        d.resetSignatures(vaultA)
        d.markSigned("73c5da0a")
        d.markSigned("2f1a9b3c")
        d.resetSignatures(vaultB)
        assertFalse(d.markSigned("73c5da0a"), "vault A's key is not a vault B key")
        assertFalse(d.markSigned("2f1a9b3c"))
        assertEquals(emptyList(), d.signedFingerprints.toList())
        assertFalse(d.readyToBroadcast(), "vault A's signers are not vault B's")
        assertFalse(d.broadcastMock())
    }

    @Test
    fun aSendBroadcastsOnce() {
        val d = MultisigSendDraft()
        d.resetSignatures(vaultA)
        d.markSigned("73c5da0a")
        d.markSigned("2f1a9b3c")
        assertTrue(d.broadcastMock())
        val txid = d.broadcastTxid
        assertFalse(d.broadcastMock(), "back navigation to M4 must not broadcast again")
        assertEquals(txid, d.broadcastTxid)
    }

    private fun vault(id: String, m: Int, keys: List<String>) = CreatedVault(
        id = id,
        name = id,
        m = m,
        n = keys.size,
        policy = CustodyPolicy.PersonalCustody,
        address = "tb1q$id",
        bsms = "",
        keyFingerprints = keys,
    )
}
