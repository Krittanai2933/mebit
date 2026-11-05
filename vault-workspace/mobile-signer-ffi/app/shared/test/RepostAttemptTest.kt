package com.mebit.app.feature.send

import com.mebit.app.BiometricResult
import com.mebit.app.Biometrics
import com.mebit.app.data.AppLockState
import com.mebit.app.data.FakePlainFile
import com.mebit.app.data.FakeSecureStore
import com.mebit.app.data.OnboardingSession
import com.mebit.app.data.OwnerCheck
import com.mebit.app.data.PUBLIC_WORDS_24
import com.mebit.app.data.PendingPayments
import com.mebit.app.data.Repost
import com.mebit.app.data.SIGNED_HEX
import com.mebit.app.data.SIGNED_TXID
import com.mebit.app.data.SavedWallet
import com.mebit.app.data.SendDraft
import com.mebit.app.data.SingleSigWallet
import com.mebit.app.data.SpendingPin
import com.mebit.app.data.TEST_CHECK
import com.mebit.app.data.sessionWithKey
import com.mebit.ffi.signer.HotScan
import com.mebit.ffi.signer.HotSendResult
import com.mebit.ffi.signer.HotWalletSnapshot
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.MobileSignerException
import com.mebit.ffi.signer.PreparedSend
import com.mebit.ffi.signer.SoftwareAccountKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TestTimeSource

/**
 * A repost from Send or Review (C2). The owner check that signing asks comes first, so a stranger holding the phone cannot push out
 * a payment the owner may have given up on, and one tap posts once.
 */
class RepostAttemptTest {
    @Test
    fun aRefusedOwnerCheckPostsNothing() = runBlocking {
        val attempt = RepostAttempt()
        var posts = 0
        attempt.run(TXID, confirm = { false }) {
            posts++
            Repost.Out
        }
        assertEquals(0, posts)
        assertNull(attempt.done)
        assertFalse(attempt.running)
    }

    @Test
    fun theOwnerIsAskedBeforeTheBytesArePostedAndTheEndIsKept() = runBlocking {
        val attempt = RepostAttempt()
        val events = mutableListOf<String>()
        attempt.run(TXID, confirm = { events += "asked"; true }) {
            events += "posted"
            Repost.Unknown
        }
        assertEquals(listOf("asked", "posted"), events)
        assertEquals(Reposted(TXID, Repost.Unknown), attempt.done)
        assertNull(attempt.outTxid, "a payment that is not out")
        attempt.run(TXID, confirm = { true }) { Repost.Out }
        assertEquals(TXID, attempt.outTxid)
    }

    @Test
    fun aSecondTapWhileTheFirstWaitsOnTheOwnerPostsNothing() = runBlocking {
        val attempt = RepostAttempt()
        val owner = CompletableDeferred<Boolean>()
        var asked = 0
        var posts = 0
        val confirm: suspend () -> Boolean = {
            asked++
            owner.await()
        }
        val first = launch {
            attempt.run(TXID, confirm) {
                posts++
                Repost.Out
            }
        }
        yield()
        assertTrue(attempt.running, "the first tap waits on the owner")
        attempt.run(TXID, confirm) {
            posts++
            Repost.Out
        }
        owner.complete(true)
        first.join()
        assertEquals(1, asked, "owner checks")
        assertEquals(1, posts, "posts")
    }

    @Test
    fun onAPhoneWithNoOwnerCheckTheSpendingPinGuardsARepostAsItGuardsSigning() = runBlocking {
        val clock = TestTimeSource()
        val session = sessionWithKey(SpendingPin(clock))
        assertTrue(session.pin.set("270419"))
        val owner = OwnerCheck(NoLock, session)
        val appLock = AppLockState(clock)
        val attempt = RepostAttempt()
        var posts = 0
        val confirm: suspend () -> Boolean = { appLock.confirm(owner, enabled = false, reason = "ยืนยันการส่งรายการเดิมอีกครั้ง") }
        val post = {
            posts++
            Repost.Out
        }
        val cancelled = launch { attempt.run(TXID, confirm, post) }
        yield()
        val sheet = assertNotNull(owner.asking, "the PIN sheet before the repost, with App Lock off")
        assertEquals("ยืนยันการส่งรายการเดิมอีกครั้ง", sheet.title)
        "583920".forEach(sheet::type)
        assertEquals(0, posts, "a wrong PIN posts nothing")
        sheet.cancel()
        cancelled.join()
        assertEquals(0, posts, "a closed sheet posts nothing")
        assertNull(attempt.done)
        val passed = launch { attempt.run(TXID, confirm, post) }
        yield()
        "270419".forEach(assertNotNull(owner.asking, "the sheet again")::type)
        passed.join()
        assertEquals(1, posts, "the right PIN lets the repost post")
        assertEquals(TXID, attempt.outTxid)
    }

    // R8 O1, the review of 2026-11-06. Leaving Send while its repost waited on a slow node cancelled the screen's scope. The post still
    // put the payment out and dropped the guard, but the draft stayed, so Send offered the paid payment again and Review signed it from
    // other coins, and the payee was paid twice.
    @Test
    fun leavingSendWhileARepostRunsLeavesNoDraftOfThePaidPayment() = runBlocking {
        val phone = LostAnswer()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        phone.answer = { txid ->
            entered.complete(Unit)
            runBlocking { release.await() }
            HotSendResult(txid)
        }
        val send = CoroutineScope(coroutineContext + Job())
        val repost = phone.startOnSend(send)
        withTimeout(10_000) { entered.await() } // a repost that never posts fails here instead of hanging
        send.cancel() // the user taps รับ, and Send leaves the composition
        release.complete(Unit) // the node takes the bytes
        repost.join()
        assertNull(phone.wallet.unconfirmedSend, "the repost put the payment out")
        assertEquals("", phone.draft.destination, "a repost that put the payment out must leave Send with no draft of it")
        assertEquals(0L, phone.draft.amountSats, "the amount on Send after the repost")
        assertEquals(listOf(SIGNED_TXID, SIGNED_TXID), phone.posts, "the first post and its same bytes, and nothing else")
    }

    // R8 O1. Send waits while a repost runs, as Review does, so its back chip and its รับ switch go nowhere until the repost ends.
    @Test
    fun leavingWaitsWhileARepostRuns() = runBlocking {
        val attempt = RepostAttempt()
        var left = 0
        val leave = attempt.unlessRunning { left++ }
        val node = CompletableDeferred<Unit>()
        val repost = launch {
            attempt.run(TXID, confirm = { true }) {
                runBlocking { node.await() }
                Repost.Out
            }
        }
        yield()
        val running = attempt.running
        leave()
        val leftWhileRunning = left
        // The node answers before any assertion, so a failing one cannot leave the post blocked and the test hanging.
        node.complete(Unit)
        repost.join()
        leave()
        assertTrue(running, "the repost waits on the node")
        assertEquals(0, leftWhileRunning, "a tap while the repost runs")
        assertEquals(1, left, "a tap once the repost ended")
    }

    // The control, the same repost while the user stays on Send. The draft goes, so the cancelled scope is what kept it above.
    @Test
    fun stayingOnSendWhileARepostRunsResetsTheDraft() = runBlocking {
        val phone = LostAnswer()
        phone.answer = { txid -> HotSendResult(txid) }
        phone.startOnSend(CoroutineScope(coroutineContext + Job())).join()
        assertNull(phone.wallet.unconfirmedSend, "the repost put the payment out")
        assertEquals("", phone.draft.destination)
    }

    /** A phone with no passcode and no screen lock, so the Spending PIN is the owner check. */
    private object NoLock : Biometrics {
        override fun available() = false

        override suspend fun authenticate(reason: String, allowPasscode: Boolean): BiometricResult = BiometricResult.Unavailable("no lock")
    }
}

private const val TXID = "c5ed075ef44b807f51123cf0c6e53c75385a17a510f995b560bf73b1102d5474"

/**
 * A live wallet with a draft of 100,000 sats to a payee, whose first post of [SIGNED_HEX] ended with its answer lost, so the guard
 * keeps the bytes and Send offers ส่งรายการเดิมอีกครั้ง. Every post lands in [posts], and the node then answers [answer].
 */
private class LostAnswer {
    val posts = mutableListOf<String>()
    var answer: (String) -> HotSendResult = { throw MobileSignerException.BroadcastUnknown("unknown") }
    private val session = OnboardingSession(
        SavedWallet(FakeSecureStore()),
        restoreKey = { w, _, _, _ -> SoftwareAccountKey(w, TEST_CHECK, "tpub-test", "m/48'/1'/0'/2'") },
        payments = PendingPayments(FakePlainFile()),
    ).apply { restoreFromWords(PUBLIC_WORDS_24) }
    val wallet = SingleSigWallet(
        prepareAtRate = { _, _, _, _, _, _ -> PreparedSend(SIGNED_TXID, SIGNED_HEX, 1_128uL, 100_000uL) },
        prepareAll = { _, _, _, _, _ -> error("not used") },
        postHex = { _, _, txid ->
            posts += txid
            answer(txid)
        },
        snapshot = { _, _, _ -> HotWalletSnapshot("tb1qreceive", 1_000_000uL, 1_000_000uL, KeyNetwork.TEST, HotScan.COMPLETE) },
        fetchHistory = { _, _, _ -> emptyList() },
        nowSeconds = { 1_792_740_720L },
        receiveAddressOf = { _, _, _, _ -> "tb1qoffline" },
    )
    val draft = SendDraft().apply {
        destination = "tb1qthepayee"
        setAmountInSats(100_000)
    }

    init {
        wallet.bindSession(session)
        assertFalse(wallet.prepare(session, draft.amountSats, draft.rateSatVb, draft.destination) && wallet.post(session), "the first post")
        assertEquals(SIGNED_HEX, (wallet.unconfirmedSend as? SingleSigWallet.UnconfirmedSend)?.hex, "the guard keeps the bytes")
    }

    /** Send's ส่งรายการเดิมอีกครั้ง, run in [scope] as the screen runs it, with an owner who passes. */
    fun startOnSend(scope: CoroutineScope): Job = RepostAttempt().start(scope, SIGNED_TXID, { true }, draft) { wallet.repost(session) }
}
