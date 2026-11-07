package com.mebit.app.data

import com.mebit.app.Why
import com.mebit.app.WriteMode
import com.mebit.app.WriteResult
import com.mebit.app.data.SingleSigWallet.DamagedGuard
import com.mebit.app.data.SingleSigWallet.HeldSend
import com.mebit.app.data.SingleSigWallet.UnconfirmedSend
import com.mebit.app.data.SingleSigWallet.Unsettled
import com.mebit.ffi.signer.FeeTiers
import com.mebit.ffi.signer.HotSendResult
import com.mebit.ffi.signer.HotTx
import com.mebit.ffi.signer.HotScan
import com.mebit.ffi.signer.HotWalletSnapshot
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.MobileSignerException
import com.mebit.ffi.signer.PreparedSend
import com.mebit.ffi.signer.SoftwareAccountKey
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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

/** The txid hot_prepare_send_at_rate computes for the test payment. The codec keeps only a 64-digit txid, so a made-up one would never reach the phone. */
private const val TXID = TEST_TXID

// The Rust MobileSignerError::BroadcastUnknown Display, which is the Kotlin message.
private const val UNKNOWN_MESSAGE = "it is not known whether transaction $TXID was sent, so do not repeat the payment until the wallet and this transaction id have been checked"

// What Home would print raw, so none of it may be the English Rust text.
private const val UNKNOWN_SHOWN = "ยังไม่ทราบว่าการส่งออกไปแล้วหรือยัง อย่าส่งซ้ำ"
private const val NOT_LIVE = "ยังดึงยอดคงเหลือไม่ได้ จึงยังส่งไม่ได้"
private const val EARLIER_UNSETTLED = "การส่งครั้งก่อนยังยืนยันไม่ได้ จึงยังส่งใหม่ไม่ได้"
private const val GUARD_NOT_SAVED = "บันทึกสถานะการส่งไม่ได้ จึงยังไม่ส่ง ลองใหม่ภายหลัง"
private const val PLAIN_FAILURE = "ส่งไม่สำเร็จ ลองใหม่ภายหลัง"

private data class PrepareCall(val words: List<String>, val passphrase: String, val network: KeyNetwork, val destination: String, val amountSats: ULong, val feeRateSatVb: ULong)

private data class PrepareAllCall(val words: List<String>, val passphrase: String, val network: KeyNetwork, val destination: String, val feeRateSatVb: ULong)

private data class PostCall(val network: KeyNetwork, val hex: String, val txid: String)

/** What the send screens read after a broadcast. */
private data class Seen(val returned: Boolean, val txid: String, val error: String?, val balanceSats: Long, val receiveAddress: String)

private fun SingleSigWallet.seen(returned: Boolean) = Seen(returned, lastTxid, lastError, balanceSats, receiveAddress)

/** Prepare, then post with no question between, as the Review screen does when the fee needs none ([feeQuestion]). The demo mock holds nothing to post. */
internal fun SingleSigWallet.broadcast(session: OnboardingSession, amountSats: Long, feeRateSatVb: Long, destination: String, network: KeyNetwork = KeyNetwork.TEST, sendAll: Boolean = false): Boolean =
    prepare(session, amountSats, feeRateSatVb, destination, network, sendAll) && (heldSend == null || post(session))

/**
 * Fakes for the hot_* calls. A test swaps [prepareResult], [postResult] or [snapshotResult] to change what the next call does.
 * The clock is [clock] and stands still unless a test moves it. [store] is the phone the live session is saved on, [guards] its
 * guard file, and [fingerprint] the wallet's.
 */
private class FakeHot(val guards: FakePlainFile = FakePlainFile(), val fingerprint: String = TEST_CHECK) {
    var prepareResult: (feeRateSatVb: ULong) -> PreparedSend = { rate -> PreparedSend(TXID, "hex-1", rate * 141uL, 100_000uL) }
    var prepareAllResult: (feeRateSatVb: ULong) -> PreparedSend = { rate -> PreparedSend(TXID, "hex-all", rate * 178uL, 1_000_000uL - rate * 178uL) }
    var postResult: () -> HotSendResult = { HotSendResult(TXID) }
    var snapshotResult: () -> HotWalletSnapshot = { HotWalletSnapshot("tb1qbefore", 1_000_000uL, 1_000_000uL, KeyNetwork.TEST, HotScan.COMPLETE) }
    var clock = SENT_AT
    val prepares = mutableListOf<PrepareCall>()
    val prepareAlls = mutableListOf<PrepareAllCall>()
    val posts = mutableListOf<PostCall>()
    var snapshots = 0
    var history = listOf(HotTx("txid-0", 1_000_000L, 3u, 1_760_000_000uL))
    var historyFails = false
    val store = FakeSecureStore()
    val wallet = SingleSigWallet(
        prepareAtRate = { words, passphrase, network, _, destination, amountSats, feeRateSatVb ->
            prepares += PrepareCall(words, passphrase, network, destination, amountSats, feeRateSatVb)
            prepareResult(feeRateSatVb)
        },
        prepareAll = { words, passphrase, network, _, destination, feeRateSatVb ->
            prepareAlls += PrepareAllCall(words, passphrase, network, destination, feeRateSatVb)
            prepareAllResult(feeRateSatVb)
        },
        postHex = { network, hex, txid ->
            posts += PostCall(network, hex, txid)
            postResult()
        },
        snapshot = { _, _, _, _ ->
            snapshots++
            snapshotResult()
        },
        fetchHistory = { _, _, _, _ -> if (historyFails) throw IllegalStateException("history unreadable") else history },
        nowSeconds = { clock },
        receiveAddressOf = { _, _, _, _, _ -> "tb1qoffline" },
    )

    /** A 12-word session saved on [store] and bound through a working snapshot: the wallet is live with 1,000,000 sats. */
    fun goLive(): OnboardingSession {
        val session = importedSession(store, guards, fingerprint)
        assertEquals(SaveState.Saved, session.saved, "the test session is saved on its phone")
        assertEquals(WORDS, session.words)
        wallet.bindSession(session)
        assertTrue(wallet.live, "bindSession went live")
        assertEquals(history, wallet.history)
        return session
    }
}

/** A session of [WORDS] and [PASSPHRASE] imported on the phone of [store] and [guards], made without the native library. Saved when [store] keeps it. */
private fun importedSession(store: FakeSecureStore, guards: FakePlainFile, fingerprint: String = TEST_CHECK): OnboardingSession =
    OnboardingSession(SavedWallet(store), restoreKey = { w, _, _, _ -> SoftwareAccountKey(w, fingerprint, "tpub-test", "m/48'/1'/0'/2'") }, payments = PendingPayments(guards)).apply {
        restoreFromWords(WORDS, PASSPHRASE) // staged, because of the passphrase
        acceptStagedKey()
    }

/** A live wallet whose broadcast at [SENT_AT] ended in BroadcastUnknown for [TXID]. The post works again afterwards, so a payment that wrongly reaches it goes out. */
private fun FakeHot.unknownSend(): OnboardingSession {
    val session = goLive()
    postResult = { throw MobileSignerException.BroadcastUnknown(UNKNOWN_MESSAGE) }
    assertFalse(wallet.broadcast(session, 100_000, 8, DESTINATION))
    assertEquals(UnconfirmedSend(TXID, SENT_AT), wallet.unconfirmedSend)
    postResult = { HotSendResult(TXID) }
    return session
}

/** The real signed payment [SIGNED_HEX] as the core hands it over, so its guard keeps the bytes. */
private val SIGNED_PAYMENT: (ULong) -> PreparedSend = { rate -> PreparedSend(SIGNED_TXID, SIGNED_HEX, rate * 141uL, 100_000uL) }

/** The guard of [SIGNED_PAYMENT] posted at [SENT_AT], and the call its every post makes. */
private val SIGNED_GUARD = UnconfirmedSend(SIGNED_TXID, SENT_AT, SIGNED_HEX)
private val SIGNED_POST = PostCall(KeyNetwork.TEST, SIGNED_HEX, SIGNED_TXID)

/** A live wallet whose first post of [SIGNED_PAYMENT] at [SENT_AT] ended in BroadcastUnknown. A post that reaches the node afterwards is out. */
private fun FakeHot.unknownSignedSend(): OnboardingSession {
    prepareResult = SIGNED_PAYMENT
    val session = goLive()
    postResult = { throw MobileSignerException.BroadcastUnknown(UNKNOWN_MESSAGE) }
    assertFalse(wallet.broadcast(session, 100_000, 8, DESTINATION))
    assertEquals(SIGNED_GUARD, wallet.unconfirmedSend)
    postResult = { HotSendResult(SIGNED_TXID) }
    return session
}

/** A phone that saved the public test wallet and keeps [pending] as that wallet's guard. */
private class Phone(pending: UnconfirmedSend?) {
    val store = FakeSecureStore().also { assertIs<SaveResult.Written>(SavedWallet(it).save(testRecord(), WriteMode.Create)) }
    val guards = FakePlainFile().also { if (pending != null) assertEquals(Claim.Written, PendingPayments(it).claim(TEST_CHECK, pending)) }
}

/**
 * What the gate does at a cold start: opens the copy [store] holds and installs it into a new session that saves through [store]
 * and keeps its guard in [guards]. The key is made without the native library.
 */
private fun restart(store: FakeSecureStore, guards: FakePlainFile): OnboardingSession {
    val opened = assertIs<UnlockResult.Opened>(SavedWallet(store).openNow(), "the saved copy")
    val session = OnboardingSession(SavedWallet(store), restoreKey = { w, _, _, _ -> SoftwareAccountKey(w, opened.record.fingerprint, "tpub-test", "m/48'/1'/0'/2'") }, payments = PendingPayments(guards))
    assertTrue(session.install(opened.record, opened.version, opened.oldGuard), "install")
    return session
}

private fun walletThatMustNotCallHot(nowSeconds: () -> Long = { 0L }) = SingleSigWallet(
    prepareAtRate = { _, _, _, _, _, _, _ -> fail("prepare ran") },
    prepareAll = { _, _, _, _, _, _ -> fail("prepareAll ran") },
    postHex = { _, _, _ -> fail("post ran") },
    snapshot = { _, _, _, _ -> fail("snapshot ran") },
    fetchHistory = { _, _, _, _ -> fail("history ran") },
    nowSeconds = nowSeconds,
)

/** The demo fixture holds 1,250,000 sats: a payment above that is refused, one within it is deducted with its fee under a mock txid. */
private fun assertMockPayments(wallet: SingleSigWallet, session: OnboardingSession) {
    assertFalse(wallet.broadcast(session, 1_250_000, 8, DESTINATION))
    assertEquals("ยอดไม่พอ", wallet.lastError)
    assertEquals(1_250_000L, wallet.balanceSats)
    assertTrue(wallet.broadcast(session, 100_000, 8, DESTINATION))
    assertNull(wallet.lastError)
    assertEquals(1_148_872L, wallet.balanceSats)
    assertEquals(1_148_872L, wallet.spendableSats)
    assertTrue(wallet.lastTxid.startsWith("mocktxid_"), wallet.lastTxid)
}

class SingleSigWalletBroadcastTest {
    @Test
    fun aSentPaymentStaysSentWhenTheBalanceRefreshFails() {
        val hot = FakeHot()
        val session = hot.goLive()
        hot.snapshotResult = { throw IllegalStateException("esplora answer unreadable") }
        val returned = hot.wallet.broadcast(session, 100_000, 8, DESTINATION)
        assertEquals(Seen(true, TXID, null, 1_000_000L, "tb1qbefore"), hot.wallet.seen(returned), "a payment that went out reads as sent")
    }

    @Test
    fun aFailedSendFailsAndNeverRefreshes() {
        val hot = FakeHot()
        val session = hot.goLive()
        val snapshots = hot.snapshots
        hot.postResult = { throw MobileSignerException.InvalidPsbtJson("esplora post: status code 400: bad-txns-inputs-missingorspent") }
        val returned = hot.wallet.broadcast(session, 100_000, 8, DESTINATION)
        assertEquals(Seen(false, "", "ส่งไม่สำเร็จ", 1_000_000L, "tb1qbefore"), hot.wallet.seen(returned))
        assertEquals(snapshots, hot.snapshots, "no refresh after a failed send")
    }

    @Test
    fun aSentPaymentTakesTheBalanceAndAddressFromTheRefresh() {
        val hot = FakeHot()
        val session = hot.goLive()
        val snapshots = hot.snapshots
        hot.snapshotResult = { HotWalletSnapshot("tb1qafter", 898_872uL, 800_000uL, KeyNetwork.TEST, HotScan.COMPLETE) }
        val returned = hot.wallet.broadcast(session, 100_000, 8, DESTINATION)
        assertEquals(Seen(true, TXID, null, 898_872L, "tb1qafter"), hot.wallet.seen(returned))
        assertEquals(800_000L, hot.wallet.spendableSats, "the spendable figure from the refresh")
        assertEquals(snapshots + 1, hot.snapshots, "one refresh after the send")
    }

    @Test
    fun thePreparationGetsTheSessionKeyAndThePaymentAsGivenAndThePostGetsWhatItPrepared() {
        val hot = FakeHot()
        val session = hot.goLive()
        hot.wallet.broadcast(session, 100_000, 8, DESTINATION, KeyNetwork.MAIN)
        assertEquals(listOf(PrepareCall(WORDS, PASSPHRASE, KeyNetwork.MAIN, DESTINATION, 100_000uL, 8uL)), hot.prepares)
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
        assertFalse(hot.wallet.broadcast(session, 100_000, 8, DESTINATION), "no payment from a balance nobody knows")
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
            HotWalletSnapshot("tb1qafter", 898_872uL, 898_872uL, KeyNetwork.TEST, HotScan.COMPLETE)
        }
        assertEquals(0L, hot.wallet.lastSentAtSeconds, "nothing sent yet")
        assertTrue(hot.wallet.broadcast(session, 100_000, 8, DESTINATION))
        assertEquals(SENT_AT + 7, hot.wallet.lastSentAtSeconds)
    }

    @Test
    fun aSentPaymentWhoseRefreshFailsStillHasItsSendTime() {
        val hot = FakeHot()
        val session = hot.goLive()
        hot.snapshotResult = { throw IllegalStateException("esplora answer unreadable") }
        assertTrue(hot.wallet.broadcast(session, 100_000, 8, DESTINATION))
        assertEquals(SENT_AT, hot.wallet.lastSentAtSeconds)
    }

    @Test
    fun aFailedSendLeavesNoSendTime() {
        val hot = FakeHot()
        val session = hot.goLive()
        hot.postResult = { throw MobileSignerException.InvalidPsbtJson("esplora post: status code 400: bad-txns-inputs-missingorspent") }
        assertFalse(hot.wallet.broadcast(session, 100_000, 8, DESTINATION))
        assertEquals(0L, hot.wallet.lastSentAtSeconds)
    }

    @Test
    fun anUnexpectedFailureInThePostKeepsTheGuardBecauseThePaymentMayBeOut() {
        val hot = FakeHot()
        val session = hot.goLive()
        hot.postResult = { throw IllegalStateException("the bridge failed after the post") }
        assertFalse(hot.wallet.broadcast(session, 100_000, 8, DESTINATION))
        assertEquals(UnconfirmedSend(TXID, SENT_AT), hot.wallet.unconfirmedSend, "the wallet after an unexpected failure")
        assertEquals(UnconfirmedSend(TXID, SENT_AT), restart(hot.store, hot.guards).unconfirmedSend(), "the phone after an unexpected failure")
        assertFalse(hot.wallet.broadcast(session, 100_000, 8, DESTINATION), "the retry reached the post")
        assertEquals(1, hot.posts.size)
    }

    @Test
    fun aMockPaymentRecordsTheClockAndARefusedOneDoesNot() {
        val wallet = walletThatMustNotCallHot { SENT_AT }
        val session = OnboardingSession()
        wallet.bindSession(session)
        assertFalse(wallet.broadcast(session, 1_250_000, 8, DESTINATION))
        assertEquals(0L, wallet.lastSentAtSeconds)
        assertTrue(wallet.broadcast(session, 100_000, 8, DESTINATION))
        assertEquals(SENT_AT, wallet.lastSentAtSeconds)
    }

    @Test
    fun theDemoFixtureForgetsTheSendTime() {
        val hot = FakeHot()
        val session = hot.goLive()
        assertTrue(hot.wallet.broadcast(session, 100_000, 8, DESTINATION))
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
        val returned = hot.wallet.broadcast(session, 100_000, 8, DESTINATION)
        assertEquals(Seen(false, "", NOT_LIVE, 0L, "tb1qoffline"), hot.wallet.seen(returned))
        assertEquals(0L, hot.wallet.lastSentAtSeconds)
        assertEquals(emptyList<PrepareCall>(), hot.prepares)
    }

    @Test
    fun aRealKeyWhoseFirstBindHasNotEndedIsRefusedEvenWhileTheDemoBalanceShows() {
        val hot = FakeHot()
        val session = sessionWithWords(WORDS, PASSPHRASE)
        assertFalse(hot.wallet.broadcast(session, 100_000, 8, DESTINATION), "a wallet never bound")
        assertEquals(NOT_LIVE, hot.wallet.lastError)
        hot.wallet.bindSession(OnboardingSession())
        val returned = hot.wallet.broadcast(session, 100_000, 8, DESTINATION)
        assertEquals(Seen(false, "", NOT_LIVE, 1_250_000L, "tb1qmebitdemosinglesig0000000000000000recv"), hot.wallet.seen(returned), "the demo's balance pays for nothing")
        assertEquals(0L, hot.wallet.lastSentAtSeconds)
        assertEquals(emptyList<PrepareCall>(), hot.prepares)
    }

    @Test
    fun anUnknownBroadcastIsNeitherASentPaymentNorAFailureToRetry() {
        val hot = FakeHot()
        val session = hot.goLive()
        hot.postResult = { throw MobileSignerException.BroadcastUnknown(UNKNOWN_MESSAGE) }
        val returned = hot.wallet.broadcast(session, 100_000, 8, DESTINATION)
        assertEquals(Seen(false, "", UNKNOWN_SHOWN, 1_000_000L, "tb1qbefore"), hot.wallet.seen(returned), "no txid of a sent payment")
        assertEquals(0L, hot.wallet.lastSentAtSeconds, "no send time of a sent payment")
        assertEquals(UnconfirmedSend(TXID, SENT_AT), hot.wallet.unconfirmedSend, "the prepared txid and the clock when the post began")
    }

    @Test
    fun noPaymentIsPreparedWhileAnUnknownOneIsUnsettled() {
        val hot = FakeHot()
        val session = hot.unknownSend()
        val returned = hot.wallet.broadcast(session, 50_000, 8, DESTINATION)
        assertEquals(Seen(false, "", EARLIER_UNSETTLED, 1_000_000L, "tb1qbefore"), hot.wallet.seen(returned))
        assertEquals(1, hot.prepares.size, "only the unknown payment was prepared")
        assertEquals(1, hot.posts.size, "only the unknown payment was posted")
    }

    @Test
    fun theGuardHoldsThePreparedTxidWhateverTheErrorSays() {
        val hot = FakeHot()
        val session = hot.goLive()
        hot.postResult = { throw MobileSignerException.BroadcastUnknown("the explorer's answer was lost") }
        assertFalse(hot.wallet.broadcast(session, 100_000, 8, DESTINATION))
        assertEquals(UnconfirmedSend(TXID, SENT_AT), hot.wallet.unconfirmedSend)
        hot.postResult = { HotSendResult(TXID) }
        assertFalse(hot.wallet.broadcast(session, 100_000, 8, DESTINATION))
        assertEquals(1, hot.posts.size)
    }

    @Test
    fun theGuardIsOnThePhoneBeforeThePostStartsAndGoneOnceThePaymentIsOut() {
        val hot = FakeHot()
        val session = hot.goLive()
        var inTheFile: Unsettled? = null
        var onThePhone: Unsettled? = null
        var inTheSession: Unsettled? = null
        hot.postResult = {
            inTheFile = hot.guards.guardOf()
            onThePhone = restart(hot.store, hot.guards).unconfirmedSend()
            inTheSession = session.unconfirmedSend()
            HotSendResult(TXID)
        }
        assertTrue(hot.wallet.broadcast(session, 100_000, 8, DESTINATION))
        assertEquals(UnconfirmedSend(TXID, SENT_AT), inTheFile, "the guard file when the post ran")
        assertEquals(UnconfirmedSend(TXID, SENT_AT), onThePhone, "what a cold start during the post would restore")
        assertEquals(UnconfirmedSend(TXID, SENT_AT), inTheSession, "the session during the post")
        assertNull(hot.wallet.unconfirmedSend, "the wallet's guard once the payment is out")
        assertNull(session.unconfirmedSend(), "the session's guard once the payment is out")
        assertNull(hot.guards.guardOf(), "the guard file once the payment is out")
        assertNull(restart(hot.store, hot.guards).unconfirmedSend(), "the phone's copy once the payment is out")
        assertEquals(TXID, hot.wallet.lastTxid)
    }

    @Test
    fun aKillDuringThePostStillBlocksASecondSendFromTheRelaunchedApp() {
        val hot = FakeHot()
        val session = hot.goLive()
        val relaunched = FakeHot(hot.guards)
        hot.postResult = {
            // The first app is killed here. The relaunched one opens the same phone's copy and tries to pay.
            val restarted = restart(hot.store, hot.guards)
            relaunched.wallet.bindSession(restarted)
            assertFalse(relaunched.wallet.broadcast(restarted, 50_000, 8, DESTINATION), "a payment from the relaunched app")
            assertEquals(EARLIER_UNSETTLED, relaunched.wallet.lastError)
            throw MobileSignerException.BroadcastUnknown(UNKNOWN_MESSAGE)
        }
        assertFalse(hot.wallet.broadcast(session, 100_000, 8, DESTINATION))
        assertEquals(emptyList<PrepareCall>(), relaunched.prepares, "the relaunched app prepared a payment")
        assertEquals(emptyList<PostCall>(), relaunched.posts, "the relaunched app posted a payment")
    }

    @Test
    fun aRefusalByTheNodeClearsTheGuardAndAnUnknownAnswerKeepsIt() {
        val hot = FakeHot()
        val session = hot.goLive()
        hot.postResult = { throw MobileSignerException.InvalidPsbtJson("esplora post: status code 400: bad-txns-inputs-missingorspent") }
        assertFalse(hot.wallet.broadcast(session, 100_000, 8, DESTINATION))
        assertEquals("ส่งไม่สำเร็จ", hot.wallet.lastError)
        assertNull(hot.wallet.unconfirmedSend, "the wallet after a refusal")
        assertNull(restart(hot.store, hot.guards).unconfirmedSend(), "the phone after a refusal")
        hot.postResult = { throw MobileSignerException.BroadcastUnknown(UNKNOWN_MESSAGE) }
        assertFalse(hot.wallet.broadcast(session, 100_000, 8, DESTINATION), "the retry after the refusal reached the post")
        assertEquals(UnconfirmedSend(TXID, SENT_AT), hot.wallet.unconfirmedSend, "the wallet after an unknown answer")
        assertEquals(UnconfirmedSend(TXID, SENT_AT), restart(hot.store, hot.guards).unconfirmedSend(), "the phone after an unknown answer")
        assertEquals(2, hot.posts.size)
    }

    @Test
    fun aRefusedPreparationSetsNoGuardAndSpeaksThai() {
        val hot = FakeHot()
        val session = hot.goLive()
        val writes = hot.store.writes.size
        hot.prepareResult = { throw MobileSignerException.InvalidPsbtJson("amount 100 is below the network's 294 sat dust floor") }
        assertFalse(hot.wallet.broadcast(session, 100, 8, DESTINATION))
        assertEquals("เครือข่ายไม่รับยอดส่งที่ต่ำกว่า 294 sat สำหรับที่อยู่ปลายทางนี้", hot.wallet.lastError)
        assertNull(hot.wallet.unconfirmedSend)
        assertNull(session.unconfirmedSend())
        assertEquals(writes, hot.store.writes.size, "the phone was written for a payment that was never signed")
        assertEquals(0, hot.guards.writes, "the guard file was written for a payment that was never signed")
        assertEquals(emptyList<PostCall>(), hot.posts)
    }

    @Test
    fun aGuardThePhoneCannotKeepStopsThePaymentBeforeThePost() {
        val hot = FakeHot()
        val session = hot.goLive()
        hot.guards.failWrite = true
        val returned = hot.wallet.broadcast(session, 100_000, 8, DESTINATION)
        assertEquals(Seen(false, "", GUARD_NOT_SAVED, 1_000_000L, "tb1qbefore"), hot.wallet.seen(returned))
        assertEquals(emptyList<PostCall>(), hot.posts, "a post without a saved guard")
        assertNull(hot.wallet.unconfirmedSend, "a guard for a payment that was never posted")
        assertNull(session.unconfirmedSend(), "the session's guard for a payment that was never posted")
        hot.guards.failWrite = false
        assertNull(restart(hot.store, hot.guards).unconfirmedSend(), "what the next cold start restores")
        assertTrue(hot.wallet.broadcast(session, 100_000, 8, DESTINATION), "the retry once the phone can save")
        assertEquals(1, hot.posts.size)
        // A file that is there but cannot be read may name a payment that is out, so it reads as one, not as a failed save.
        hot.guards.unreadable = true
        assertFalse(hot.wallet.broadcast(session, 100_000, 8, DESTINATION), "a payment while the guard file cannot be read")
        assertEquals(EARLIER_UNSETTLED, hot.wallet.lastError)
        assertEquals(DamagedGuard, hot.wallet.unconfirmedSend, "what Send and Review show")
        assertEquals(1, hot.posts.size, "a post while the guard file cannot be read")
    }

    @Test
    fun aLoadedBindWhoseHistoryHoldsTheTxidSettlesItAtOnce() {
        val hot = FakeHot()
        val session = hot.unknownSend()
        hot.history = listOf(HotTx(TXID, -101_128L, 0u, null)) + hot.history
        hot.wallet.bindSession(session)
        assertNull(hot.wallet.unconfirmedSend, "the payment is out and Home lists it")
        assertNull(restart(hot.store, hot.guards).unconfirmedSend(), "what the next cold start restores")
        assertTrue(hot.wallet.broadcast(session, 50_000, 8, DESTINATION), "a new payment can start")
    }

    @Test
    fun aClockMovedForwardNeverSettlesAGuardTheHistoryDoesNotList() {
        val hot = FakeHot()
        val session = hot.unknownSend()
        for (ahead in listOf(119L, 120L, 365L * 86_400)) {
            hot.clock = SENT_AT + ahead
            hot.wallet.bindSession(session)
            assertEquals(UnconfirmedSend(TXID, SENT_AT), hot.wallet.unconfirmedSend, "the guard $ahead s later with a loaded history that lacks the txid")
            assertFalse(hot.wallet.broadcast(session, 50_000, 8, DESTINATION), "a payment $ahead s later")
        }
        assertEquals(1, hot.posts.size, "only the unknown payment was posted")
        assertEquals(UnconfirmedSend(TXID, SENT_AT), restart(hot.store, hot.guards).unconfirmedSend(), "what the next cold start restores")
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
        assertFalse(hot.wallet.broadcast(session, 50_000, 8, DESTINATION))
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
        assertFalse(hot.wallet.broadcast(session, 50_000, 8, DESTINATION))
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
        hot.wallet.clearUnconfirmedSend(session, UnconfirmedSend(TXID, SENT_AT))
        assertNull(hot.wallet.unconfirmedSend, "the wallet after the clear")
        assertNull(session.unconfirmedSend(), "the session after the clear")
        assertNull(restart(hot.store, hot.guards).unconfirmedSend(), "the phone after the clear")
        assertTrue(hot.wallet.broadcast(session, 50_000, 8, DESTINATION), "a new payment after the clear")
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
        val phone = Phone(null)
        val session = restart(phone.store, phone.guards)
        val before = FakeHot()
        before.wallet.bindSession(session)
        before.postResult = { throw MobileSignerException.BroadcastUnknown(UNKNOWN_MESSAGE) }
        assertFalse(before.wallet.broadcast(session, 100_000, 8, DESTINATION))
        val after = FakeHot()
        val restarted = restart(phone.store, phone.guards)
        after.wallet.bindSession(restarted)
        assertEquals(UnconfirmedSend(TXID, SENT_AT), after.wallet.unconfirmedSend, "the new wallet after the restart")
        assertFalse(after.wallet.broadcast(restarted, 100_000, 8, DESTINATION), "a payment after the restart")
        assertEquals(EARLIER_UNSETTLED, after.wallet.lastError)
        assertEquals(emptyList<PrepareCall>(), after.prepares, "a payment was prepared after the restart")
    }

    @Test
    fun aRestoredPaymentThatTheHistoryListsSettlesAndLeavesTheSavedWallet() {
        val phone = Phone(UnconfirmedSend(TXID, SENT_AT))
        val session = restart(phone.store, phone.guards)
        val hot = FakeHot()
        hot.wallet.bindSession(session)
        assertEquals(UnconfirmedSend(TXID, SENT_AT), hot.wallet.unconfirmedSend, "before the history lists it")
        hot.history = listOf(HotTx(TXID, -101_128L, 0u, null)) + hot.history
        hot.wallet.bindSession(session)
        assertNull(hot.wallet.unconfirmedSend, "the payment is out and Home lists it")
        assertNull(restart(phone.store, phone.guards).unconfirmedSend(), "what the next cold start restores")
        assertTrue(hot.wallet.broadcast(session, 50_000, 8, DESTINATION), "a new payment can start")
    }

    @Test
    fun aRestoredPaymentStaysUntilTheHistoryListsItHoweverOldItIs() {
        val phone = Phone(UnconfirmedSend(TXID, SENT_AT))
        val session = restart(phone.store, phone.guards)
        val hot = FakeHot()
        hot.clock = SENT_AT + 120
        hot.wallet.bindSession(session)
        assertEquals(UnconfirmedSend(TXID, SENT_AT), hot.wallet.unconfirmedSend, "at two minutes")
        hot.clock = SENT_AT + 30L * 86_400
        hot.wallet.bindSession(session)
        assertEquals(UnconfirmedSend(TXID, SENT_AT), hot.wallet.unconfirmedSend, "a month later")
        assertEquals(UnconfirmedSend(TXID, SENT_AT), restart(phone.store, phone.guards).unconfirmedSend(), "what the next cold start restores")
    }

    @Test
    fun theDemoFixtureNeverTouchesASavedPaymentOfUnknownOutcome() {
        val phone = Phone(UnconfirmedSend(TXID, SENT_AT))
        val session = restart(phone.store, phone.guards)
        val hot = FakeHot()
        hot.wallet.bindSession(session)
        hot.wallet.useDemoFixture()
        assertNull(hot.wallet.unconfirmedSend, "the fixture's own clear")
        assertEquals(1, phone.store.writes.size, "the fixture wrote the wallet")
        assertEquals(1, phone.guards.writes, "the fixture wrote the guard file")
        assertEquals(UnconfirmedSend(TXID, SENT_AT), restart(phone.store, phone.guards).unconfirmedSend(), "what the next cold start restores")
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
        assertFalse(hot.wallet.broadcast(session, 100_000, 8, DESTINATION))
        assertEquals("ส่งไม่สำเร็จ", hot.wallet.lastError)
        assertNull(hot.wallet.unconfirmedSend)
        hot.postResult = { HotSendResult(TXID) }
        assertTrue(hot.wallet.broadcast(session, 100_000, 8, DESTINATION), "the retry reaches the post")
        assertEquals(2, hot.posts.size)
    }
    @Test
    fun theSlipGetsTheFeeTheSignedTransactionPaysNotTheEstimate() {
        val hot = FakeHot()
        val session = hot.goLive()
        hot.prepareResult = { PreparedSend(TXID, "hex-1", 1_672uL, 100_000uL) }
        assertTrue(hot.wallet.broadcast(session, 100_000, 8, DESTINATION))
        assertEquals(1_672L, hot.wallet.lastFeeSats, "two coins at 8 sat/vB, not 8 x 141")
        hot.wallet.useDemoFixture()
        assertEquals(0L, hot.wallet.lastFeeSats)
    }

    // Open item 73: send all over two coins. The core spends every coin and sets the amount, so the app's estimate never reaches it.
    @Test
    fun aSendAllPaymentIsPreparedBySendAllWithTheRateAndTheSlipGetsWhatLeft() {
        val hot = FakeHot()
        val session = hot.goLive()
        assertTrue(hot.wallet.broadcast(session, 999_859, 1, DESTINATION, sendAll = true))
        assertEquals(listOf(PrepareAllCall(WORDS, PASSPHRASE, KeyNetwork.TEST, DESTINATION, 1uL)), hot.prepareAlls)
        assertEquals(emptyList<PrepareCall>(), hot.prepares, "the fixed-amount path never ran")
        assertEquals(listOf(PostCall(KeyNetwork.TEST, "hex-all", TXID)), hot.posts, "the post got what send all prepared")
        assertEquals(999_822L, hot.wallet.lastAmountSats, "the balance less the two-coin fee, not the 141 vB estimate")
        assertEquals(178L, hot.wallet.lastFeeSats)
        assertNull(hot.wallet.unconfirmedSend)
        assertEquals(TXID, hot.wallet.lastTxid)
    }

    @Test
    fun anOrdinaryPaymentNeverTakesTheSendAllPathAndRecordsItsAmount() {
        val hot = FakeHot()
        val session = hot.goLive()
        assertTrue(hot.wallet.broadcast(session, 100_000, 8, DESTINATION))
        assertEquals(emptyList<PrepareAllCall>(), hot.prepareAlls)
        assertEquals(1, hot.prepares.size)
        assertEquals(100_000L, hot.wallet.lastAmountSats)
        hot.wallet.useDemoFixture()
        assertEquals(0L, hot.wallet.lastAmountSats)
    }

    @Test
    fun aSendAllRefusalSetsNoGuardAndSpeaksThaiAndAMockSendAllIsDeducted() {
        val hot = FakeHot()
        val session = hot.goLive()
        hot.prepareAllResult = { throw MobileSignerException.InvalidPsbtJson("invalid PSBT json: the whole balance 403 less the fee 110 is below the network's 294 sat dust floor") }
        assertFalse(hot.wallet.broadcast(session, 514, 1, DESTINATION, sendAll = true))
        assertEquals(sendErrorThai("the whole balance 403 less the fee 110 is below the network's 294 sat dust floor"), hot.wallet.lastError)
        assertNull(hot.wallet.unconfirmedSend)
        assertEquals(emptyList<PostCall>(), hot.posts)
        val demo = walletThatMustNotCallHot()
        val fresh = OnboardingSession()
        demo.bindSession(fresh)
        assertTrue(demo.broadcast(fresh, 1_250_000L - 1_128L, 8, DESTINATION, sendAll = true))
        assertEquals(0L, demo.balanceSats)
        assertEquals(1_250_000L - 1_128L, demo.lastAmountSats)
    }

    @Test
    fun aFailedSendLeavesTheLastFeeAlone() {
        val hot = FakeHot()
        val session = hot.goLive()
        assertTrue(hot.wallet.broadcast(session, 100_000, 8, DESTINATION))
        hot.postResult = { throw MobileSignerException.InvalidPsbtJson("esplora post: status code 400: bad-txns-inputs-missingorspent") }
        assertFalse(hot.wallet.broadcast(session, 100_000, 15, DESTINATION))
        assertEquals(1_128L, hot.wallet.lastFeeSats)
    }

    @Test
    fun aMockPaymentPaysTheEstimate() {
        val wallet = walletThatMustNotCallHot()
        val session = OnboardingSession()
        wallet.bindSession(session)
        assertTrue(wallet.broadcast(session, 100_000, 15, DESTINATION))
        assertEquals(1_250_000L - 100_000L - 2_115L, wallet.balanceSats)
        assertEquals(2_115L, wallet.lastFeeSats)
    }

    // Open item 77. A fee the core charges for many coins is held for the user's word, with nothing written and nothing posted.
    @Test
    fun aHighFeeIsHeldWithNoGuardAndNoPostAndTheConfirmWritesTheGuardThenPosts() {
        val hot = FakeHot()
        val session = hot.goLive()
        val writes = hot.store.writes.size
        val signed = PreparedSend(TXID, "hex-1", 2_760uL, 10_000uL)
        hot.prepareResult = { signed }
        assertTrue(hot.wallet.prepare(session, 10_000, 8, DESTINATION))
        assertEquals(HeldSend(signed, KeyNetwork.TEST), hot.wallet.heldSend)
        assertTrue(feeNeedsConfirmation(2_760, 8 * TYPICAL_VSIZE, 10_000), "what the Review screen asks about")
        assertEquals(writes, hot.store.writes.size, "the phone was written before the user confirmed")
        assertEquals(0, hot.guards.writes, "the guard file was written before the user confirmed")
        assertNull(hot.wallet.unconfirmedSend, "a guard before the user confirmed")
        assertNull(session.unconfirmedSend(), "the session's guard before the user confirmed")
        assertEquals(emptyList<PostCall>(), hot.posts, "a post before the user confirmed")
        assertEquals(Seen(true, "", null, 1_000_000L, "tb1qbefore"), hot.wallet.seen(true), "nothing reads as sent")
        var onThePhone: Unsettled? = null
        hot.postResult = {
            onThePhone = restart(hot.store, hot.guards).unconfirmedSend()
            HotSendResult(TXID)
        }
        assertTrue(hot.wallet.post(session))
        assertEquals(UnconfirmedSend(TXID, SENT_AT), onThePhone, "the guard on the phone when the post ran")
        assertEquals(listOf(PostCall(KeyNetwork.TEST, "hex-1", TXID)), hot.posts)
        assertNull(hot.wallet.heldSend, "the held payment once it is out")
        assertNull(hot.wallet.unconfirmedSend, "the guard once the payment is out")
        assertEquals(TXID, hot.wallet.lastTxid)
        assertEquals(2_760L, hot.wallet.lastFeeSats)
        assertEquals(10_000L, hot.wallet.lastAmountSats)
    }

    @Test
    fun aCancelledHighFeeWritesNoGuardPostsNothingAndThePaymentCanBePreparedAgain() {
        val hot = FakeHot()
        val session = hot.goLive()
        val writes = hot.store.writes.size
        hot.prepareResult = { PreparedSend(TXID, "hex-1", 2_760uL, 10_000uL) }
        assertTrue(hot.wallet.prepare(session, 10_000, 8, DESTINATION))
        hot.wallet.dropHeldSend()
        assertNull(hot.wallet.heldSend)
        assertNull(hot.wallet.lastError, "a cancel is not a failure")
        assertEquals(writes, hot.store.writes.size, "the phone was written for a payment the user let go")
        assertEquals(0, hot.guards.writes, "the guard file was written for a payment the user let go")
        assertNull(hot.wallet.unconfirmedSend)
        assertEquals(emptyList<PostCall>(), hot.posts)
        assertFalse(hot.wallet.post(session), "a post with nothing held")
        assertEquals(emptyList<PostCall>(), hot.posts, "a post with nothing held reached the node")
        // The user changed the tier and tried again.
        hot.prepareResult = { rate -> PreparedSend(TXID, "hex-1", rate * 345uL, 10_000uL) }
        assertTrue(hot.wallet.broadcast(session, 10_000, 1, DESTINATION))
        assertEquals(listOf(PostCall(KeyNetwork.TEST, "hex-1", TXID)), hot.posts)
        assertEquals(345L, hot.wallet.lastFeeSats)
    }

    @Test
    fun aHeldPaymentIsReplacedByTheNextPrepareAndForgottenByTheDemoFixture() {
        val hot = FakeHot()
        val session = hot.goLive()
        hot.prepareResult = { PreparedSend(TXID, "hex-first", 2_760uL, 10_000uL) }
        assertTrue(hot.wallet.prepare(session, 10_000, 8, DESTINATION))
        hot.prepareResult = { PreparedSend(TXID, "hex-second", 1_128uL, 10_000uL) }
        assertTrue(hot.wallet.prepare(session, 10_000, 8, DESTINATION))
        assertEquals("hex-second", hot.wallet.heldSend?.prepared?.hex)
        assertTrue(hot.wallet.post(session))
        assertEquals(listOf(PostCall(KeyNetwork.TEST, "hex-second", TXID)), hot.posts, "only the payment the user saw last")
        assertTrue(hot.wallet.prepare(session, 10_000, 8, DESTINATION))
        hot.wallet.useDemoFixture()
        assertNull(hot.wallet.heldSend)
    }

    @Test
    fun aRefusedPreparationHoldsNothing() {
        val hot = FakeHot()
        val session = hot.goLive()
        hot.prepareResult = { PreparedSend(TXID, "hex-1", 2_760uL, 10_000uL) }
        assertTrue(hot.wallet.prepare(session, 10_000, 8, DESTINATION))
        hot.prepareResult = { throw MobileSignerException.InvalidPsbtJson("invalid PSBT json: insufficient funds: have 1000000 confirmed, need 2000000") }
        assertFalse(hot.wallet.prepare(session, 2_000_000, 8, DESTINATION))
        assertNull(hot.wallet.heldSend, "the payment held before the refusal")
        assertFalse(hot.wallet.post(session))
        assertEquals(emptyList<PostCall>(), hot.posts)
    }

    @Test
    fun aPostWithTheHeldNetworkNeverTheDefault() {
        val hot = FakeHot()
        val session = hot.goLive()
        assertTrue(hot.wallet.prepare(session, 100_000, 8, DESTINATION, KeyNetwork.MAIN))
        assertTrue(hot.wallet.post(session))
        assertEquals(listOf(PostCall(KeyNetwork.MAIN, "hex-1", TXID)), hot.posts)
    }

    // More than twice what was shown, and then either more than a tenth of what the destination gets or a thousand sats over.
    @Test
    fun theFeeQuestionNeedsOverTwiceTheEstimateAndThenATenthOfTheAmountOrAThousandSatsOver() {
        // 1 sat/vB shows 141. Four coins and change are 345 sat: over twice, but 3.45% of 10,000, so no question.
        assertFalse(feeNeedsConfirmation(345, 141, 10_000), "the ordinary four-coin payment")
        assertFalse(feeNeedsConfirmation(1_000, 141, 10_000), "exactly a tenth")
        assertTrue(feeNeedsConfirmation(1_001, 141, 10_000), "just over a tenth")
        assertFalse(feeNeedsConfirmation(282, 141, 1_000), "exactly twice, and a large share of a small payment")
        assertTrue(feeNeedsConfirmation(283, 141, 1_000), "just over twice, and a large share")
        // 8 sat/vB shows 1,128. 2,760 is four coins and change.
        assertTrue(feeNeedsConfirmation(2_760, 1_128, 100_000), "2.76% of the amount, but 1,632 sat over the estimate")
        assertTrue(feeNeedsConfirmation(2_760, 1_128, 10_000), "27.6% of the amount")
        assertFalse(feeNeedsConfirmation(2_256, 1_128, 1_000), "exactly twice")
        assertFalse(feeNeedsConfirmation(1_128, 1_128, 546), "what was shown, on a dust-limit amount")
        // Send all: the amount is what leaves after the fee.
        assertTrue(feeNeedsConfirmation(314, 141, 2_000), "send all of 2,314 sat over four coins")
        assertFalse(feeNeedsConfirmation(314, 141, 9_686), "send all of 10,000 sat over four coins")
    }

    @Test
    fun theFeeTiersComeFromRustAndFallBackToTheBoardWhenTheBridgeFails() {
        var answer: () -> FeeTiers = { FeeTiers(1uL, 1uL, 2uL, true) }
        val wallet = SingleSigWallet(
            prepareAtRate = { _, _, _, _, _, _, _ -> fail("prepare ran") },
            postHex = { _, _, _ -> fail("post ran") },
            snapshot = { _, _, _, _ -> fail("snapshot ran") },
            fetchHistory = { _, _, _, _ -> fail("history ran") },
            fetchFeeTiers = { answer() },
        )
        assertEquals(LiveFeeTiers(1, 1, 2, live = true), wallet.feeTiers())
        answer = { FeeTiers(4uL, 8uL, 15uL, false) }
        assertEquals(LiveFeeTiers(4, 8, 15, live = false), wallet.feeTiers())
        answer = { throw IllegalStateException("the bridge failed") }
        assertEquals(LiveFeeTiers.DEFAULT, wallet.feeTiers())
    }

    // V1 of the 2026-11-05 interrogate. The card asked only when the fee was also over a tenth of the amount, so a fee hundreds of
    // times the estimate on a large payment was posted with no question. Fast shows 15 x 141 = 2,115; the core charged these for
    // 1,000 dust coins, or 300 coins of 10,000, listed before 1 BTC.
    @Test
    fun aFeeAThousandSatsOrMoreAboveTheEstimateIsQuestionedHoweverLargeThePayment() {
        assertTrue(feeNeedsConfirmation(1_022_115, 2_115, 50_000_000), "483x the estimate on 0.5 BTC")
        assertTrue(feeNeedsConfirmation(1_021_650, 2_115, 99_524_350), "send all, 483x")
        assertTrue(feeNeedsConfirmation(308_115, 2_115, 50_000_000), "145x")
        assertTrue(feeNeedsConfirmation(307_650, 2_115, 102_692_350), "send all, 145x")
        assertTrue(feeNeedsConfirmation(164_328, 1_128, 4_835_672), "145x at 8 sat/vB")
        assertTrue(feeNeedsConfirmation(2_082, 141, 117_918), "send all over 30 coins at 1 sat/vB")
        assertTrue(feeNeedsConfirmation(5_377, 141, 150_000), "100 dust coins listed first at 1 sat/vB")
        assertTrue(feeNeedsConfirmation(1_141, 141, 50_000_000), "exactly 1,000 sat over")
        assertFalse(feeNeedsConfirmation(1_140, 141, 50_000_000), "999 sat over, a small share")
        assertFalse(feeNeedsConfirmation(345, 141, 10_000), "the ordinary four-coin payment, 204 sat over")
        assertFalse(feeNeedsConfirmation(4_230, 2_115, 50_000_000), "2,115 sat over but exactly twice")
        assertTrue(feeNeedsConfirmation(4_231, 2_115, 50_000_000), "just over twice")
    }

    // V6. A post while another payment's guard was set posted anyway and erased that guard. A bind after a restart brings in a guard
    // while a payment is held.
    @Test
    fun aPostWhileAnotherPaymentsGuardIsSetRefusesPostsNothingAndKeepsThatGuard() {
        val hot = FakeHot()
        val session = hot.goLive()
        hot.prepareResult = { PreparedSend(TXID, "hex-held", 2_760uL, 10_000uL) }
        assertTrue(hot.wallet.prepare(session, 10_000, 8, DESTINATION))
        val other = UnconfirmedSend("f".repeat(64), SENT_AT)
        assertEquals(Claim.Written, session.recordUnconfirmedSend(other.txid, other.sinceSeconds))
        hot.wallet.bindSession(session)
        assertEquals(other, hot.wallet.unconfirmedSend, "the guard the bind brought in")
        assertFalse(hot.wallet.post(session), "a post while a guard is set")
        assertEquals(EARLIER_UNSETTLED, hot.wallet.lastError)
        assertEquals(emptyList<PostCall>(), hot.posts, "a post reached the node")
        assertEquals(other, hot.wallet.unconfirmedSend, "the other payment's guard on the wallet")
        assertEquals(other, restart(hot.store, hot.guards).unconfirmedSend(), "the other payment's guard on the phone")
    }

    // V7. A prepare that finished after dropHeldSend had run held its payment for the next Review. The screen is left while the
    // core signs, so the drop runs before prepare returns.
    @Test
    fun aPaymentDroppedWhileTheCoreWasStillSigningItStaysDropped() {
        val hot = FakeHot()
        val session = hot.goLive()
        hot.prepareResult = { rate ->
            hot.wallet.dropHeldSend()
            PreparedSend(TXID, "hex-late", rate * 345uL, 10_000uL)
        }
        assertFalse(hot.wallet.prepare(session, 10_000, 1, DESTINATION), "a prepare whose payment was let go reads as out")
        assertNull(hot.wallet.heldSend, "a payment held after leaving the screen")
        assertNull(hot.wallet.lastError, "a drop is not a failure")
        assertFalse(hot.wallet.post(session))
        assertEquals(emptyList<PostCall>(), hot.posts)
        hot.prepareResult = { rate ->
            hot.wallet.useDemoFixture()
            PreparedSend(TXID, "hex-late", rate * 345uL, 10_000uL)
        }
        assertFalse(hot.wallet.prepare(session, 10_000, 1, DESTINATION), "a prepare the demo fixture overtook")
        assertNull(hot.wallet.heldSend, "a payment held after the demo fixture")
        hot.wallet.bindSession(session)
        assertTrue(hot.wallet.live)
        hot.prepareResult = { PreparedSend(TXID, "hex-next", 2_760uL, 10_000uL) }
        assertTrue(hot.wallet.prepare(session, 10_000, 8, DESTINATION), "a prepare nobody dropped")
        assertEquals("hex-next", hot.wallet.heldSend?.prepared?.hex)
    }

    // V1. The whole Review decision for send all: the fee rule, and the amount that leaves against the one Review showed, because
    // the amount absorbs the fee and the spendable balance can have moved since the snapshot.
    @Test
    fun reviewAsksWhenSendAllWouldLeaveAThousandSatsLessThanItShowed() {
        val draft = SendDraft()
        AmountEntry(draft).sendAll(1_000_000)
        assertEquals(998_872L, draft.amountSats, "what Review shows at the normal tier")
        assertNull(feeQuestion(PreparedSend(TXID, "h", 1_128uL, 998_872uL), draft), "exactly what was shown")
        assertNull(feeQuestion(PreparedSend(TXID, "h", 1_128uL, 997_873uL), draft), "999 sat under")
        assertEquals(listOf(FeeReason.SendAllBelowShown), feeQuestion(PreparedSend(TXID, "h", 1_128uL, 997_872uL), draft)?.reasons, "1,000 sat under")
        assertEquals(listOf(FeeReason.SendAllBelowShown), feeQuestion(PreparedSend(TXID, "h", 1_021_650uL, 99_524_350uL), draft)?.reasons, "the fee rule through the same door")
        // A fixed amount is paid as asked, so only the fee rules speak.
        val fixed = SendDraft()
        fixed.setAmountInSats(100_000)
        assertNull(feeQuestion(PreparedSend(TXID, "h", 1_128uL, 99_000uL), fixed))
        assertEquals(listOf(FeeReason.AboveEstimate), feeQuestion(PreparedSend(TXID, "h", 2_760uL, 100_000uL), fixed)?.reasons)
    }

    // Item 85. The core used to refuse a fixed amount whose fee was more than the amount, with a generic banner. It signs it now, the fee
    // being paid on top, and Review asks once on the same card. Each row is the draft Review showed and the payment the core signed.
    @Test
    fun aFeeAboveTheAmountAsksOnceInsteadOfBeingRefused() {
        fun fixed(amountSats: Long, ratesSatVb: Long = 8) = SendDraft().apply {
            takeTiers(LiveFeeTiers(ratesSatVb, ratesSatVb, ratesSatVb, live = true))
            setAmountInSats(amountSats)
        }
        assertEquals(listOf(FeeReason.AboveAmount), feeQuestion(PreparedSend(TXID, "h", 14_241uL, 1_000uL), fixed(1_000, 101))?.reasons, "1,000 sat at 101 sat/vB, the iPhone payment")
        assertNull(feeQuestion(PreparedSend(TXID, "h", 1_128uL, 1_128uL), fixed(1_128)), "a fee equal to the amount")
        assertEquals(listOf(FeeReason.AboveAmount), feeQuestion(PreparedSend(TXID, "h", 1_128uL, 1_127uL), fixed(1_127))?.reasons, "a fee one sat over the amount")
        assertEquals(listOf(FeeReason.AboveAmount), feeQuestion(PreparedSend(TXID, "h", 1_128uL, 546uL), fixed(546))?.reasons, "a dust-limit amount, where the estimate rule is quiet")
        assertEquals(listOf(FeeReason.AboveAmount, FeeReason.AboveEstimate), feeQuestion(PreparedSend(TXID, "h", 2_760uL, 1_000uL), fixed(1_000))?.reasons, "four coins on a small amount, one card")
        assertEquals(listOf(FeeReason.AboveEstimate), feeQuestion(PreparedSend(TXID, "h", 2_760uL, 10_000uL), fixed(10_000))?.reasons, "four coins, the fee still under the amount")
    }

    // Send all takes the fee out of the balance and the core never refused it for the fee, so a fee above what leaves asks nothing new.
    @Test
    fun sendAllNeverAsksForAFeeAboveWhatLeaves() {
        val draft = SendDraft()
        draft.takeTiers(LiveFeeTiers(20, 20, 20, live = true))
        AmountEntry(draft).sendAll(4_000)
        assertEquals(1_180L, draft.amountSats, "what Review shows at 20 sat/vB over the typical size")
        assertNull(feeQuestion(PreparedSend(TXID, "h", 2_200uL, 1_800uL), draft), "one coin and one output, the fee above what leaves")
    }

    // The card is worded by its reasons. Parts are asserted, not whole strings, so design review can edit the words.
    @Test
    fun eachFeeQuestionIsTitledByItsReasonAndNamesManyCoinsOnlyWhenTheFeeWasFarAboveWhatWasShown() {
        fun card(vararg reasons: FeeReason) = FeeQuestion(reasons.toList(), rateSatVb = 8, dustChangeSats = 0, sendAll = FeeReason.SendAllBelowShown in reasons)
        val aboveAmount = card(FeeReason.AboveAmount)
        val both = card(FeeReason.AboveAmount, FeeReason.AboveEstimate)
        for (q in listOf(aboveAmount, both)) assertTrue("จำนวนที่ส่ง" in q.title, "$q is titled by the fee against the amount")
        for (q in listOf(card(FeeReason.AboveEstimate), card(FeeReason.SendAllBelowShown))) assertFalse("จำนวนที่ส่ง" in q.title, "$q is titled by the fee against the estimate")
        for (q in listOf(card(FeeReason.AboveEstimate), card(FeeReason.SendAllBelowShown), both)) assertTrue("หลายเหรียญ" in q.message, "$q names the coins")
        assertFalse("หลายเหรียญ" in aboveAmount.message, "a one-coin payment pays the fee Review showed")
        assertEquals("ส่งจริง", card(FeeReason.SendAllBelowShown).amountLabel)
        assertEquals("ส่ง", both.amountLabel)
    }

    // Item 86. Change below the change address's dust floor, which both testnet4 hosts refuse as an output, goes into the fee. The
    // core used to refuse the payment, and Review showed the generic failure banner. Review asks once and names the sats.
    @Test
    fun changeInTheFeeAsksAndNamesTheSats() {
        val draft = SendDraft().apply {
            takeTiers(LiveFeeTiers(1, 1, 1, live = true))
            setAmountInSats(9_566)
        }
        val question = feeQuestion(PreparedSend(TXID, "h", 434uL, 9_566uL, 293uL), draft)
        assertEquals(listOf(FeeReason.ChangeToFee), question?.reasons)
        assertEquals("เงินทอนรวมเข้าค่าธรรมเนียม", question?.title)
        assertTrue("293 sat" in question?.message.orEmpty(), "the line names the sats: ${question?.message}")
        assertNull(feeQuestion(PreparedSend(TXID, "h", 141uL, 9_565uL, 0uL), draft), "change at the floor is ordinary change")
    }

    // A fold on a small payment makes the fee three times the estimate. Judged with the change in it, the estimate rule would say the
    // wallet combined several coins, which is false, so it judges the fee without the change.
    @Test
    fun dustIsNotReadAsSeveralCoins() {
        val draft = SendDraft().apply {
            takeTiers(LiveFeeTiers(1, 1, 1, live = true))
            setAmountInSats(1_000)
        }
        val question = feeQuestion(PreparedSend(TXID, "h", 434uL, 1_000uL, 293uL), draft)
        assertEquals(listOf(FeeReason.ChangeToFee), question?.reasons)
        assertFalse("หลายเหรียญ" in question?.message.orEmpty())
    }

    // Each line is true alone, so the card lists every reason that holds, titled by the one with the most money at stake.
    @Test
    fun reasonsCombineOnOneCardTitledByTheFirst() {
        val draft = SendDraft().apply {
            takeTiers(LiveFeeTiers(1, 1, 1, live = true))
            setAmountInSats(1_000)
        }
        val question = feeQuestion(PreparedSend(TXID, "h", 1_200uL, 1_000uL, 200uL), draft)
        assertEquals(listOf(FeeReason.AboveAmount, FeeReason.AboveEstimate, FeeReason.ChangeToFee), question?.reasons)
        assertEquals("ค่าธรรมเนียมสูงกว่าจำนวนที่ส่ง", question?.title)
        assertEquals(1, Regex("ตรวจค่าธรรมเนียมก่อนส่ง").findAll(question?.message.orEmpty()).count(), "one closing line")
        // A tier above 2,000 sat/vB on a payment whose fee is above its amount, with change in the fee: the rate titles the card.
        val high = SendDraft().apply {
            takeTiers(LiveFeeTiers(2_001, 2_001, 2_001, live = true))
            setAmountInSats(100_000)
        }
        val all = feeQuestion(PreparedSend(TXID, "h", 282_341uL, 100_000uL, 200uL), high)
        assertEquals(listOf(FeeReason.HighRate, FeeReason.AboveAmount, FeeReason.ChangeToFee), all?.reasons)
        assertEquals("อัตราค่าธรรมเนียมสูงกว่า 2,000 sat/vB", all?.title)
        assertTrue("2,001 sat/vB" in all?.message.orEmpty(), "the line names the rate: ${all?.message}")
    }

    // Item 86. The core refused a rate above 2,000 sat/vB, which the network takes up to 10,000. It signs it now, and Review asks
    // once above 2,000. A tier of exactly 2,000 asks nothing.
    @Test
    fun aRateAbove2000AsksAndOneAtItDoesNot() {
        fun at(rateSatVb: Long) = SendDraft().apply {
            takeTiers(LiveFeeTiers(rateSatVb, rateSatVb, rateSatVb, live = true))
            setAmountInSats(1_000_000)
        }
        assertEquals(listOf(FeeReason.HighRate), feeQuestion(PreparedSend(TXID, "h", (2_001L * TYPICAL_VSIZE).toULong(), 1_000_000uL), at(2_001))?.reasons)
        assertNull(feeQuestion(PreparedSend(TXID, "h", (2_000L * TYPICAL_VSIZE).toULong(), 1_000_000uL), at(2_000)))
    }

    // A fee bump that folds its change, or one asked above 2,000 sat/vB, says so on its card. One that keeps its change at an ordinary
    // rate says nothing more than the fee.
    @Test
    fun replacementCardNamesTheChangeInTheFeeAndAHighRate() {
        fun held(dustSats: ULong, rateSatVb: Long = 15) = SingleSigWallet.HeldReplacement(
            PreparedSend(TXID, "h", 998uL, 9_002uL, dustSats),
            KeyNetwork.TEST,
            UnconfirmedSend(TXID, SENT_AT),
            Replacement.FeeBump,
            rateSatVb,
        )
        val folded = replacementQuestion(held(293uL))
        assertEquals(listOf(FeeReason.ChangeToFee), folded?.reasons)
        assertTrue("293 sat" in folded?.message.orEmpty())
        assertNull(replacementQuestion(held(0uL)))
        assertEquals(listOf(FeeReason.HighRate), replacementQuestion(held(0uL, rateSatVb = 2_001))?.reasons)
        assertNull(replacementQuestion(held(0uL, rateSatVb = 2_000)))
    }

    // A fee bump of a payment with no change that no confirmed coin could keep takes the new fee from the amount, and its card says by
    // how much. One that kept the amount says nothing about it.
    @Test
    fun aFeeBumpCardSaysHowMuchLessTheRecipientGetsOnlyWhenTheAmountWasCut() {
        fun held(cutSats: ULong) = SingleSigWallet.HeldReplacement(
            PreparedSend(TXID, "h", 550uL, 9_450uL, 0uL, cutSats),
            KeyNetwork.TEST,
            UnconfirmedSend(TXID, SENT_AT),
            Replacement.FeeBump,
            15,
        )
        assertTrue("น้อยกว่ารายการเดิม 1,440 sat" in amountCutLine(held(1_440uL)).orEmpty())
        assertNull(amountCutLine(held(0uL)))
    }

    // V4. The Send screen quoted send all and the available line from the balance with the mempool in it, so a send all posted a
    // tenth of what Review showed. The wallet now carries the spendable figure beside the balance.
    @Test
    fun theSpendableBalanceComesFromTheSnapshotBesideTheBalanceThatKeepsTheMempool() {
        val hot = FakeHot()
        hot.snapshotResult = { HotWalletSnapshot("tb1qbefore", 10_000_000uL, 1_000_000uL, KeyNetwork.TEST, HotScan.COMPLETE) }
        val session = hot.goLive()
        assertEquals(10_000_000L, hot.wallet.balanceSats, "Home's figure")
        assertEquals(1_000_000L, hot.wallet.spendableSats, "the Send screen's figure")
        val draft = SendDraft()
        AmountEntry(draft).sendAll(hot.wallet.spendableSats)
        assertEquals(1_000_000L - draft.feeSats, draft.amountSats, "send all from the spendable figure")
        assertEquals(SendIssue.Insufficient(1_128), sendIssue(true, 1_000_000, 1_128, hot.wallet.spendableSats), "the whole spendable figure plus the fee")
        hot.snapshotResult = { throw IllegalStateException("esplora unreachable") }
        hot.wallet.bindSession(session)
        assertEquals(0L, hot.wallet.spendableSats, "unknown, like the balance")
        hot.wallet.useDemoFixture()
        assertEquals(1_250_000L, hot.wallet.spendableSats, "the demo's")
    }

    // N1 of open item 79. The guard is keyed by the wallet's fingerprint: one wallet's payment of unknown outcome stops that wallet in
    // every live instance, one that bound before the guard was written included, and never stops another wallet.
    @Test
    fun aGuardStopsASecondPaymentFromItsWalletButNotFromAnotherWallet() {
        val first = FakeHot()
        val firstSession = first.goLive()
        val second = FakeHot(first.guards)
        val secondSession = restart(first.store, first.guards)
        second.wallet.bindSession(secondSession)
        first.postResult = { throw MobileSignerException.BroadcastUnknown(UNKNOWN_MESSAGE) }
        assertFalse(first.wallet.broadcast(firstSession, 100_000, 8, DESTINATION))
        assertFalse(second.wallet.broadcast(secondSession, 50_000, 8, DESTINATION), "a second payment from the same wallet")
        assertEquals(EARLIER_UNSETTLED, second.wallet.lastError)
        assertEquals(emptyList<PostCall>(), second.posts, "the second instance posted")
        assertEquals(UnconfirmedSend(TXID, SENT_AT), second.wallet.unconfirmedSend, "the second instance after its refusal")
        val other = FakeHot(first.guards, OTHER_CHECK)
        val otherSession = other.goLive()
        assertTrue(other.wallet.broadcast(otherSession, 50_000, 8, DESTINATION), "a payment from another wallet")
        assertEquals(1, other.posts.size)
        assertEquals(UnconfirmedSend(TXID, SENT_AT), first.guards.guardOf(TEST_CHECK), "the first wallet's guard after the other wallet's payment")
        assertNull(first.guards.guardOf(OTHER_CHECK), "the other wallet's guard once its payment is out")
    }

    // N1. A phone with no screen lock keeps the wallet in memory only, but it keeps the guard: after the process ends and the user types
    // the words again, the payment of unknown outcome still stops a second one.
    @Test
    fun aPhoneWithNoScreenLockKeepsTheGuardAcrossARestartAndANewImport() {
        val noLock = FakeSecureStore(write = WriteResult.Unavailable(Why.NoScreenLock))
        val before = FakeHot()
        val first = importedSession(noLock, before.guards)
        assertEquals(SaveState.NotSaved, first.saved, "a phone with no screen lock keeps no wallet")
        before.wallet.bindSession(first)
        before.postResult = { throw MobileSignerException.BroadcastUnknown(UNKNOWN_MESSAGE) }
        assertFalse(before.wallet.broadcast(first, 100_000, 8, DESTINATION))
        assertEquals(1, before.posts.size, "the payment posted with its guard in the plain file")
        val after = FakeHot(before.guards)
        val second = importedSession(noLock, before.guards)
        after.wallet.bindSession(second)
        assertEquals(UnconfirmedSend(TXID, SENT_AT), after.wallet.unconfirmedSend, "the new wallet after the restart")
        assertFalse(after.wallet.broadcast(second, 100_000, 8, DESTINATION), "a payment after the restart")
        assertEquals(EARLIER_UNSETTLED, after.wallet.lastError)
        assertEquals(emptyList<PrepareCall>(), after.prepares, "a payment was prepared after the restart")
    }

    // N1. A record that a build before the guard file saved holds the guard (flag 8). The unlock moves it to the file and saves the record
    // without it, once, so it still stops a second payment, and a guard the user cleared never comes back from the record.
    @Test
    fun anOlderRecordsGuardMovesToTheFileOnceAtTheUnlock() {
        val store = storeHolding(oldRecordBytes(UnconfirmedSend(TXID, SENT_AT)))
        val guards = FakePlainFile()
        val session = restart(store, guards)
        assertEquals(UnconfirmedSend(TXID, SENT_AT), guards.guardOf(), "the guard file after the unlock")
        assertNull(assertIs<UnlockResult.Opened>(SavedWallet(store).openNow()).oldGuard, "the record on the phone after the unlock")
        val hot = FakeHot(guards)
        hot.wallet.bindSession(session)
        assertFalse(hot.wallet.broadcast(session, 50_000, 8, DESTINATION), "a payment while the moved guard is set")
        assertEquals(EARLIER_UNSETTLED, hot.wallet.lastError)
        hot.wallet.clearUnconfirmedSend(session, UnconfirmedSend(TXID, SENT_AT)) // the user checked the explorer
        assertNull(restart(store, guards).unconfirmedSend(), "the next cold start after the clear")
        assertNull(guards.guardOf(), "the guard file after that cold start")
        assertTrue(hot.wallet.broadcast(session, 50_000, 8, DESTINATION), "a new payment after the clear")
    }

    // N1. Rows 72 and 76 of the storage design: a Replace that met both Keychain accounts taken, or another instance's newer copy, failed
    // the guard's save and so refused the payment. The guard no longer goes through the secure store.
    @Test
    fun aSecureStoreThatCannotSaveNoLongerStopsAPayment() {
        val answers = listOf(
            "both Keychain accounts taken" to WriteResult.Unavailable(Why.Other),
            "another instance's newer copy" to WriteResult.Conflict,
            "a Keystore key not ready" to WriteResult.Unavailable(Why.TryLater),
        )
        for ((case, answer) in answers) {
            val hot = FakeHot()
            val session = hot.goLive()
            hot.store.write = answer
            var inTheFile: Unsettled? = null
            hot.postResult = {
                inTheFile = hot.guards.guardOf()
                HotSendResult(TXID)
            }
            assertTrue(hot.wallet.broadcast(session, 100_000, 8, DESTINATION), "a payment when the store answers $case")
            assertEquals(1, hot.posts.size, case)
            assertEquals(UnconfirmedSend(TXID, SENT_AT), inTheFile, "the guard file when the post ran, $case")
            assertEquals(SaveState.Saved, session.saved, "the wallet after the payment, $case")
        }
    }

    // N1. Two live instances of one wallet post at the same moment over one guard file. Each read of the file waits a moment for the
    // other instance's, so without the lock across "no guard for this wallet, then write mine" both would find none and both post.
    // The node answers only once both posts are past their guard. A payment that went out clears its guard, so without the hold the
    // first instance could post and settle before the second one claimed, and that second post would rightly go.
    @Test
    fun twoInstancesOfOneWalletThatPostAtOnceSendOnePayment(): Unit = runBlocking {
        val first = FakeHot()
        val firstSession = first.goLive()
        val second = FakeHot(first.guards)
        val secondSession = restart(first.store, first.guards)
        second.wallet.bindSession(secondSession)
        assertTrue(first.wallet.prepare(firstSession, 100_000, 8, DESTINATION))
        assertTrue(second.wallet.prepare(secondSession, 100_000, 8, DESTINATION))
        val readers = atomic(0)
        first.guards.onRead = {
            readers.incrementAndGet()
            runBlocking { withTimeoutOrNull(300) { while (readers.value < 2) delay(5) } }
        }
        val atTheNode = atomic(0)
        val returned = atomic(0)
        val node = {
            atTheNode.incrementAndGet()
            runBlocking { withTimeoutOrNull(10_000) { while (atTheNode.value + returned.value < 2) delay(5) } }
            HotSendResult(TXID)
        }
        first.postResult = node
        second.postResult = node
        val firstPost = async(Dispatchers.Default) { first.wallet.post(firstSession).also { returned.incrementAndGet() } }
        val secondPost = async(Dispatchers.Default) { second.wallet.post(secondSession).also { returned.incrementAndGet() } }
        val sent = listOf(firstPost.await(), secondPost.await())
        assertEquals(1, first.posts.size + second.posts.size, "payments that reached the node")
        assertEquals(1, sent.count { it }, "posts that reported a payment out")
        assertEquals(EARLIER_UNSETTLED, listOf(first, second).single { it.posts.isEmpty() }.wallet.lastError)
    }

    // N1c. A damaged guard file read as no guard at the bind, so Send let a payment start, and its claim then failed as a plain failure
    // with no reason and no way out. It is a payment of unknown outcome now, offered to the user's clear at once, and only that clear
    // deletes it.
    @Test
    fun aDamagedGuardFileIsAnUnsettledPaymentUntilTheUserClearsItAndThenAPaymentPostsWithAFreshGuard() {
        val hot = FakeHot()
        hot.guards.text = "garbage\n"
        val session = hot.goLive()
        assertEquals(DamagedGuard, hot.wallet.unconfirmedSend, "what the bind took from the damaged file")
        assertTrue(hot.wallet.unconfirmedClearOffered(), "the clear is offered at once, with no post time to wait on")
        assertFalse(hot.wallet.broadcast(session, 100_000, 8, DESTINATION))
        assertEquals(EARLIER_UNSETTLED, hot.wallet.lastError)
        assertEquals(emptyList<PrepareCall>(), hot.prepares, "a payment was signed while the guard file was damaged")
        assertEquals(emptyList<PostCall>(), hot.posts)
        hot.wallet.bindSession(session)
        assertEquals(DamagedGuard, hot.wallet.unconfirmedSend, "a later bind")
        assertEquals("garbage\n", hot.guards.text, "the damaged file before the user's clear")
        assertTrue(hot.wallet.clearUnconfirmedSend(session, DamagedGuard), "the user's clear")
        assertNull(hot.wallet.unconfirmedSend)
        assertNull(hot.guards.text, "the clear deleted the damaged file")
        var onThePhone: Unsettled? = null
        hot.postResult = {
            onThePhone = hot.guards.guardOf()
            HotSendResult(TXID)
        }
        assertTrue(hot.wallet.broadcast(session, 100_000, 8, DESTINATION), "a payment after the clear")
        assertEquals(UnconfirmedSend(TXID, SENT_AT), onThePhone, "the fresh guard on the phone when the post ran")
        assertEquals(1, hot.posts.size)
        assertNull(hot.guards.text, "the payment is out, so its guard is gone")
    }

    // N1c. One damaged file stops every wallet on the phone and one clear frees them all, and a wallet that clears the damaged guard
    // late never drops the guard another wallet wrote since.
    @Test
    fun aDamagedGuardFileStopsEveryWalletAndALateClearKeepsANewerGuard() {
        val guards = FakePlainFile().apply { text = "garbage\n" }
        val first = FakeHot(guards)
        val firstSession = first.goLive()
        val other = FakeHot(guards, OTHER_CHECK)
        val otherSession = other.goLive()
        assertEquals(DamagedGuard, first.wallet.unconfirmedSend)
        assertEquals(DamagedGuard, other.wallet.unconfirmedSend, "another wallet")
        assertFalse(other.wallet.broadcast(otherSession, 50_000, 8, DESTINATION), "a payment from another wallet")
        assertTrue(first.wallet.clearUnconfirmedSend(firstSession, DamagedGuard))
        first.postResult = { throw MobileSignerException.BroadcastUnknown(UNKNOWN_MESSAGE) }
        assertFalse(first.wallet.broadcast(firstSession, 100_000, 8, DESTINATION))
        assertEquals(UnconfirmedSend(TXID, SENT_AT), guards.guardOf(TEST_CHECK), "the first wallet's fresh guard")
        assertEquals(DamagedGuard, other.wallet.unconfirmedSend, "the other wallet still holds what its bind took")
        assertTrue(other.wallet.clearUnconfirmedSend(otherSession, DamagedGuard), "the other wallet's late clear")
        assertEquals(UnconfirmedSend(TXID, SENT_AT), guards.guardOf(TEST_CHECK), "the first wallet's guard after the late clear")
        assertTrue(other.wallet.broadcast(otherSession, 50_000, 8, DESTINATION), "the other wallet after its clear")
        assertEquals(1, other.posts.size)
        assertFalse(first.wallet.broadcast(firstSession, 50_000, 8, DESTINATION), "the first wallet's payment may be out")
        assertEquals(1, first.posts.size)
    }

    // N1c, the review of 2026-11-05. An older record's payment P1 met a file that already held another payment P2 of the wallet. The move
    // answered Taken and dropped P1 from the session and the record, so once P2 settled nothing guarded P1.
    @Test
    fun anOlderRecordsPaymentAndTheFilesOtherPaymentMustBothSettleBeforeAPaymentPosts() {
        val p1 = UnconfirmedSend(TEST_TXID, SENT_AT)
        val p2 = UnconfirmedSend("f".repeat(64), SENT_AT + 60)
        val store = storeHolding(oldRecordBytes(p1))
        val guards = FakePlainFile()
        assertEquals(Claim.Written, PendingPayments(guards).claim(TEST_CHECK, p2), "another payment of the wallet in the file")
        val session = restart(store, guards)
        assertEquals(p1, assertIs<UnlockResult.Opened>(SavedWallet(store).openNow()).oldGuard, "the record keeps P1 while the file holds P2")
        val hot = FakeHot(guards)
        hot.wallet.bindSession(session)
        assertEquals(p1, hot.wallet.unconfirmedSend, "the record's payment is shown")
        assertFalse(hot.wallet.broadcast(session, 50_000, 8, DESTINATION), "a payment while P1 may be out")
        assertTrue(hot.wallet.clearUnconfirmedSend(session, p1), "the user's clear of P1")
        assertNull(assertIs<UnlockResult.Opened>(SavedWallet(store).openNow()).oldGuard, "the record after P1's clear")
        assertEquals(p2, guards.guardOf(), "P2 after P1's clear")
        assertFalse(hot.wallet.broadcast(session, 50_000, 8, DESTINATION), "a payment while P2 may be out")
        assertEquals(p2, hot.wallet.unconfirmedSend, "P2 is shown once the claim met it")
        assertEquals(emptyList<PostCall>(), hot.posts, "a post before both settled")
        assertTrue(hot.wallet.clearUnconfirmedSend(session, p2), "the user's clear of P2")
        assertTrue(hot.wallet.broadcast(session, 50_000, 8, DESTINATION), "a payment once both settled")
        assertEquals(1, hot.posts.size)
        assertNull(restart(store, guards).unconfirmedSend(), "the next cold start")
    }

    // N1c, the review of 2026-11-05. The record's copy of a moved payment stayed when its save failed, the user's clear then emptied the
    // file, and the next unlock moved the cleared payment back. A clear now drops the record's copy first, or drops nothing.
    @Test
    fun aClearThatCannotDropTheRecordsCopyKeepsThePaymentEverywhere() {
        val pending = UnconfirmedSend(TEST_TXID, SENT_AT)
        val store = storeHolding(oldRecordBytes(pending))
        store.write = WriteResult.Unavailable(Why.TryLater)
        val guards = FakePlainFile()
        val session = restart(store, guards)
        assertEquals(pending, guards.guardOf(), "the move reached the file")
        val hot = FakeHot(guards)
        hot.wallet.bindSession(session)
        assertFalse(hot.wallet.clearUnconfirmedSend(session, pending), "a clear while the record cannot be saved")
        assertEquals(pending, hot.wallet.unconfirmedSend, "the wallet")
        assertEquals(pending, guards.guardOf(), "the file")
        assertEquals(pending, assertIs<UnlockResult.Opened>(SavedWallet(store).openNow()).oldGuard, "the record")
        assertFalse(hot.wallet.broadcast(session, 50_000, 8, DESTINATION))
        store.write = null
        assertTrue(hot.wallet.clearUnconfirmedSend(session, pending), "the clear once the record can be saved")
        assertNull(guards.guardOf(), "the file")
        assertNull(restart(store, guards).unconfirmedSend(), "the next cold start")
        assertTrue(hot.wallet.broadcast(session, 50_000, 8, DESTINATION), "a payment after the clear")
    }

    // R8 S2, the review of 2026-11-06. A clear the guard file could not take said it worked, so the screen cleared and the payment was
    // refused only after the owner check and the signing. The clear now fails, which shows its failed line, and the guard stays.
    @Test
    fun aClearTheGuardFileCannotTakeKeepsThePaymentEverywhere() {
        val hot = FakeHot()
        val session = hot.unknownSend()
        hot.guards.failDelete = true
        assertFalse(hot.wallet.clearUnconfirmedSend(session, UnconfirmedSend(TXID, SENT_AT)), "a clear whose delete failed")
        assertEquals(UnconfirmedSend(TXID, SENT_AT), hot.wallet.unconfirmedSend, "the wallet")
        assertEquals(UnconfirmedSend(TXID, SENT_AT), hot.guards.guardOf(), "the file")
        hot.guards.failDelete = false
        assertTrue(hot.wallet.clearUnconfirmedSend(session, UnconfirmedSend(TXID, SENT_AT)), "the clear once the file can take it")
        assertNull(hot.guards.guardOf(), "the file after the clear")
    }

    // C2. The first post writes the signed bytes into its guard's line before it posts, and only bytes that are its own transaction.
    // Bytes that are not, as the fakes' "hex-1", stay out of the file and the payment still goes, with nothing to repost.
    @Test
    fun theFirstPostKeepsItsSignedBytesWithTheGuardOnlyWhenTheyAreItsTransaction() {
        val hot = FakeHot()
        hot.prepareResult = SIGNED_PAYMENT
        val session = hot.goLive()
        var inTheFile: String? = null
        hot.postResult = {
            inTheFile = hot.guards.text
            throw MobileSignerException.BroadcastUnknown(UNKNOWN_MESSAGE)
        }
        assertFalse(hot.wallet.broadcast(session, 100_000, 8, DESTINATION))
        assertEquals("$TEST_CHECK $SIGNED_TXID $SENT_AT $SIGNED_HEX\n", inTheFile, "the guard file when the post ran")
        assertEquals(SIGNED_GUARD, hot.wallet.unconfirmedSend)
        assertEquals(SIGNED_GUARD, restart(hot.store, hot.guards).unconfirmedSend(), "what a cold start restores")

        val plain = FakeHot()
        val plainSession = plain.goLive()
        plain.postResult = { throw MobileSignerException.BroadcastUnknown(UNKNOWN_MESSAGE) }
        assertFalse(plain.wallet.broadcast(plainSession, 100_000, 8, DESTINATION))
        assertEquals("$TEST_CHECK $TXID $SENT_AT\n", plain.guards.text, "bytes that are not the txid's transaction")
        assertEquals(listOf(PostCall(KeyNetwork.TEST, "hex-1", TXID)), plain.posts, "the payment still went to the post")
        assertEquals(UnconfirmedSend(TXID, SENT_AT), plain.wallet.unconfirmedSend)
    }

    // C2, decision 3. The node took the same bytes, or answered that it already holds them in its mempool or a block. The core reports
    // both as a sent txid (hot_wallet.rs, a_4xx_saying_the_node_holds_it_is_sent_with_no_lookup), so the post returns either way.
    @Test
    fun aRepostThatIsOutPostsTheSameBytesAndTakesTheGuardAndItsBytesOffThePhone() {
        val hot = FakeHot()
        val session = hot.unknownSignedSend()
        val other = UnconfirmedSend(OTHER_SIGNED_TXID, SENT_AT, OTHER_SIGNED_HEX)
        assertEquals(Claim.Written, PendingPayments(hot.guards).claim(OTHER_CHECK, other), "another wallet's guard with its bytes")
        assertEquals(Repost.Out, hot.wallet.repost(session))
        assertEquals(listOf(SIGNED_POST, SIGNED_POST), hot.posts, "the first post and the repost, the same bytes")
        assertNull(hot.wallet.unconfirmedSend, "the wallet")
        assertEquals("$OTHER_CHECK $OTHER_SIGNED_TXID $SENT_AT $OTHER_SIGNED_HEX\n", hot.guards.text, "the file keeps only the other wallet's line")
        assertNull(restart(hot.store, hot.guards).unconfirmedSend(), "a cold start")
        assertEquals(Repost.Failed, hot.wallet.repost(session), "a second tap once the guard is gone")
        assertEquals(2, hot.posts.size, "the second tap posted nothing")
        hot.prepareResult = { rate -> PreparedSend(TXID, "hex-1", rate * 141uL, 50_000uL) }
        assertTrue(hot.wallet.broadcast(session, 50_000, 8, DESTINATION), "a new payment once the repost was out")
    }

    // C2, decision 3. Unlike a first post, a refused repost proves nothing. The payment may have confirmed long ago, or another
    // transaction may have spent its coins. So every end but Out keeps the guard and its bytes, in memory and on the phone.
    @Test
    fun everyRepostThatIsNotOutKeepsTheGuardAndItsBytesEverywhere() {
        val answers: List<Pair<Repost, () -> HotSendResult>> = listOf(
            Repost.Unknown to { throw MobileSignerException.BroadcastUnknown(UNKNOWN_MESSAGE) },
            Repost.Refused to { throw MobileSignerException.InvalidPsbtJson("invalid PSBT json: esplora post: status code 400: sendrawtransaction RPC error -25: bad-txns-inputs-missingorspent") },
            Repost.Refused to { throw MobileSignerException.InvalidPsbtJson("esplora post: status code 400: sendrawtransaction RPC error -26: txn-mempool-conflict") },
            Repost.Failed to { throw MobileSignerException.InvalidPsbtJson("broadcast: txid $SIGNED_TXID does not match the transaction $OTHER_SIGNED_TXID") },
            Repost.Failed to { throw MobileSignerException.InvalidPsbtJson("mainnet Esplora is not enabled in this build") },
            Repost.Failed to { throw IllegalStateException("the answer could not be read") },
        )
        val hot = FakeHot()
        val session = hot.unknownSignedSend()
        answers.forEachIndexed { i, (outcome, answer) ->
            hot.postResult = answer
            assertEquals(outcome, hot.wallet.repost(session), "answer $i")
            assertEquals(SIGNED_GUARD, hot.wallet.unconfirmedSend, "the wallet after answer $i")
            assertEquals("$TEST_CHECK $SIGNED_TXID $SENT_AT $SIGNED_HEX\n", hot.guards.text, "the guard file after answer $i")
            assertEquals(SIGNED_GUARD, restart(hot.store, hot.guards).unconfirmedSend(), "a cold start after answer $i")
            assertFalse(hot.wallet.broadcast(session, 50_000, 8, DESTINATION), "a new payment after answer $i")
        }
        assertEquals(List(1 + answers.size) { SIGNED_POST }, hot.posts, "every repost posted the same bytes and no new payment went")
        assertEquals(1, hot.prepares.size, "no new payment was signed")
    }

    // C2, decision 1. The user's clear takes the signed bytes off the phone with the guard and leaves another wallet's line whole, so
    // no reader of app storage can post a payment the user gave up on.
    @Test
    fun theUsersClearTakesTheBytesOffThePhoneWithTheGuard() {
        val hot = FakeHot()
        val session = hot.unknownSignedSend()
        val other = UnconfirmedSend(OTHER_SIGNED_TXID, SENT_AT, OTHER_SIGNED_HEX)
        assertEquals(Claim.Written, PendingPayments(hot.guards).claim(OTHER_CHECK, other), "another wallet's guard with its bytes")
        assertTrue(hot.wallet.clearUnconfirmedSend(session, SIGNED_GUARD))
        assertEquals("$OTHER_CHECK $OTHER_SIGNED_TXID $SENT_AT $OTHER_SIGNED_HEX\n", hot.guards.text, "the file after the clear")
        assertNull(restart(hot.store, hot.guards).unconfirmedSend(), "a cold start")
        assertEquals(Repost.Failed, hot.wallet.repost(session), "a repost after the clear")
        assertEquals(listOf(SIGNED_POST), hot.posts, "nothing posted since the clear")
    }

    // C2, decision 1. A guard with no bytes, as an earlier build's line of three or the move from an older record leaves it, and a
    // damaged file get no repost. Nothing is posted and the guard stays for the user's clear.
    @Test
    fun aGuardWithoutBytesOrADamagedFileGetsNoRepost() {
        val lineOfThree = FakeHot(FakePlainFile().apply { text = "$TEST_CHECK $SIGNED_TXID $SENT_AT\n" })
        val lineOfThreeSession = lineOfThree.goLive()
        val moved = FakeHot()
        val movedSession = restart(storeHolding(oldRecordBytes(UnconfirmedSend(SIGNED_TXID, SENT_AT))), moved.guards)
        moved.wallet.bindSession(movedSession)
        val damaged = FakeHot(FakePlainFile().apply { text = "garbage\n" })
        val damagedSession = damaged.goLive()
        val cases = listOf(
            Triple("a line of three", lineOfThree, lineOfThreeSession),
            Triple("a guard moved from an older record", moved, movedSession),
            Triple("a damaged file", damaged, damagedSession),
        )
        for ((case, hot, session) in cases) {
            val guard = assertNotNull(hot.wallet.unconfirmedSend, case)
            assertNull((guard as? UnconfirmedSend)?.hex, "no bytes to offer, $case")
            val file = hot.guards.text
            assertEquals(Repost.Failed, hot.wallet.repost(session), case)
            assertEquals(emptyList<PostCall>(), hot.posts, case)
            assertEquals(guard, hot.wallet.unconfirmedSend, "the guard stays, $case")
            assertEquals(file, hot.guards.text, "the file, $case")
        }
    }

    // C2, decision 1. Bytes in the file that are not the line's own transaction make the file damaged, so they are never posted.
    @Test
    fun bytesThatAreNotTheLinesTransactionAreNeverPosted() {
        val hot = FakeHot(FakePlainFile().apply { text = "$TEST_CHECK $SIGNED_TXID $SENT_AT $OTHER_SIGNED_HEX\n" })
        val session = hot.goLive()
        val reposted = hot.wallet.repost(session)
        assertEquals(emptyList<PostCall>(), hot.posts, "what the repost posted")
        assertEquals(Repost.Failed, reposted)
        assertEquals(DamagedGuard, hot.wallet.unconfirmedSend, "what the bind took")
    }

    // C2, decision 4. A second instance cleared the payment and made another one whose outcome is unknown too. The first instance still
    // shows the old guard, and its repost posts neither the new payment's bytes nor the old ones.
    @Test
    fun anInstanceWhoseGuardTheFileNoLongerHoldsPostsNothing() {
        val first = FakeHot()
        val firstSession = first.unknownSignedSend()
        val second = FakeHot(first.guards)
        val secondSession = restart(first.store, first.guards)
        second.wallet.bindSession(secondSession)
        assertTrue(second.wallet.clearUnconfirmedSend(secondSession, SIGNED_GUARD), "the second instance's clear")
        second.prepareResult = { rate -> PreparedSend(OTHER_SIGNED_TXID, OTHER_SIGNED_HEX, rate * 141uL, 100_000uL) }
        second.postResult = { throw MobileSignerException.BroadcastUnknown(UNKNOWN_MESSAGE) }
        assertFalse(second.wallet.broadcast(secondSession, 100_000, 8, DESTINATION))
        assertEquals("$TEST_CHECK $OTHER_SIGNED_TXID $SENT_AT $OTHER_SIGNED_HEX\n", first.guards.text, "the new payment's guard")
        assertEquals(SIGNED_GUARD, first.wallet.unconfirmedSend, "the first instance still shows the old one")
        assertEquals(Repost.Failed, first.wallet.repost(firstSession))
        assertEquals(listOf(SIGNED_POST), first.posts, "the first instance posted only its first post")
    }

    // C2, decision 4. While a repost runs, a clear in another live instance of the wallet waits, so no payment can start while the
    // reposted bytes may still land. Once the repost ended, unknown again here, the clear goes through.
    @Test
    fun aClearInAnotherInstanceWaitsWhileARepostOfThatPaymentRuns() {
        val first = FakeHot()
        val firstSession = first.unknownSignedSend()
        val second = FakeHot(first.guards)
        val secondSession = restart(first.store, first.guards)
        second.wallet.bindSession(secondSession)
        first.postResult = {
            assertFalse(second.wallet.clearUnconfirmedSend(secondSession, SIGNED_GUARD), "the other instance's clear during the repost")
            assertFalse(second.wallet.broadcast(secondSession, 50_000, 8, DESTINATION), "a new payment from it during the repost")
            throw MobileSignerException.BroadcastUnknown(UNKNOWN_MESSAGE)
        }
        assertEquals(Repost.Unknown, first.wallet.repost(firstSession))
        assertEquals(SIGNED_GUARD, second.wallet.unconfirmedSend, "the other instance keeps the guard")
        assertEquals(emptyList<PostCall>(), second.posts)
        assertTrue(second.wallet.clearUnconfirmedSend(secondSession, SIGNED_GUARD), "its clear once the repost ended")
        assertNull(first.guards.text)
    }

    // C2, decision 4. Two live instances of one wallet repost at the same moment. Both read the one line, so both post the same bytes,
    // which are one transaction, and both end with the guard gone. The node answers only once both are inside the post.
    @Test
    fun twoInstancesThatRepostAtOncePostOnlyTheGuardsOwnBytes(): Unit = runBlocking {
        val first = FakeHot()
        val firstSession = first.unknownSignedSend()
        val second = FakeHot(first.guards)
        val secondSession = restart(first.store, first.guards)
        second.wallet.bindSession(secondSession)
        val inside = atomic(0)
        val most = atomic(0)
        val node = {
            val now = inside.incrementAndGet()
            most.update { maxOf(it, now) }
            runBlocking { withTimeoutOrNull(10_000) { while (most.value < 2) delay(5) } }
            inside.decrementAndGet()
            HotSendResult(SIGNED_TXID)
        }
        first.postResult = node
        second.postResult = node
        val firstRepost = async(Dispatchers.Default) { first.wallet.repost(firstSession) }
        val secondRepost = async(Dispatchers.Default) { second.wallet.repost(secondSession) }
        assertEquals(listOf(Repost.Out, Repost.Out), listOf(firstRepost.await(), secondRepost.await()))
        assertEquals(2, most.value, "both reposts were inside the post at once")
        assertEquals(listOf(SIGNED_POST, SIGNED_POST), first.posts, "the first post and the first instance's repost")
        assertEquals(listOf(SIGNED_POST), second.posts, "the second instance's repost")
        assertNull(first.wallet.unconfirmedSend, "the first instance")
        assertNull(second.wallet.unconfirmedSend, "the second instance")
        assertNull(first.guards.text, "the phone keeps no guard and no bytes")
    }
}

/** Another wallet's fingerprint, distinct from [TEST_CHECK]. */
private const val OTHER_CHECK = "9f8e7d6c"
