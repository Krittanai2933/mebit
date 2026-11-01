package com.mebit.app.data

import com.mebit.ffi.signer.HotSendResult
import com.mebit.ffi.signer.HotTx
import com.mebit.ffi.signer.HotWalletSnapshot
import com.mebit.ffi.signer.KeyNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** A session holding [words] and [passphrase]. Each platform makes it its own way, because the host JVM test has no native library. */
internal expect fun sessionWithWords(words: List<String>, passphrase: String): OnboardingSession

private val WORDS = List(11) { "abandon" } + "about"
private const val PASSPHRASE = "mebit-test"
private const val DESTINATION = "tb1qdestinationfortheseteststhatneverleaves"

// 23 ต.ค. 2569 · 14:32 น. in Bangkok, the time on board 4.6.
private const val SENT_AT = 1_792_740_720L

private data class SendCall(val words: List<String>, val passphrase: String, val network: KeyNetwork, val destination: String, val amountSats: ULong, val feeSats: ULong)

/** What the send screens read after a broadcast. */
private data class Seen(val returned: Boolean, val txid: String, val error: String?, val balanceSats: Long, val receiveAddress: String)

private fun SingleSigWallet.seen(returned: Boolean) = Seen(returned, lastTxid, lastError, balanceSats, receiveAddress)

/**
 * Fakes for the three hot_* calls. A test swaps [sendResult] or [snapshotResult] to change what the next call does.
 * The clock is [clock], and every read moves it on a second, so the wallet's send time shows which read it kept.
 */
private class FakeHot {
    var sendResult: () -> HotSendResult = { HotSendResult("txid-1") }
    var snapshotResult: () -> HotWalletSnapshot = { HotWalletSnapshot("tb1qbefore", 1_000_000uL, KeyNetwork.TEST) }
    var clock = SENT_AT
    val sends = mutableListOf<SendCall>()
    var snapshots = 0
    val history = listOf(HotTx("txid-0", 1_000_000L, 3u, 1_760_000_000uL))
    val wallet = SingleSigWallet(
        send = { words, passphrase, network, destination, amountSats, feeSats ->
            sends += SendCall(words, passphrase, network, destination, amountSats, feeSats)
            sendResult()
        },
        snapshot = { _, _, _ ->
            snapshots++
            snapshotResult()
        },
        fetchHistory = { _, _, _ -> history },
        nowSeconds = { clock++ },
    )

    /** A 12-word session bound through a working snapshot: the wallet is live with 1,000,000 sats. */
    fun goLive(): OnboardingSession {
        val session = sessionWithWords(WORDS, PASSPHRASE)
        assertEquals(WORDS, session.words)
        wallet.bindSession(session)
        assertTrue(wallet.live, "bindSession went live")
        assertEquals(history, wallet.history)
        return session
    }
}

private fun walletThatMustNotCallHot(nowSeconds: () -> Long = { 0L }) = SingleSigWallet(
    send = { _, _, _, _, _, _ -> fail("send ran") },
    snapshot = { _, _, _ -> fail("snapshot ran") },
    fetchHistory = { _, _, _ -> fail("history ran") },
    nowSeconds = nowSeconds,
)

/** The demo fixture holds 1,250,000 sats: a payment above that is refused, one within it is deducted with its fee under a mock txid. */
private fun assertMockPayments(wallet: SingleSigWallet, session: OnboardingSession) {
    assertFalse(wallet.broadcast(session, 1_250_000, 1_128, DESTINATION))
    assertEquals("ยอดไม่พอ", wallet.lastError)
    assertEquals(1_250_000L, wallet.balanceSats)
    assertTrue(wallet.broadcast(session, 100_000, 1_128, DESTINATION))
    assertNull(wallet.lastError)
    assertEquals(1_148_872L, wallet.balanceSats)
    assertTrue(wallet.lastTxid.startsWith("mocktxid_"), wallet.lastTxid)
}

class SingleSigWalletBroadcastTest {
    @Test
    fun aSentPaymentStaysSentWhenTheBalanceRefreshFails() {
        val hot = FakeHot()
        val session = hot.goLive()
        hot.snapshotResult = { throw IllegalStateException("esplora answer unreadable") }
        val returned = hot.wallet.broadcast(session, 100_000, 1_128, DESTINATION)
        assertEquals(Seen(true, "txid-1", null, 1_000_000L, "tb1qbefore"), hot.wallet.seen(returned), "a payment that went out reads as sent")
    }

    @Test
    fun aFailedSendFailsAndNeverRefreshes() {
        val hot = FakeHot()
        val session = hot.goLive()
        val snapshots = hot.snapshots
        hot.sendResult = { throw IllegalStateException("bad-txns-inputs-missingorspent") }
        val returned = hot.wallet.broadcast(session, 100_000, 1_128, DESTINATION)
        assertEquals(Seen(false, "", "bad-txns-inputs-missingorspent", 1_000_000L, "tb1qbefore"), hot.wallet.seen(returned))
        assertEquals(snapshots, hot.snapshots, "no refresh after a failed send")
    }

    @Test
    fun aSentPaymentTakesTheBalanceAndAddressFromTheRefresh() {
        val hot = FakeHot()
        val session = hot.goLive()
        val snapshots = hot.snapshots
        hot.snapshotResult = { HotWalletSnapshot("tb1qafter", 898_872uL, KeyNetwork.TEST) }
        val returned = hot.wallet.broadcast(session, 100_000, 1_128, DESTINATION)
        assertEquals(Seen(true, "txid-1", null, 898_872L, "tb1qafter"), hot.wallet.seen(returned))
        assertEquals(snapshots + 1, hot.snapshots, "one refresh after the send")
    }

    @Test
    fun theSendGetsTheSessionKeyAndThePaymentAsGiven() {
        val hot = FakeHot()
        val session = hot.goLive()
        hot.wallet.broadcast(session, 100_000, 1_128, DESTINATION, KeyNetwork.MAIN)
        assertEquals(listOf(SendCall(WORDS, PASSPHRASE, KeyNetwork.MAIN, DESTINATION, 100_000uL, 1_128uL)), hot.sends)
    }

    @Test
    fun withoutASeedThePaymentIsMockedAndNoHotCallRuns() {
        val wallet = walletThatMustNotCallHot()
        val session = OnboardingSession()
        wallet.bindSession(session)
        assertFalse(wallet.live)
        assertMockPayments(wallet, session)
    }

    @Test
    fun aSeedWhoseWalletNeverWentLiveIsMockedAndNeverSends() {
        val hot = FakeHot()
        hot.snapshotResult = { throw IllegalStateException("esplora unreachable") }
        hot.sendResult = { fail("send ran") }
        val session = sessionWithWords(WORDS, PASSPHRASE)
        hot.wallet.bindSession(session)
        assertFalse(hot.wallet.live)
        assertMockPayments(hot.wallet, session)
        assertEquals(1, hot.snapshots, "only bindSession asked for a snapshot")
    }

    @Test
    fun aLiveSendRecordsWhenTheSendReturnedNotWhenASlowRefreshEnded() {
        val hot = FakeHot()
        val session = hot.goLive()
        hot.snapshotResult = {
            hot.clock += 5
            HotWalletSnapshot("tb1qafter", 898_872uL, KeyNetwork.TEST)
        }
        assertEquals(0L, hot.wallet.lastSentAtSeconds, "nothing sent yet")
        assertTrue(hot.wallet.broadcast(session, 100_000, 1_128, DESTINATION))
        assertEquals(SENT_AT, hot.wallet.lastSentAtSeconds)
    }

    @Test
    fun aSentPaymentWhoseRefreshFailsStillHasItsSendTime() {
        val hot = FakeHot()
        val session = hot.goLive()
        hot.snapshotResult = { throw IllegalStateException("esplora answer unreadable") }
        assertTrue(hot.wallet.broadcast(session, 100_000, 1_128, DESTINATION))
        assertEquals(SENT_AT, hot.wallet.lastSentAtSeconds)
    }

    @Test
    fun aFailedSendLeavesNoSendTime() {
        val hot = FakeHot()
        val session = hot.goLive()
        hot.sendResult = { throw IllegalStateException("bad-txns-inputs-missingorspent") }
        assertFalse(hot.wallet.broadcast(session, 100_000, 1_128, DESTINATION))
        assertEquals(0L, hot.wallet.lastSentAtSeconds)
    }

    @Test
    fun aMockPaymentRecordsTheClockAndARefusedOneDoesNot() {
        val wallet = walletThatMustNotCallHot { SENT_AT }
        val session = OnboardingSession()
        wallet.bindSession(session)
        assertFalse(wallet.broadcast(session, 1_250_000, 1_128, DESTINATION))
        assertEquals(0L, wallet.lastSentAtSeconds)
        assertTrue(wallet.broadcast(session, 100_000, 1_128, DESTINATION))
        assertEquals(SENT_AT, wallet.lastSentAtSeconds)
    }

    @Test
    fun theDemoFixtureForgetsTheSendTime() {
        val hot = FakeHot()
        val session = hot.goLive()
        assertTrue(hot.wallet.broadcast(session, 100_000, 1_128, DESTINATION))
        assertEquals(SENT_AT, hot.wallet.lastSentAtSeconds)
        hot.wallet.useDemoFixture()
        assertEquals(0L, hot.wallet.lastSentAtSeconds)
    }
}
