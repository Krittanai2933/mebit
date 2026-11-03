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
import com.mebit.ffi.signer.PreparedSend
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

/** The txid hot_prepare_send computes for the test payment. The codec keeps only a 64-digit txid, so a made-up one would never reach the phone. */
private const val TXID = TEST_TXID

// The Rust MobileSignerError::BroadcastUnknown Display, which is the Kotlin message.
private const val UNKNOWN_MESSAGE = "it is not known whether transaction $TXID was sent, so do not repeat the payment until the wallet and this transaction id have been checked"

// What Home would print raw, so none of it may be the English Rust text.
private const val UNKNOWN_SHOWN = "ยังไม่ทราบว่าการส่งออกไปแล้วหรือยัง อย่าส่งซ้ำ"
private const val NOT_LIVE = "ยังดึงยอดคงเหลือไม่ได้ จึงยังส่งไม่ได้"
private const val EARLIER_UNSETTLED = "การส่งครั้งก่อนยังยืนยันไม่ได้ จึงยังส่งใหม่ไม่ได้"
private const val GUARD_NOT_SAVED = "บันทึกสถานะการส่งไม่ได้ จึงยังไม่ส่ง ลองใหม่ภายหลัง"
private const val PLAIN_FAILURE = "ส่งไม่สำเร็จ ลองใหม่ภายหลัง"

private data class PrepareCall(val words: List<String>, val passphrase: String, val network: KeyNetwork, val destination: String, val amountSats: ULong, val feeSats: ULong)

private data class PostCall(val network: KeyNetwork, val hex: String, val txid: String)

/** What the send screens read after a broadcast. */
private data class Seen(val returned: Boolean, val txid: String, val error: String?, val balanceSats: Long, val receiveAddress: String)

private fun SingleSigWallet.seen(returned: Boolean) = Seen(returned, lastTxid, lastError, balanceSats, receiveAddress)

/**
 * Fakes for the hot_* calls. A test swaps [prepareResult], [postResult] or [snapshotResult] to change what the next call does.
 * The clock is [clock] and stands still unless a test moves it. [store] is the phone the live session is saved on.
 */
private class FakeHot {
    var prepareResult: (feeSats: ULong) -> PreparedSend = { fee -> PreparedSend(TXID, "hex-1", fee) }
    var postResult: () -> HotSendResult = { HotSendResult(TXID) }
    var snapshotResult: () -> HotWalletSnapshot = { HotWalletSnapshot("tb1qbefore", 1_000_000uL, KeyNetwork.TEST) }
    var clock = SENT_AT
    val prepares = mutableListOf<PrepareCall>()
    val posts = mutableListOf<PostCall>()
    var snapshots = 0
    var history = listOf(HotTx("txid-0", 1_000_000L, 3u, 1_760_000_000uL))
    var historyFails = false
    val store = FakeSecureStore()
    val wallet = SingleSigWallet(
        prepare = { words, passphrase, network, destination, amountSats, feeSats ->
            prepares += PrepareCall(words, passphrase, network, destination, amountSats, feeSats)
            prepareResult(feeSats)
        },
        post = { network, hex, txid ->
            posts += PostCall(network, hex, txid)
            postResult()
        },
        snapshot = { _, _, _ ->
            snapshots++
            snapshotResult()
        },
        fetchHistory = { _, _, _ -> if (historyFails) throw IllegalStateException("history unreadable") else history },
        nowSeconds = { clock },
        receiveAddressOf = { _, _, _, _ -> "tb1qoffline" },
    )

    /** A 12-word session saved on [store] and bound through a working snapshot: the wallet is live with 1,000,000 sats. */
    fun goLive(): OnboardingSession {
        val session = savedSession(store)
        assertEquals(WORDS, session.words)
        wallet.bindSession(session)
        assertTrue(wallet.live, "bindSession went live")
        assertEquals(history, wallet.history)
        return session
    }
}

/** A session of [WORDS] and [PASSPHRASE] saved on [store], made without the native library. */
private fun savedSession(store: FakeSecureStore): OnboardingSession =
    OnboardingSession(SavedWallet(store), restoreKey = { w, _, _, _ -> SoftwareAccountKey(w, TEST_CHECK, "tpub-test", "m/48'/1'/0'/2'") }).apply {
        restoreFromWords(WORDS, PASSPHRASE) // staged, because of the passphrase
        acceptStagedKey()
        assertEquals(SaveState.Saved, saved, "the test session is saved on its phone")
    }

/** A live wallet whose broadcast at [SENT_AT] ended in BroadcastUnknown for [TXID]. The post works again afterwards, so a payment that wrongly reaches it goes out. */
private fun FakeHot.unknownSend(): OnboardingSession {
    val session = goLive()
    postResult = { throw MobileSignerException.BroadcastUnknown(UNKNOWN_MESSAGE) }
    assertFalse(wallet.broadcast(session, 100_000, 1_128, DESTINATION))
    assertEquals(UnconfirmedSend(TXID, SENT_AT), wallet.unconfirmedSend)
    postResult = { HotSendResult(TXID) }
    return session
}

/** A phone that saved the public test wallet with [pending]. */
private fun phoneHolding(pending: UnconfirmedSend?) =
    FakeSecureStore().also { assertIs<SaveResult.Written>(SavedWallet(it).save(testRecord(unconfirmedSend = pending), WriteMode.Create)) }

/** What the gate does at a cold start: opens the copy [store] holds and installs it into a new session that saves through [store]. The key is made without the native library. */
private fun restart(store: FakeSecureStore): OnboardingSession {
    val opened = assertIs<UnlockResult.Opened>(SavedWallet(store).openNow(), "the saved copy")
    val session = OnboardingSession(SavedWallet(store), restoreKey = { w, _, _, _ -> SoftwareAccountKey(w, opened.record.fingerprint, "tpub-test", "m/48'/1'/0'/2'") })
    assertTrue(session.install(opened.record, opened.version), "install")
    return session
}

private fun walletThatMustNotCallHot(nowSeconds: () -> Long = { 0L }) = SingleSigWallet(
    prepare = { _, _, _, _, _, _ -> fail("prepare ran") },
    post = { _, _, _ -> fail("post ran") },
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
        assertEquals(Seen(true, TXID, null, 1_000_000L, "tb1qbefore"), hot.wallet.seen(returned), "a payment that went out reads as sent")
    }

    @Test
    fun aFailedSendFailsAndNeverRefreshes() {
        val hot = FakeHot()
        val session = hot.goLive()
        val snapshots = hot.snapshots
        hot.postResult = { throw MobileSignerException.InvalidPsbtJson("esplora post: status code 400: bad-txns-inputs-missingorspent") }
        val returned = hot.wallet.broadcast(session, 100_000, 1_128, DESTINATION)
        assertEquals(Seen(false, "", "ส่งไม่สำเร็จ", 1_000_000L, "tb1qbefore"), hot.wallet.seen(returned))
        assertEquals(snapshots, hot.snapshots, "no refresh after a failed send")
    }

    @Test
    fun aSentPaymentTakesTheBalanceAndAddressFromTheRefresh() {
        val hot = FakeHot()
        val session = hot.goLive()
        val snapshots = hot.snapshots
        hot.snapshotResult = { HotWalletSnapshot("tb1qafter", 898_872uL, KeyNetwork.TEST) }
        val returned = hot.wallet.broadcast(session, 100_000, 1_128, DESTINATION)
        assertEquals(Seen(true, TXID, null, 898_872L, "tb1qafter"), hot.wallet.seen(returned))
        assertEquals(snapshots + 1, hot.snapshots, "one refresh after the send")
    }

    @Test
    fun thePreparationGetsTheSessionKeyAndThePaymentAsGivenAndThePostGetsWhatItPrepared() {
        val hot = FakeHot()
        val session = hot.goLive()
        hot.wallet.broadcast(session, 100_000, 1_128, DESTINATION, KeyNetwork.MAIN)
        assertEquals(listOf(PrepareCall(WORDS, PASSPHRASE, KeyNetwork.MAIN, DESTINATION, 100_000uL, 1_128uL)), hot.prepares)
        assertEquals(listOf(PostCall(KeyNetwork.MAIN, "hex-1", TXID)), hot.posts)
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
        hot.prepareResult = { fail("prepare ran") }
        val session = sessionWithWords(WORDS, PASSPHRASE)
        hot.wallet.bindSession(session)
        assertFalse(hot.wallet.live)
        assertFalse(hot.wallet.broadcast(session, 100_000, 1_128, DESTINATION), "no payment from a balance nobody knows")
        assertEquals("", hot.wallet.lastTxid, "no mock txid")
        assertEquals(1, hot.snapshots, "only bindSession asked for a snapshot")
    }

    @Test
    fun aLiveSendRecordsWhenThePostReturnedNotWhenItBeganOrWhenASlowRefreshEnded() {
        val hot = FakeHot()
        val session = hot.goLive()
        hot.postResult = {
            hot.clock += 7
            HotSendResult(TXID)
        }
        hot.snapshotResult = {
            hot.clock += 5
            HotWalletSnapshot("tb1qafter", 898_872uL, KeyNetwork.TEST)
        }
        assertEquals(0L, hot.wallet.lastSentAtSeconds, "nothing sent yet")
        assertTrue(hot.wallet.broadcast(session, 100_000, 1_128, DESTINATION))
        assertEquals(SENT_AT + 7, hot.wallet.lastSentAtSeconds)
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
        hot.postResult = { throw MobileSignerException.InvalidPsbtJson("esplora post: status code 400: bad-txns-inputs-missingorspent") }
        assertFalse(hot.wallet.broadcast(session, 100_000, 1_128, DESTINATION))
        assertEquals(0L, hot.wallet.lastSentAtSeconds)
    }

    @Test
    fun anUnexpectedFailureInThePostKeepsTheGuardBecauseThePaymentMayBeOut() {
        val hot = FakeHot()
        val session = hot.goLive()
        hot.postResult = { throw IllegalStateException("the bridge failed after the post") }
        assertFalse(hot.wallet.broadcast(session, 100_000, 1_128, DESTINATION))
        assertEquals(UnconfirmedSend(TXID, SENT_AT), hot.wallet.unconfirmedSend, "the wallet after an unexpected failure")
        assertEquals(UnconfirmedSend(TXID, SENT_AT), restart(hot.store).unconfirmedSend, "the phone after an unexpected failure")
        assertFalse(hot.wallet.broadcast(session, 100_000, 1_128, DESTINATION), "the retry reached the post")
        assertEquals(1, hot.posts.size)
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
        assertEquals(emptyList<PrepareCall>(), hot.prepares)
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
        assertEquals(emptyList<PrepareCall>(), hot.prepares)
    }

    @Test
    fun anUnknownBroadcastIsNeitherASentPaymentNorAFailureToRetry() {
        val hot = FakeHot()
        val session = hot.goLive()
        hot.postResult = { throw MobileSignerException.BroadcastUnknown(UNKNOWN_MESSAGE) }
        val returned = hot.wallet.broadcast(session, 100_000, 1_128, DESTINATION)
        assertEquals(Seen(false, "", UNKNOWN_SHOWN, 1_000_000L, "tb1qbefore"), hot.wallet.seen(returned), "no txid of a sent payment")
        assertEquals(0L, hot.wallet.lastSentAtSeconds, "no send time of a sent payment")
        assertEquals(UnconfirmedSend(TXID, SENT_AT), hot.wallet.unconfirmedSend, "the prepared txid and the clock when the post began")
    }

    @Test
    fun noPaymentIsPreparedWhileAnUnknownOneIsUnsettled() {
        val hot = FakeHot()
        val session = hot.unknownSend()
        val returned = hot.wallet.broadcast(session, 50_000, 1_128, DESTINATION)
        assertEquals(Seen(false, "", EARLIER_UNSETTLED, 1_000_000L, "tb1qbefore"), hot.wallet.seen(returned))
        assertEquals(1, hot.prepares.size, "only the unknown payment was prepared")
        assertEquals(1, hot.posts.size, "only the unknown payment was posted")
    }

    @Test
    fun theGuardHoldsThePreparedTxidWhateverTheErrorSays() {
        val hot = FakeHot()
        val session = hot.goLive()
        hot.postResult = { throw MobileSignerException.BroadcastUnknown("the explorer's answer was lost") }
        assertFalse(hot.wallet.broadcast(session, 100_000, 1_128, DESTINATION))
        assertEquals(UnconfirmedSend(TXID, SENT_AT), hot.wallet.unconfirmedSend)
        hot.postResult = { HotSendResult(TXID) }
        assertFalse(hot.wallet.broadcast(session, 100_000, 1_128, DESTINATION))
        assertEquals(1, hot.posts.size)
    }

    @Test
    fun theGuardIsOnThePhoneBeforeThePostStartsAndGoneOnceThePaymentIsOut() {
        val hot = FakeHot()
        val session = hot.goLive()
        var onThePhone: UnconfirmedSend? = null
        var inTheSession: UnconfirmedSend? = null
        hot.postResult = {
            onThePhone = restart(hot.store).unconfirmedSend
            inTheSession = session.unconfirmedSend
            HotSendResult(TXID)
        }
        assertTrue(hot.wallet.broadcast(session, 100_000, 1_128, DESTINATION))
        assertEquals(UnconfirmedSend(TXID, SENT_AT), onThePhone, "what a cold start during the post would restore")
        assertEquals(UnconfirmedSend(TXID, SENT_AT), inTheSession, "the session during the post")
        assertNull(hot.wallet.unconfirmedSend, "the wallet's guard once the payment is out")
        assertNull(session.unconfirmedSend, "the session's guard once the payment is out")
        assertNull(restart(hot.store).unconfirmedSend, "the phone's copy once the payment is out")
        assertEquals(TXID, hot.wallet.lastTxid)
    }

    @Test
    fun aKillDuringThePostStillBlocksASecondSendFromTheRelaunchedApp() {
        val hot = FakeHot()
        val session = hot.goLive()
        val relaunched = FakeHot()
        hot.postResult = {
            // The first app is killed here. The relaunched one opens the same phone's copy and tries to pay.
            val restarted = restart(hot.store)
            relaunched.wallet.bindSession(restarted)
            assertFalse(relaunched.wallet.broadcast(restarted, 50_000, 1_128, DESTINATION), "a payment from the relaunched app")
            assertEquals(EARLIER_UNSETTLED, relaunched.wallet.lastError)
            throw MobileSignerException.BroadcastUnknown(UNKNOWN_MESSAGE)
        }
        assertFalse(hot.wallet.broadcast(session, 100_000, 1_128, DESTINATION))
        assertEquals(emptyList<PrepareCall>(), relaunched.prepares, "the relaunched app prepared a payment")
        assertEquals(emptyList<PostCall>(), relaunched.posts, "the relaunched app posted a payment")
    }

    @Test
    fun aRefusalByTheNodeClearsTheGuardAndAnUnknownAnswerKeepsIt() {
        val hot = FakeHot()
        val session = hot.goLive()
        hot.postResult = { throw MobileSignerException.InvalidPsbtJson("esplora post: status code 400: bad-txns-inputs-missingorspent") }
        assertFalse(hot.wallet.broadcast(session, 100_000, 1_128, DESTINATION))
        assertEquals("ส่งไม่สำเร็จ", hot.wallet.lastError)
        assertNull(hot.wallet.unconfirmedSend, "the wallet after a refusal")
        assertNull(restart(hot.store).unconfirmedSend, "the phone after a refusal")
        hot.postResult = { throw MobileSignerException.BroadcastUnknown(UNKNOWN_MESSAGE) }
        assertFalse(hot.wallet.broadcast(session, 100_000, 1_128, DESTINATION), "the retry after the refusal reached the post")
        assertEquals(UnconfirmedSend(TXID, SENT_AT), hot.wallet.unconfirmedSend, "the wallet after an unknown answer")
        assertEquals(UnconfirmedSend(TXID, SENT_AT), restart(hot.store).unconfirmedSend, "the phone after an unknown answer")
        assertEquals(2, hot.posts.size)
    }

    @Test
    fun aRefusedPreparationSetsNoGuardAndSpeaksThai() {
        val hot = FakeHot()
        val session = hot.goLive()
        val writes = hot.store.writes.size
        hot.prepareResult = { throw MobileSignerException.InvalidPsbtJson("amount 100 is below the 546 sat dust limit") }
        assertFalse(hot.wallet.broadcast(session, 100, 1_128, DESTINATION))
        assertEquals("จำนวนน้อยกว่าขั้นต่ำที่เครือข่ายรับ (546 sat)", hot.wallet.lastError)
        assertNull(hot.wallet.unconfirmedSend)
        assertNull(session.unconfirmedSend)
        assertEquals(writes, hot.store.writes.size, "the phone was written for a payment that was never signed")
        assertEquals(emptyList<PostCall>(), hot.posts)
    }

    @Test
    fun aGuardThePhoneCannotKeepStopsThePaymentBeforeThePost() {
        val hot = FakeHot()
        val session = hot.goLive()
        hot.store.write = WriteResult.Unavailable(Why.TryLater)
        val returned = hot.wallet.broadcast(session, 100_000, 1_128, DESTINATION)
        assertEquals(Seen(false, "", GUARD_NOT_SAVED, 1_000_000L, "tb1qbefore"), hot.wallet.seen(returned))
        assertEquals(emptyList<PostCall>(), hot.posts, "a post without a saved guard")
        assertNull(hot.wallet.unconfirmedSend, "a guard for a payment that was never posted")
        assertNull(session.unconfirmedSend, "the session's guard for a payment that was never posted")
        hot.store.write = null
        assertNull(restart(hot.store).unconfirmedSend, "what the next cold start restores")
        assertTrue(hot.wallet.broadcast(session, 100_000, 1_128, DESTINATION), "the retry once the phone can save")
        assertEquals(1, hot.posts.size)
    }

    @Test
    fun aLoadedBindWhoseHistoryHoldsTheTxidSettlesItAtOnce() {
        val hot = FakeHot()
        val session = hot.unknownSend()
        hot.history = listOf(HotTx(TXID, -101_128L, 0u, null)) + hot.history
        hot.wallet.bindSession(session)
        assertNull(hot.wallet.unconfirmedSend, "the payment is out and Home lists it")
        assertNull(restart(hot.store).unconfirmedSend, "what the next cold start restores")
        assertTrue(hot.wallet.broadcast(session, 50_000, 1_128, DESTINATION), "a new payment can start")
    }

    @Test
    fun aClockMovedForwardNeverSettlesAGuardTheHistoryDoesNotList() {
        val hot = FakeHot()
        val session = hot.unknownSend()
        for (ahead in listOf(119L, 120L, 365L * 86_400)) {
            hot.clock = SENT_AT + ahead
            hot.wallet.bindSession(session)
            assertEquals(UnconfirmedSend(TXID, SENT_AT), hot.wallet.unconfirmedSend, "the guard $ahead s later with a loaded history that lacks the txid")
            assertFalse(hot.wallet.broadcast(session, 50_000, 1_128, DESTINATION), "a payment $ahead s later")
        }
        assertEquals(1, hot.posts.size, "only the unknown payment was posted")
        assertEquals(UnconfirmedSend(TXID, SENT_AT), restart(hot.store).unconfirmedSend, "what the next cold start restores")
    }

    @Test
    fun aBindWhoseHistoryCouldNotBeLoadedNeverSettlesIt() {
        val hot = FakeHot()
        val session = hot.unknownSend()
        hot.clock = SENT_AT + 600
        hot.historyFails = true
        hot.wallet.bindSession(session)
        assertTrue(hot.wallet.live, "the snapshot loaded")
        assertEquals(emptyList<HotTx>(), hot.wallet.history)
        assertEquals(UnconfirmedSend(TXID, SENT_AT), hot.wallet.unconfirmedSend, "an empty list from a failed call says nothing about the payment")
        assertFalse(hot.wallet.broadcast(session, 50_000, 1_128, DESTINATION))
        assertEquals(1, hot.posts.size)
        hot.historyFails = false
        hot.wallet.bindSession(session)
        assertEquals(UnconfirmedSend(TXID, SENT_AT), hot.wallet.unconfirmedSend, "a list that loads without the txid")
        hot.history = listOf(HotTx(TXID, -101_128L, 0u, null)) + hot.history
        hot.wallet.bindSession(session)
        assertNull(hot.wallet.unconfirmedSend, "a list that loads with the txid")
    }

    @Test
    fun aFailedBindNeverSettlesItHoweverLongAgo() {
        val hot = FakeHot()
        val session = hot.unknownSend()
        hot.clock = SENT_AT + 600
        hot.snapshotResult = { throw IllegalStateException("esplora unreachable") }
        hot.wallet.bindSession(session)
        assertEquals(UnconfirmedSend(TXID, SENT_AT), hot.wallet.unconfirmedSend)
        assertFalse(hot.wallet.broadcast(session, 50_000, 1_128, DESTINATION))
        assertEquals(EARLIER_UNSETTLED, hot.wallet.lastError, "the earlier payment is named before the unknown balance")
    }

    @Test
    fun theClearControlIsOfferedAfterTwoMinutesOrANegativeAgeAndClearsNothingItself() {
        assertFalse(offersUnconfirmedClear(SENT_AT, SENT_AT), "at once")
        assertFalse(offersUnconfirmedClear(SENT_AT, SENT_AT + 119), "at 119 s")
        assertTrue(offersUnconfirmedClear(SENT_AT, SENT_AT + 120), "at 120 s")
        assertTrue(offersUnconfirmedClear(SENT_AT, SENT_AT - 1), "with the clock moved back")
        val hot = FakeHot()
        hot.unknownSend()
        hot.clock = SENT_AT + 119
        assertFalse(hot.wallet.unconfirmedClearOffered(), "the wallet at 119 s")
        hot.clock = SENT_AT + 120
        assertTrue(hot.wallet.unconfirmedClearOffered(), "the wallet at 120 s")
        assertEquals(UnconfirmedSend(TXID, SENT_AT), hot.wallet.unconfirmedSend, "the offer cleared the guard")
        hot.wallet.useDemoFixture()
        assertFalse(hot.wallet.unconfirmedClearOffered(), "without a guard")
    }

    @Test
    fun aClockMovedBackDoesNotStickBecauseTheUserCanClearTheGuard() {
        val hot = FakeHot()
        val session = hot.unknownSend()
        hot.clock = SENT_AT - 365L * 86_400
        hot.wallet.bindSession(session)
        assertEquals(UnconfirmedSend(TXID, SENT_AT), hot.wallet.unconfirmedSend, "a bind with the clock moved back")
        assertTrue(hot.wallet.unconfirmedClearOffered(), "the control with a negative age")
        hot.wallet.clearUnconfirmedSend(session)
        assertNull(hot.wallet.unconfirmedSend, "the wallet after the clear")
        assertNull(session.unconfirmedSend, "the session after the clear")
        assertNull(restart(hot.store).unconfirmedSend, "the phone after the clear")
        assertTrue(hot.wallet.broadcast(session, 50_000, 1_128, DESTINATION), "a new payment after the clear")
        assertEquals(2, hot.posts.size)
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
        before.postResult = { throw MobileSignerException.BroadcastUnknown(UNKNOWN_MESSAGE) }
        assertFalse(before.wallet.broadcast(session, 100_000, 1_128, DESTINATION))
        val after = FakeHot()
        val restarted = restart(store)
        after.wallet.bindSession(restarted)
        assertEquals(UnconfirmedSend(TXID, SENT_AT), after.wallet.unconfirmedSend, "the new wallet after the restart")
        assertFalse(after.wallet.broadcast(restarted, 100_000, 1_128, DESTINATION), "a payment after the restart")
        assertEquals(EARLIER_UNSETTLED, after.wallet.lastError)
        assertEquals(emptyList<PrepareCall>(), after.prepares, "a payment was prepared after the restart")
    }

    @Test
    fun aRestoredPaymentThatTheHistoryListsSettlesAndLeavesTheSavedWallet() {
        val store = phoneHolding(UnconfirmedSend(TXID, SENT_AT))
        val session = restart(store)
        val hot = FakeHot()
        hot.wallet.bindSession(session)
        assertEquals(UnconfirmedSend(TXID, SENT_AT), hot.wallet.unconfirmedSend, "before the history lists it")
        hot.history = listOf(HotTx(TXID, -101_128L, 0u, null)) + hot.history
        hot.wallet.bindSession(session)
        assertNull(hot.wallet.unconfirmedSend, "the payment is out and Home lists it")
        assertNull(restart(store).unconfirmedSend, "what the next cold start restores")
        assertTrue(hot.wallet.broadcast(session, 50_000, 1_128, DESTINATION), "a new payment can start")
    }

    @Test
    fun aRestoredPaymentStaysUntilTheHistoryListsItHoweverOldItIs() {
        val store = phoneHolding(UnconfirmedSend(TXID, SENT_AT))
        val session = restart(store)
        val hot = FakeHot()
        hot.clock = SENT_AT + 120
        hot.wallet.bindSession(session)
        assertEquals(UnconfirmedSend(TXID, SENT_AT), hot.wallet.unconfirmedSend, "at two minutes")
        hot.clock = SENT_AT + 30L * 86_400
        hot.wallet.bindSession(session)
        assertEquals(UnconfirmedSend(TXID, SENT_AT), hot.wallet.unconfirmedSend, "a month later")
        assertEquals(UnconfirmedSend(TXID, SENT_AT), restart(store).unconfirmedSend, "what the next cold start restores")
    }

    @Test
    fun theDemoFixtureNeverTouchesASavedPaymentOfUnknownOutcome() {
        val store = phoneHolding(UnconfirmedSend(TXID, SENT_AT))
        val session = restart(store)
        val hot = FakeHot()
        hot.wallet.bindSession(session)
        hot.wallet.useDemoFixture()
        assertNull(hot.wallet.unconfirmedSend, "the fixture's own clear")
        assertEquals(1, store.writes.size, "the fixture wrote")
        assertEquals(UnconfirmedSend(TXID, SENT_AT), restart(store).unconfirmedSend, "what the next cold start restores")
        hot.wallet.bindSession(session)
        assertEquals(UnconfirmedSend(TXID, SENT_AT), hot.wallet.unconfirmedSend, "the next bind of the real key")
    }

    @Test
    fun aBindOfASessionWithoutAPaymentOfUnknownOutcomeKeepsTheWalletsOwn() {
        val hot = FakeHot()
        hot.unknownSend()
        hot.wallet.bindSession(sessionWithWords(WORDS, PASSPHRASE))
        assertEquals(UnconfirmedSend(TXID, SENT_AT), hot.wallet.unconfirmedSend)
    }

    @Test
    fun anyOtherPostFailureIsAPlainFailureThatCanBeTriedAgain() {
        val hot = FakeHot()
        val session = hot.goLive()
        hot.postResult = { throw MobileSignerException.InvalidPsbtJson("broadcast: not a transaction: odd length") }
        assertFalse(hot.wallet.broadcast(session, 100_000, 1_128, DESTINATION))
        assertEquals("ส่งไม่สำเร็จ", hot.wallet.lastError)
        assertNull(hot.wallet.unconfirmedSend)
        hot.postResult = { HotSendResult(TXID) }
        assertTrue(hot.wallet.broadcast(session, 100_000, 1_128, DESTINATION), "the retry reaches the post")
        assertEquals(2, hot.posts.size)
    }
}
