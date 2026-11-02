package com.mebit.app.data

import com.mebit.app.Why
import com.mebit.app.WriteMode
import com.mebit.app.WriteResult
import com.mebit.app.data.SingleSigWallet.UnconfirmedSend
import com.mebit.ffi.signer.HotSendResult
import com.mebit.ffi.signer.HotTx
import com.mebit.ffi.signer.HotWalletSnapshot
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.MobileSignerException
import com.mebit.ffi.signer.SoftwareAccountKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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

private const val UNKNOWN_TXID = TEST_TXID

// The Rust MobileSignerError::BroadcastUnknown Display, which is the Kotlin message, so the txid is read from the real wording.
private const val UNKNOWN_MESSAGE = "it is not known whether transaction $UNKNOWN_TXID was sent, so do not repeat the payment until the wallet and this transaction id have been checked"

// What Home would print raw, so it must not be the English Rust text above.
private const val UNKNOWN_SHOWN = "ยังไม่ทราบว่าการส่งออกไปแล้วหรือยัง อย่าส่งซ้ำ"
private const val NOT_LIVE = "ยังดึงยอดคงเหลือไม่ได้ จึงยังส่งไม่ได้"
private const val EARLIER_UNSETTLED = "การส่งครั้งก่อนยังยืนยันไม่ได้ จึงยังส่งใหม่ไม่ได้"

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
    var history = listOf(HotTx("txid-0", 1_000_000L, 3u, 1_760_000_000uL))
    var historyFails = false
    val wallet = SingleSigWallet(
        send = { words, passphrase, network, destination, amountSats, feeSats ->
            sends += SendCall(words, passphrase, network, destination, amountSats, feeSats)
            sendResult()
        },
        snapshot = { _, _, _ ->
            snapshots++
            snapshotResult()
        },
        fetchHistory = { _, _, _ -> if (historyFails) throw IllegalStateException("history unreadable") else history },
        nowSeconds = { clock++ },
        receiveAddressOf = { _, _, _, _ -> "tb1qoffline" },
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

/** A live wallet whose broadcast at [SENT_AT] ended in BroadcastUnknown for [UNKNOWN_TXID]. Send works again afterwards, so a payment that wrongly reaches it goes out. */
private fun FakeHot.unknownSend(): OnboardingSession {
    val session = goLive()
    sendResult = { throw MobileSignerException.BroadcastUnknown(UNKNOWN_MESSAGE) }
    assertFalse(wallet.broadcast(session, 100_000, 1_128, DESTINATION))
    assertEquals(UnconfirmedSend(UNKNOWN_TXID, SENT_AT), wallet.unconfirmedSend)
    sendResult = { HotSendResult("txid-2") }
    return session
}

/** A phone that saved the public test wallet with [pending]. */
private fun phoneHolding(pending: UnconfirmedSend?) =
    FakeSecureStore().also { assertEquals(SaveResult.Written, SavedWallet(it).save(testRecord(unconfirmedSend = pending), WriteMode.Create)) }

/** What the gate does at a cold start: opens the copy [store] holds and installs it into a new session that saves through [store]. The key is made without the native library. */
private fun restart(store: FakeSecureStore): OnboardingSession {
    val record = assertIs<UnlockResult.Opened>(SavedWallet(store).openNow(), "the saved copy").record
    val session = OnboardingSession(SavedWallet(store), restoreKey = { w, _, _, _ -> SoftwareAccountKey(w, record.fingerprint, "tpub-test", "m/48'/1'/0'/2'") })
    assertTrue(session.install(record), "install")
    return session
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
    fun aSeedWhoseWalletNeverWentLiveNeverSendsAndFakesNoPayment() {
        val hot = FakeHot()
        hot.snapshotResult = { throw IllegalStateException("esplora unreachable") }
        hot.sendResult = { fail("send ran") }
        val session = sessionWithWords(WORDS, PASSPHRASE)
        hot.wallet.bindSession(session)
        assertFalse(hot.wallet.live)
        assertFalse(hot.wallet.broadcast(session, 100_000, 1_128, DESTINATION), "no payment from a balance nobody knows")
        assertEquals("", hot.wallet.lastTxid, "no mock txid")
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

    @Test
    fun aRealKeyOnAnUnavailableWalletIsRefusedAndNoPaymentIsFaked() {
        val hot = FakeHot()
        hot.snapshotResult = { throw IllegalStateException("esplora unreachable") }
        val session = sessionWithWords(WORDS, PASSPHRASE)
        hot.wallet.bindSession(session)
        val returned = hot.wallet.broadcast(session, 100_000, 1_128, DESTINATION)
        assertEquals(Seen(false, "", NOT_LIVE, 0L, "tb1qoffline"), hot.wallet.seen(returned))
        assertEquals(0L, hot.wallet.lastSentAtSeconds)
        assertEquals(emptyList<SendCall>(), hot.sends)
    }

    @Test
    fun aRealKeyWhoseFirstBindHasNotEndedIsRefusedEvenWhileTheDemoBalanceShows() {
        val hot = FakeHot()
        val session = sessionWithWords(WORDS, PASSPHRASE)
        assertFalse(hot.wallet.broadcast(session, 100_000, 1_128, DESTINATION), "a wallet never bound")
        assertEquals(NOT_LIVE, hot.wallet.lastError)
        hot.wallet.bindSession(OnboardingSession())
        val returned = hot.wallet.broadcast(session, 100_000, 1_128, DESTINATION)
        assertEquals(Seen(false, "", NOT_LIVE, 1_250_000L, "tb1qmebitdemosinglesig0000000000000000recv"), hot.wallet.seen(returned), "the demo's balance pays for nothing")
        assertEquals(0L, hot.wallet.lastSentAtSeconds)
        assertEquals(emptyList<SendCall>(), hot.sends)
    }

    @Test
    fun anUnknownBroadcastIsNeitherASentPaymentNorAFailureToRetry() {
        val hot = FakeHot()
        val session = hot.goLive()
        hot.sendResult = { throw MobileSignerException.BroadcastUnknown(UNKNOWN_MESSAGE) }
        val returned = hot.wallet.broadcast(session, 100_000, 1_128, DESTINATION)
        assertEquals(Seen(false, "", UNKNOWN_SHOWN, 1_000_000L, "tb1qbefore"), hot.wallet.seen(returned), "no txid of a sent payment")
        assertEquals(0L, hot.wallet.lastSentAtSeconds, "no send time of a sent payment")
        assertEquals(UnconfirmedSend(UNKNOWN_TXID, SENT_AT), hot.wallet.unconfirmedSend, "the txid from the message and the clock when it failed")
    }

    @Test
    fun noPaymentReachesSendWhileAnUnknownOneIsUnsettled() {
        val hot = FakeHot()
        val session = hot.unknownSend()
        val returned = hot.wallet.broadcast(session, 50_000, 1_128, DESTINATION)
        assertEquals(Seen(false, "", EARLIER_UNSETTLED, 1_000_000L, "tb1qbefore"), hot.wallet.seen(returned))
        assertEquals(1, hot.sends.size, "only the unknown payment reached send")
    }

    @Test
    fun anUnknownBroadcastWhoseMessageNamesNoTxidStillHoldsTheWallet() {
        val hot = FakeHot()
        val session = hot.goLive()
        hot.sendResult = { throw MobileSignerException.BroadcastUnknown("the explorer's answer was lost") }
        assertFalse(hot.wallet.broadcast(session, 100_000, 1_128, DESTINATION))
        assertEquals(UnconfirmedSend("", SENT_AT), hot.wallet.unconfirmedSend)
        hot.sendResult = { HotSendResult("txid-2") }
        assertFalse(hot.wallet.broadcast(session, 100_000, 1_128, DESTINATION))
        assertEquals(1, hot.sends.size)
    }

    @Test
    fun aLoadedBindWhoseHistoryHoldsTheTxidSettlesItAtOnce() {
        val hot = FakeHot()
        val session = hot.unknownSend()
        hot.history = listOf(HotTx(UNKNOWN_TXID, -101_128L, 0u, null)) + hot.history
        hot.wallet.bindSession(session)
        assertNull(hot.wallet.unconfirmedSend, "the payment is out and Home lists it")
        assertTrue(hot.wallet.broadcast(session, 50_000, 1_128, DESTINATION), "a new payment can start")
    }

    @Test
    fun aLoadedBindWithoutTheTxidKeepsItUnderTwoMinutes() {
        val hot = FakeHot()
        val session = hot.unknownSend()
        hot.clock = SENT_AT + 119
        hot.wallet.bindSession(session)
        assertEquals(UnconfirmedSend(UNKNOWN_TXID, SENT_AT), hot.wallet.unconfirmedSend)
        assertFalse(hot.wallet.broadcast(session, 50_000, 1_128, DESTINATION))
        assertEquals(1, hot.sends.size)
    }

    @Test
    fun aLoadedBindWithoutTheTxidSettlesItAtTwoMinutes() {
        val hot = FakeHot()
        val session = hot.unknownSend()
        hot.clock = SENT_AT + 120
        hot.wallet.bindSession(session)
        assertNull(hot.wallet.unconfirmedSend, "two minutes of a readable explorer without it count as not sent")
        assertTrue(hot.wallet.broadcast(session, 50_000, 1_128, DESTINATION))
    }

    @Test
    fun aBindWhoseHistoryCouldNotBeLoadedNeverSettlesItByTime() {
        val hot = FakeHot()
        val session = hot.unknownSend()
        hot.clock = SENT_AT + 600
        hot.historyFails = true
        hot.wallet.bindSession(session)
        assertTrue(hot.wallet.live, "the snapshot loaded")
        assertEquals(emptyList<HotTx>(), hot.wallet.history)
        assertEquals(UnconfirmedSend(UNKNOWN_TXID, SENT_AT), hot.wallet.unconfirmedSend, "an empty list from a failed call says nothing about the payment")
        assertFalse(hot.wallet.broadcast(session, 50_000, 1_128, DESTINATION))
        assertEquals(1, hot.sends.size)
        hot.historyFails = false
        hot.wallet.bindSession(session)
        assertNull(hot.wallet.unconfirmedSend, "a list that loads settles it, here by time")
    }

    @Test
    fun aFailedBindNeverSettlesItHoweverLongAgo() {
        val hot = FakeHot()
        val session = hot.unknownSend()
        hot.clock = SENT_AT + 600
        hot.snapshotResult = { throw IllegalStateException("esplora unreachable") }
        hot.wallet.bindSession(session)
        assertEquals(UnconfirmedSend(UNKNOWN_TXID, SENT_AT), hot.wallet.unconfirmedSend)
        assertFalse(hot.wallet.broadcast(session, 50_000, 1_128, DESTINATION))
        assertEquals(EARLIER_UNSETTLED, hot.wallet.lastError, "the earlier payment is named before the unknown balance")
    }

    @Test
    fun theDemoFixtureForgetsAnUnknownBroadcast() {
        val hot = FakeHot()
        hot.unknownSend()
        hot.wallet.useDemoFixture()
        assertNull(hot.wallet.unconfirmedSend)
    }

    @Test
    fun aPaymentOfUnknownOutcomeStillHoldsANewWalletAfterARestart() {
        val store = phoneHolding(null)
        val session = restart(store)
        val before = FakeHot()
        before.wallet.bindSession(session)
        before.sendResult = { throw MobileSignerException.BroadcastUnknown(UNKNOWN_MESSAGE) }
        assertFalse(before.wallet.broadcast(session, 100_000, 1_128, DESTINATION))
        val after = FakeHot()
        val restarted = restart(store)
        after.wallet.bindSession(restarted)
        assertEquals(UnconfirmedSend(UNKNOWN_TXID, SENT_AT), after.wallet.unconfirmedSend, "the new wallet after the restart")
        assertFalse(after.wallet.broadcast(restarted, 100_000, 1_128, DESTINATION), "a payment after the restart")
        assertEquals(EARLIER_UNSETTLED, after.wallet.lastError)
        assertEquals(emptyList<SendCall>(), after.sends, "a send ran after the restart")
    }

    @Test
    fun aRestoredPaymentThatTheHistoryListsSettlesAndLeavesTheSavedWallet() {
        val store = phoneHolding(UnconfirmedSend(UNKNOWN_TXID, SENT_AT))
        val session = restart(store)
        val hot = FakeHot()
        hot.wallet.bindSession(session)
        assertEquals(UnconfirmedSend(UNKNOWN_TXID, SENT_AT), hot.wallet.unconfirmedSend, "before the history lists it")
        hot.history = listOf(HotTx(UNKNOWN_TXID, -101_128L, 0u, null)) + hot.history
        hot.wallet.bindSession(session)
        assertNull(hot.wallet.unconfirmedSend, "the payment is out and Home lists it")
        assertNull(restart(store).unconfirmedSend, "what the next cold start restores")
        assertTrue(hot.wallet.broadcast(session, 50_000, 1_128, DESTINATION), "a new payment can start")
    }

    @Test
    fun aRestoredPaymentSettlesTwoMinutesAfterItsBroadcastEnded() {
        val store = phoneHolding(UnconfirmedSend(UNKNOWN_TXID, SENT_AT))
        val session = restart(store)
        val hot = FakeHot()
        hot.clock = SENT_AT + 119
        hot.wallet.bindSession(session)
        assertEquals(UnconfirmedSend(UNKNOWN_TXID, SENT_AT), hot.wallet.unconfirmedSend, "under two minutes")
        hot.clock = SENT_AT + 120
        hot.wallet.bindSession(session)
        assertNull(hot.wallet.unconfirmedSend, "at two minutes")
        assertNull(restart(store).unconfirmedSend, "what the next cold start restores")
    }

    @Test
    fun aPaymentOfUnknownOutcomeThatCannotBeSavedStillHoldsTheWallet() {
        val store = phoneHolding(null)
        val session = restart(store)
        val hot = FakeHot()
        hot.wallet.bindSession(session)
        store.write = WriteResult.Unavailable(Why.TryLater)
        hot.sendResult = { throw MobileSignerException.BroadcastUnknown(UNKNOWN_MESSAGE) }
        assertFalse(hot.wallet.broadcast(session, 100_000, 1_128, DESTINATION))
        assertEquals(UnconfirmedSend(UNKNOWN_TXID, SENT_AT), hot.wallet.unconfirmedSend, "the wallet's guard after a failed save")
        assertEquals(UnconfirmedSend(UNKNOWN_TXID, SENT_AT), session.unconfirmedSend, "the session's after a failed save")
        hot.sendResult = { HotSendResult("txid-2") }
        assertFalse(hot.wallet.broadcast(session, 50_000, 1_128, DESTINATION), "a payment after a failed save")
        assertEquals(1, hot.sends.size, "only the unknown payment reached send")
        store.write = null
        session.flushPendingSave()
        assertEquals(UnconfirmedSend(UNKNOWN_TXID, SENT_AT), restart(store).unconfirmedSend, "what the next cold start restores after the flush")
    }

    @Test
    fun theDemoFixtureNeverTouchesASavedPaymentOfUnknownOutcome() {
        val store = phoneHolding(UnconfirmedSend(UNKNOWN_TXID, SENT_AT))
        val session = restart(store)
        val hot = FakeHot()
        hot.wallet.bindSession(session)
        hot.wallet.useDemoFixture()
        assertNull(hot.wallet.unconfirmedSend, "the fixture's own clear")
        assertEquals(1, store.writes.size, "the fixture wrote")
        assertEquals(UnconfirmedSend(UNKNOWN_TXID, SENT_AT), restart(store).unconfirmedSend, "what the next cold start restores")
        hot.wallet.bindSession(session)
        assertEquals(UnconfirmedSend(UNKNOWN_TXID, SENT_AT), hot.wallet.unconfirmedSend, "the next bind of the real key")
    }

    @Test
    fun aBindOfASessionWithoutAPaymentOfUnknownOutcomeKeepsTheWalletsOwn() {
        val hot = FakeHot()
        hot.unknownSend()
        hot.wallet.bindSession(sessionWithWords(WORDS, PASSPHRASE))
        assertEquals(UnconfirmedSend(UNKNOWN_TXID, SENT_AT), hot.wallet.unconfirmedSend)
    }

    @Test
    fun anyOtherSendFailureIsAPlainFailureThatCanBeTriedAgain() {
        val hot = FakeHot()
        val session = hot.goLive()
        hot.sendResult = { throw MobileSignerException.InvalidPsbtJson("esplora post: status code 400") }
        assertFalse(hot.wallet.broadcast(session, 100_000, 1_128, DESTINATION))
        assertNull(hot.wallet.unconfirmedSend)
        hot.sendResult = { HotSendResult("txid-2") }
        assertTrue(hot.wallet.broadcast(session, 100_000, 1_128, DESTINATION), "the retry reaches send")
        assertEquals(2, hot.sends.size)
    }
}
