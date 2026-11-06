package com.mebit.app.data

import com.mebit.app.data.SingleSigWallet.DamagedGuard
import com.mebit.app.data.SingleSigWallet.UnconfirmedSend
import com.mebit.app.feature.send.RepostAttempt
import com.mebit.ffi.signer.HotScan
import com.mebit.ffi.signer.HotSendResult
import com.mebit.ffi.signer.HotTx
import com.mebit.ffi.signer.HotWalletSnapshot
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.MobileSignerException
import com.mebit.ffi.signer.PreparedSend
import com.mebit.ffi.signer.SoftwareAccountKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * C3 and option (a), a fee bump or a cancel of a payment of unknown outcome. Each spends every coin of the payment it replaces, so
 * one chain pays at most once. Its guard names the whole chain, because any payment of it may be the one that is out, and a chain
 * that holds a cancel waits for a block, because until then the payment it cancels may still confirm.
 */
class ReplacementTest {
    @Test
    fun aBumpIsSignedFromTheGuardsBytesWithNothingWrittenOrPostedUntilItsConfirm() {
        val phone = Guarded().firstPostUnknown()
        assertTrue(phone.wallet.prepareReplacement(phone.session, Replacement.FeeBump, 20))
        assertEquals(listOf(Replacement.FeeBump to SIGNED_HEX), phone.core, "what the core was asked to replace")
        val held = assertNotNull(phone.wallet.heldReplacement)
        assertEquals(Replacement.FeeBump, held.kind)
        assertEquals(FIRST, held.replaces)
        assertEquals(listOf(SIGNED_TXID), phone.posts, "nothing posted while it is held")
        assertEquals(FIRST, phone.guards.guardOf(), "nothing written while it is held")
        phone.wallet.dropHeldReplacement()
        assertNull(phone.wallet.heldReplacement)
        assertEquals(Repost.Failed, phone.wallet.postReplacement(phone.session), "a confirm after the drop")
        assertEquals(listOf(SIGNED_TXID), phone.posts)
    }

    // C3. Out is known, so the guard goes everywhere and the bump is the payment that went out, as after a first post.
    @Test
    fun aBumpThatIsOutDropsTheGuardEverywhereAndIsTheLastPayment() {
        val phone = Guarded().firstPostUnknown()
        assertTrue(phone.wallet.prepareReplacement(phone.session, Replacement.FeeBump, 20))
        var during: SingleSigWallet.Unsettled? = null
        phone.answer = { txid ->
            during = phone.guards.guardOf()
            HotSendResult(txid)
        }
        assertEquals(Repost.Out, phone.wallet.postReplacement(phone.session))
        assertEquals(BUMP_GUARD, during, "the bump's guard is on the phone before its post")
        assertEquals(listOf(SIGNED_TXID, BUMP.first), phone.posts)
        assertNull(phone.wallet.unconfirmedSend, "the wallet")
        assertNull(phone.guards.text, "the phone")
        assertEquals(BUMP.first, phone.wallet.lastTxid)
        assertEquals(20L * 141, phone.wallet.lastFeeSats)
        assertEquals(100_000L, phone.wallet.lastAmountSats)
        assertEquals(LATER, phone.wallet.lastSentAtSeconds)
        assertNull(phone.wallet.heldReplacement)
    }

    // C3. After a bump, any payment of the chain may be the one that is out. Unknown and Refused say nothing about which, so the bump's
    // guard stays everywhere, a new payment is refused, and a retry posts the bump's bytes.
    @Test
    fun aBumpThatIsUnknownOrRefusedKeepsItsGuardEverywhere() {
        val answers: List<Pair<Repost, (String) -> HotSendResult>> = listOf(
            Repost.Unknown to { _ -> throw MobileSignerException.BroadcastUnknown("unknown") },
            Repost.Refused to { _ -> throw MobileSignerException.InvalidPsbtJson("invalid PSBT json: esplora post: status code 400: insufficient fee, rejecting replacement") },
        )
        for ((outcome, answer) in answers) {
            val phone = Guarded().firstPostUnknown()
            assertTrue(phone.wallet.prepareReplacement(phone.session, Replacement.FeeBump, 20))
            phone.answer = answer
            assertEquals(outcome, phone.wallet.postReplacement(phone.session), "$outcome")
            assertEquals(BUMP_GUARD, phone.wallet.unconfirmedSend, "the wallet, $outcome")
            assertEquals(BUMP_GUARD, phone.guards.guardOf(), "the phone, $outcome")
            assertEquals("", phone.wallet.lastTxid, "no payment recorded, $outcome")
            assertFalse(phone.wallet.prepare(phone.session, 50_000, 8, PAYEE), "a new payment, $outcome")
            phone.answer = { HotSendResult(it) }
            assertEquals(Repost.Out, phone.wallet.repost(phone.session), "a retry, $outcome")
            assertEquals(listOf(SIGNED_TXID, BUMP.first, BUMP.first), phone.posts, "the retry posted the bump's bytes, $outcome")
            assertNull(phone.guards.text, "a chain with no cancel settles on its retry's out, $outcome")
        }
    }

    // C3. Every way a confirm posts nothing, which is no held bump, one that no longer replaces the wallet's guard, a file that names another
    // payment now, none or a damaged one (the wallet then takes what the file holds), and a file that cannot take the write.
    @Test
    fun aBumpThatCannotTakeTheGuardsPlacePostsNothing() {
        val idle = Guarded().firstPostUnknown()
        assertEquals(Repost.Failed, idle.wallet.postReplacement(idle.session), "no held bump")

        val cleared = Guarded().firstPostUnknown()
        assertTrue(cleared.wallet.prepareReplacement(cleared.session, Replacement.FeeBump, 20))
        assertTrue(cleared.wallet.clearUnconfirmedSend(cleared.session, FIRST))
        assertEquals(Repost.Failed, cleared.wallet.postReplacement(cleared.session), "a bump of a guard the wallet no longer holds")
        assertNull(cleared.guards.text, "nothing written")

        val another = UnconfirmedSend(OTHER_SIGNED_TXID, SENT_AT + 30, OTHER_SIGNED_HEX)
        val changes: List<Triple<String, (FakePlainFile) -> Unit, SingleSigWallet.Unsettled?>> = listOf(
            Triple("another payment's line", { file ->
                assertTrue(PendingPayments(file).settle(TEST_CHECK, FIRST))
                assertEquals(Claim.Written, PendingPayments(file).claim(TEST_CHECK, another))
            }, another),
            Triple("no line", { file -> assertTrue(PendingPayments(file).settle(TEST_CHECK, FIRST)) }, null),
            Triple("a damaged file", { file -> file.text = "garbage\n" }, DamagedGuard),
        )
        for ((case, change, now) in changes) {
            val phone = Guarded().firstPostUnknown()
            assertTrue(phone.wallet.prepareReplacement(phone.session, Replacement.FeeBump, 20), case)
            change(phone.guards)
            val text = phone.guards.text
            assertEquals(Repost.Failed, phone.wallet.postReplacement(phone.session), case)
            assertEquals(now, phone.wallet.unconfirmedSend, "the wallet takes what the file holds, $case")
            assertEquals(text, phone.guards.text, "nothing written, $case")
            assertEquals(listOf(SIGNED_TXID), phone.posts, "nothing posted, $case")
        }

        val full = Guarded().firstPostUnknown()
        assertTrue(full.wallet.prepareReplacement(full.session, Replacement.FeeBump, 20))
        full.guards.failWrite = true
        assertEquals(Repost.Failed, full.wallet.postReplacement(full.session), "a file that cannot take the write")
        assertEquals(FIRST, full.wallet.unconfirmedSend)
        assertEquals(FIRST, full.guards.guardOf())
        assertEquals(listOf(SIGNED_TXID), full.posts)
    }

    // C3. The bump may be refused and the first payment confirm. A history that lists only the first payment settles the chain.
    @Test
    fun aBindSettlesAChainWhoseHistoryListsOnlyAPaymentItReplaced() {
        val phone = Guarded().firstPostUnknown()
        assertTrue(phone.wallet.prepareReplacement(phone.session, Replacement.FeeBump, 20))
        phone.answer = { throw MobileSignerException.BroadcastUnknown("unknown") }
        assertEquals(Repost.Unknown, phone.wallet.postReplacement(phone.session))
        phone.history = listOf(HotTx(SIGNED_TXID, -101_128L, 0u, null))
        phone.wallet.bindSession(phone.session)
        assertNull(phone.wallet.unconfirmedSend, "the wallet")
        assertNull(phone.guards.text, "the phone")
    }

    // C3. A replacement needs the signed bytes of a guard the wallet holds. A line without them, a damaged file or no guard get none,
    // and a refusal of the core reads in Thai.
    @Test
    fun aReplacementIsSignedOnlyFromAGuardWithItsBytes() {
        val cases = listOf(
            "no guard" to Guarded(),
            "a guard without bytes" to Guarded(FakePlainFile().apply { text = "$TEST_CHECK $SIGNED_TXID $SENT_AT\n" }),
            "a damaged file" to Guarded(FakePlainFile().apply { text = "garbage\n" }),
        )
        for ((case, phone) in cases) for (kind in Replacement.entries) {
            assertFalse(phone.wallet.prepareReplacement(phone.session, kind, 20), "$kind, $case")
            assertNull(phone.wallet.heldReplacement, "$kind, $case")
        }
        assertEquals(emptyList<Pair<Replacement, String>>(), cases.flatMap { it.second.core }, "the core was never asked")
        val refused = Guarded().firstPostUnknown()
        refused.sign = { throw MobileSignerException.InvalidPsbtJson("invalid PSBT json: replace: coins spent: $SIGNED_TXID:1 is not a coin this wallet can spend") }
        assertFalse(refused.wallet.prepareReplacement(refused.session, Replacement.FeeBump, 20))
        assertEquals("เหรียญของรายการเดิมถูกใช้ไปแล้ว รายการเดิมอาจออกไปแล้ว ตรวจประวัติในหน้าแรกก่อน", refused.wallet.lastError)
        assertNull(refused.wallet.heldReplacement)
    }

    // The user left while the core was signing, so the replacement is let go, not held for a screen that is gone.
    @Test
    fun aReplacementDroppedWhileTheCoreWasSigningStaysDropped() {
        val phone = Guarded().firstPostUnknown()
        val signs = phone.sign
        phone.sign = { kind ->
            phone.wallet.dropHeldReplacement()
            signs(kind)
        }
        assertFalse(phone.wallet.prepareReplacement(phone.session, Replacement.FeeBump, 20))
        assertNull(phone.wallet.heldReplacement)
        assertNull(phone.wallet.lastError, "a drop is not a failure")
        assertEquals(Repost.Failed, phone.wallet.postReplacement(phone.session))
        assertEquals(listOf(SIGNED_TXID), phone.posts)
    }

    // C3. Two live instances of one wallet. The first bumps. The second's repost of the old bytes and its own stale bump post nothing,
    // and the second takes the bump's guard from the file.
    @Test
    fun anInstanceWhoseGuardAnotherBumpedPostsNothing() {
        val first = Guarded().firstPostUnknown()
        val second = Guarded(first.guards)
        assertEquals(FIRST, second.wallet.unconfirmedSend, "the second instance took the guard at its bind")
        assertTrue(second.wallet.prepareReplacement(second.session, Replacement.FeeBump, 30), "the second instance's bump")
        assertTrue(first.wallet.prepareReplacement(first.session, Replacement.FeeBump, 20))
        first.answer = { throw MobileSignerException.BroadcastUnknown("unknown") }
        assertEquals(Repost.Unknown, first.wallet.postReplacement(first.session))
        assertEquals(Repost.Failed, second.wallet.repost(second.session), "the second instance's repost of the old bytes")
        assertEquals(Repost.Failed, second.wallet.postReplacement(second.session), "the second instance's stale bump")
        assertEquals(emptyList<String>(), second.posts, "the second instance posted nothing")
        assertEquals(BUMP_GUARD, second.wallet.unconfirmedSend, "the second instance takes the bump's guard")
        assertEquals(BUMP_GUARD, first.guards.guardOf())
    }

    // C3. While a bump's guard is held, no new payment goes, from this instance or from another that signed one before.
    @Test
    fun aNewPaymentIsRefusedWhileABumpsGuardIsHeld() {
        val early = Guarded()
        assertTrue(early.wallet.prepare(early.session, 50_000, 8, PAYEE), "another instance signs a payment first")
        val phone = Guarded(early.guards).firstPostUnknown()
        assertTrue(phone.wallet.prepareReplacement(phone.session, Replacement.FeeBump, 20))
        phone.answer = { throw MobileSignerException.BroadcastUnknown("unknown") }
        assertEquals(Repost.Unknown, phone.wallet.postReplacement(phone.session))
        assertFalse(phone.wallet.prepare(phone.session, 50_000, 8, PAYEE), "a new payment from this instance")
        assertFalse(early.wallet.post(early.session), "the payment the other instance signed")
        assertEquals(emptyList<String>(), early.posts)
        assertEquals(BUMP_GUARD, early.wallet.unconfirmedSend, "it takes the bump's guard")
        assertEquals(BUMP_GUARD, phone.guards.guardOf())
    }

    // A3 and A2. A cancel is out once posted, but the payment it cancels may still confirm, so its guard stays everywhere and nothing
    // is recorded as paid. A retry that is out keeps it too.
    @Test
    fun aCancelThatIsOutKeepsItsGuardAndRecordsNoPayment() {
        val phone = Guarded().firstPostUnknown()
        assertTrue(phone.wallet.prepareReplacement(phone.session, Replacement.Cancel, 20))
        assertEquals(listOf(Replacement.Cancel to SIGNED_HEX), phone.core)
        phone.answer = { HotSendResult(it) }
        assertEquals(Repost.Out, phone.wallet.postReplacement(phone.session))
        assertEquals(CANCEL_GUARD, phone.wallet.unconfirmedSend, "the wallet")
        assertEquals(CANCEL_GUARD, phone.guards.guardOf(), "the phone")
        assertEquals(Repost.Out, phone.wallet.repost(phone.session), "a retry of the cancel")
        assertEquals(CANCEL_GUARD, phone.wallet.unconfirmedSend, "the wallet after the retry")
        assertEquals(CANCEL_GUARD, phone.guards.guardOf(), "the phone after the retry")
        assertEquals(listOf(SIGNED_TXID, CANCEL.first, CANCEL.first), phone.posts)
        assertEquals("", phone.wallet.lastTxid)
        assertEquals(0L, phone.wallet.lastFeeSats)
        assertEquals(0L, phone.wallet.lastAmountSats)
        assertEquals(0L, phone.wallet.lastSentAtSeconds)
        assertFalse(phone.wallet.prepare(phone.session, 50_000, 8, PAYEE), "a new payment")
    }

    // A2. Only a block settles a chain that holds a cancel. A listing with no confirmation of either transaction keeps the guard, and
    // one with a confirmation of either settles it.
    @Test
    fun aCancelsChainSettlesOnlyOnAListingWithABlock() {
        val listings = listOf(
            listOf(HotTx(CANCEL.first, 0L, 0u, null)) to false,
            listOf(HotTx(SIGNED_TXID, -101_128L, 0u, null)) to false,
            listOf(HotTx(CANCEL.first, 0L, 0u, null), HotTx(SIGNED_TXID, -101_128L, 0u, null)) to false,
            listOf(HotTx(CANCEL.first, 0L, 1u, 1_792_741_000uL)) to true,
            listOf(HotTx(SIGNED_TXID, -101_128L, 1u, 1_792_741_000uL)) to true,
        )
        for ((history, settles) in listings) {
            val phone = Guarded().firstPostUnknown()
            assertTrue(phone.wallet.prepareReplacement(phone.session, Replacement.Cancel, 20))
            phone.answer = { HotSendResult(it) }
            assertEquals(Repost.Out, phone.wallet.postReplacement(phone.session))
            phone.history = history
            phone.wallet.bindSession(phone.session)
            val case = history.joinToString { "${it.txid.take(6)} at ${it.confirmations}" }
            assertEquals(if (settles) null else CANCEL_GUARD, phone.wallet.unconfirmedSend, "the wallet, $case")
            assertEquals(if (settles) null else CANCEL_GUARD, phone.guards.guardOf(), "the phone, $case")
        }
    }

    // A2. A chain with no cancel still settles on a listing with no confirmation.
    @Test
    fun aBumpsChainSettlesOnAListingWithNoBlock() {
        val phone = Guarded().firstPostUnknown()
        assertTrue(phone.wallet.prepareReplacement(phone.session, Replacement.FeeBump, 20))
        phone.answer = { throw MobileSignerException.BroadcastUnknown("unknown") }
        assertEquals(Repost.Unknown, phone.wallet.postReplacement(phone.session))
        phone.history = listOf(HotTx(BUMP.first, -102_820L, 0u, null))
        phone.wallet.bindSession(phone.session)
        assertNull(phone.wallet.unconfirmedSend)
        assertNull(phone.guards.text)
    }

    // A3. A fee bump of a cancel would pay the destination the cancel took back, so a chain that holds a cancel signs only another
    // cancel, which keeps the mark.
    @Test
    fun aChainThatHoldsACancelIsBumpedOnlyByAnotherCancel() {
        val phone = Guarded().firstPostUnknown()
        assertTrue(phone.wallet.prepareReplacement(phone.session, Replacement.Cancel, 20))
        phone.answer = { throw MobileSignerException.InvalidPsbtJson("invalid PSBT json: esplora post: status code 400: insufficient fee") }
        assertEquals(Repost.Refused, phone.wallet.postReplacement(phone.session))
        assertEquals(CANCEL_GUARD, phone.wallet.unconfirmedSend)
        assertFalse(phone.wallet.prepareReplacement(phone.session, Replacement.FeeBump, 40), "a fee bump of the cancel")
        assertNull(phone.wallet.heldReplacement)
        assertEquals(listOf(Replacement.Cancel to SIGNED_HEX), phone.core, "the core was not asked for the bump")
        phone.sign = { PreparedSend(BUMP.first, BUMP.second, 40uL * 110uL, 95_000uL) }
        assertTrue(phone.wallet.prepareReplacement(phone.session, Replacement.Cancel, 40), "another cancel")
        assertEquals(Replacement.Cancel to CANCEL.second, phone.core.last(), "the cancel replaces the cancel's bytes")
        phone.answer = { throw MobileSignerException.BroadcastUnknown("unknown") }
        assertEquals(Repost.Unknown, phone.wallet.postReplacement(phone.session))
        val again = UnconfirmedSend(BUMP.first, LATER, BUMP.second, listOf(SIGNED_TXID, CANCEL.first), holdsCancel = true)
        assertEquals(again, phone.wallet.unconfirmedSend)
        assertEquals(again, phone.guards.guardOf())
    }

    // R9 H1. A bind's history speaks only for the guard held when it began to load. A fee bump posted during the load, its answer lost,
    // came after a history that lists the first payment, so the bind judges the first payment's guard and the bump's guard stays everywhere.
    @Test
    fun aBumpPostedWhileABindsHistoryLoadsKeepsItsGuard() {
        val phone = Guarded().firstPostUnknown()
        phone.history = listOf(HotTx(SIGNED_TXID, -101_128L, 0u, null))
        phone.onHistory = {
            phone.onHistory = {}
            assertEquals(Repost.Unknown, phone.bump())
        }
        phone.wallet.bindSession(phone.session)
        assertEquals(BUMP_GUARD, phone.wallet.unconfirmedSend, "the wallet")
        assertEquals(BUMP_GUARD, phone.guards.guardOf(), "the phone")
    }

    // The cancel of that race, out. The listing of the first payment with no block settles the first payment's guard, the one the bind
    // judged, and never the cancel's that took its place, because until a block holds one transaction of the chain the payment may confirm.
    @Test
    fun aCancelPostedWhileABindsHistoryLoadsKeepsItsGuard() {
        val phone = Guarded().firstPostUnknown()
        phone.history = listOf(HotTx(SIGNED_TXID, -101_128L, 0u, null))
        phone.answer = { HotSendResult(it) }
        phone.onHistory = {
            phone.onHistory = {}
            assertEquals(Repost.Out, phone.cancel())
        }
        phone.wallet.bindSession(phone.session)
        assertEquals(CANCEL_GUARD, phone.wallet.unconfirmedSend, "the wallet")
        assertEquals(CANCEL_GUARD, phone.guards.guardOf(), "the phone")
    }

    // R9 O1. Review offered the guard's controls while its own first post still ran, so a cancel could go out inside that post. The first
    // post, ending out, settles the guard it wrote and never the cancel's that took its place, so no new payment goes while the payment and
    // its cancel may both be out.
    @Test
    fun aCancelPostedWhileTheFirstPostRunsKeepsItsGuardWhenThatPostEndsOut() = cancelDuringTheFirstPost(Repost.Out)

    // The same with the cancel's answer lost, so the cancel may be out.
    @Test
    fun aCancelWhoseAnswerWasLostKeepsItsGuardWhenTheFirstPostEndsOut() = cancelDuringTheFirstPost(Repost.Unknown)

    private fun cancelDuringTheFirstPost(cancelled: Repost) {
        val phone = Guarded()
        var during: SingleSigWallet.Unsettled? = null
        phone.answer = { txid ->
            if (txid == SIGNED_TXID) {
                phone.answer = { if (cancelled == Repost.Out) HotSendResult(it) else throw MobileSignerException.BroadcastUnknown("unknown") }
                assertEquals(cancelled, phone.cancel(), "the cancel")
                during = phone.guards.guardOf()
            }
            HotSendResult(txid)
        }
        assertTrue(phone.wallet.prepare(phone.session, 100_000, 8, PAYEE))
        assertTrue(phone.wallet.post(phone.session), "the first post is out")
        assertEquals(CANCEL_GUARD, during, "the cancel's guard was on the phone before its post")
        assertEquals(CANCEL_GUARD, phone.wallet.unconfirmedSend, "the wallet")
        assertEquals(CANCEL_GUARD, phone.guards.guardOf(), "the phone")
        assertFalse(phone.wallet.prepare(phone.session, 50_000, 8, PAYEE) && phone.wallet.post(phone.session), "a new payment")
        assertEquals(listOf(SIGNED_TXID, CANCEL.first), phone.posts)
    }

    // R9 O1, the bump. Inside the first post a retry is refused, which offers the fee bump, and the bump's answer is lost, so it may be
    // out. The first post is then refused too. It settles the guard it wrote and never the bump's, so the payment is not signed again.
    @Test
    fun aBumpWhoseAnswerWasLostKeepsItsGuardWhenTheFirstPostIsRefused() {
        val phone = Guarded()
        phone.answer = { txid ->
            if (txid == SIGNED_TXID && phone.posts.size == 1) {
                assertEquals(Repost.Refused, phone.wallet.repost(phone.session), "a retry")
                assertEquals(Repost.Unknown, phone.bump(), "the bump the refusal offered")
            }
            if (txid == BUMP.first) throw MobileSignerException.BroadcastUnknown("unknown")
            throw MobileSignerException.InvalidPsbtJson("invalid PSBT json: esplora post: status code 400: mempool min fee not met")
        }
        assertTrue(phone.wallet.prepare(phone.session, 100_000, 8, PAYEE))
        assertFalse(phone.wallet.post(phone.session), "the first post is refused")
        assertEquals(BUMP_GUARD, phone.wallet.unconfirmedSend, "the wallet")
        assertEquals(BUMP_GUARD, phone.guards.guardOf(), "the phone")
        assertFalse(phone.wallet.prepare(phone.session, 100_000, 8, PAYEE) && phone.wallet.post(phone.session), "the payment signed again")
        assertEquals(listOf(SIGNED_TXID, SIGNED_TXID, BUMP.first), phone.posts)
    }

    // Every clear names its guard. A settle of one that a replacement took the place of drops nothing, here or on the phone.
    @Test
    fun aSettleOfAnEarlierGuardLeavesTheNewerGuardEverywhere() {
        val phone = Guarded().firstPostUnknown()
        assertEquals(Repost.Unknown, phone.bump())
        assertTrue(phone.wallet.clearUnconfirmedSend(phone.session, FIRST), "nothing of the first payment is left to drop")
        assertEquals(BUMP_GUARD, phone.wallet.unconfirmedSend, "the wallet")
        assertEquals(BUMP_GUARD, phone.guards.guardOf(), "the phone")
    }

    // A retry that ends out lets go of its own guard only. A bump posted while the retry's post ran took that guard's place, and stays.
    @Test
    fun aRetryThatEndsOutKeepsTheGuardOfABumpPostedDuringIt() {
        val phone = Guarded().firstPostUnknown()
        phone.answer = { txid ->
            if (txid == SIGNED_TXID) {
                phone.answer = { throw MobileSignerException.BroadcastUnknown("unknown") }
                assertEquals(Repost.Unknown, phone.bump())
            }
            HotSendResult(txid)
        }
        assertEquals(Repost.Out, phone.wallet.repost(phone.session))
        assertEquals(BUMP_GUARD, phone.wallet.unconfirmedSend, "the wallet")
        assertEquals(BUMP_GUARD, phone.guards.guardOf(), "the phone")
    }

    // The first post's race the other way round. It ends out while its cancel still posts, and the mark of that post keeps the cancel's guard.
    @Test
    fun theFirstPostEndingWhileItsCancelStillPostsKeepsTheCancelsGuard() = runBlocking {
        val phone = Guarded()
        val firstPosting = CompletableDeferred<Unit>()
        val cancelPosting = CompletableDeferred<Unit>()
        val firstEnded = CompletableDeferred<Unit>()
        phone.answer = { txid ->
            when (txid) {
                SIGNED_TXID -> {
                    firstPosting.complete(Unit)
                    runBlocking { cancelPosting.await() }
                }
                CANCEL.first -> {
                    cancelPosting.complete(Unit)
                    runBlocking { firstEnded.await() }
                }
            }
            HotSendResult(txid)
        }
        val cancel = launch(Dispatchers.Default) {
            firstPosting.await()
            assertEquals(Repost.Out, phone.cancel())
        }
        assertTrue(phone.wallet.prepare(phone.session, 100_000, 8, PAYEE))
        assertTrue(phone.wallet.post(phone.session), "the first post is out")
        firstEnded.complete(Unit)
        cancel.join()
        assertEquals(CANCEL_GUARD, phone.wallet.unconfirmedSend, "the wallet")
        assertEquals(CANCEL_GUARD, phone.guards.guardOf(), "the phone")
    }

    // Two instances. A's first post runs, B takes the guard and cancels, and A's post then ends out. A's settle meets the cancel's line,
    // which stays, and so does A's next payment.
    @Test
    fun aCancelFromAnotherInstanceDuringTheFirstPostKeepsItsGuard() {
        val a = Guarded()
        a.answer = { txid ->
            if (txid == SIGNED_TXID) {
                val b = Guarded(a.guards)
                assertEquals(FIRST, b.wallet.unconfirmedSend, "B took the first payment's guard")
                b.answer = { HotSendResult(it) }
                assertEquals(Repost.Out, b.cancel())
            }
            HotSendResult(txid)
        }
        assertTrue(a.wallet.prepare(a.session, 100_000, 8, PAYEE))
        assertTrue(a.wallet.post(a.session), "A's first post is out")
        assertEquals(CANCEL_GUARD, a.guards.guardOf())
        assertFalse(a.wallet.prepare(a.session, 50_000, 8, PAYEE) && a.wallet.post(a.session), "A's next payment")
        assertEquals(listOf(SIGNED_TXID), a.posts)
    }

    // Two instances. B's retry of the old bytes runs while A cancels. B's retry ends out, but its settle meets the cancel's line, and so
    // does B's next payment.
    @Test
    fun aRetryOfTheOldBytesRacingACancelDropsNothing() {
        val a = Guarded().firstPostUnknown()
        val b = Guarded(a.guards)
        assertEquals(FIRST, b.wallet.unconfirmedSend)
        a.answer = { HotSendResult(it) }
        b.answer = { txid ->
            if (txid == SIGNED_TXID) assertEquals(Repost.Out, a.cancel(), "A's cancel")
            HotSendResult(txid)
        }
        assertEquals(Repost.Out, b.wallet.repost(b.session))
        assertFalse(b.wallet.prepare(b.session, 50_000, 8, PAYEE) && b.wallet.post(b.session), "B's next payment")
        assertEquals(CANCEL_GUARD, a.guards.guardOf())
        assertEquals(listOf(SIGNED_TXID), b.posts)
    }

    // Two instances. B took the cancel's guard, and its user clears it while A's cancel still posts. The mark of that post refuses the clear.
    @Test
    fun aClearWhileTheCancelPostsDropsNothing() = runBlocking {
        val a = Guarded().firstPostUnknown()
        val posting = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        a.answer = { txid ->
            if (txid == CANCEL.first) {
                posting.complete(Unit)
                runBlocking { release.await() }
            }
            HotSendResult(txid)
        }
        val cancel = launch(Dispatchers.Default) { assertEquals(Repost.Out, a.cancel()) }
        posting.await()
        val b = Guarded(a.guards)
        assertEquals(CANCEL_GUARD, b.wallet.unconfirmedSend, "B took the cancel's guard")
        assertFalse(b.wallet.clearUnconfirmedSend(b.session, CANCEL_GUARD), "B's clear while the cancel posts")
        release.complete(Unit)
        cancel.join()
        assertEquals(CANCEL_GUARD, a.guards.guardOf())
    }

    // Two instances. A cancels after B took the first payment's guard. B's bind settles the first payment on a listing with no block, as a
    // chain with no cancel settles, but the phone keeps the cancel's line, so B's next payment is refused before any post.
    @Test
    fun aStaleInstancesBindCannotOpenTheWayForANewPayment() {
        val a = Guarded().firstPostUnknown()
        val b = Guarded(a.guards)
        assertEquals(FIRST, b.wallet.unconfirmedSend)
        a.answer = { HotSendResult(it) }
        assertEquals(Repost.Out, a.cancel())
        b.history = listOf(HotTx(SIGNED_TXID, -101_128L, 0u, null))
        b.wallet.bindSession(b.session)
        assertFalse(b.wallet.prepare(b.session, 50_000, 8, PAYEE) && b.wallet.post(b.session), "B's next payment")
        assertEquals(emptyList<String>(), b.posts)
        assertEquals(CANCEL_GUARD, a.guards.guardOf())
    }

    // A double tap on the card's confirm, through the screen's RepostAttempt and the real wallet, posts the cancel once.
    @Test
    fun aDoubleTapOnTheCardsConfirmPostsOnce() = runBlocking {
        val phone = Guarded().firstPostUnknown()
        assertTrue(phone.wallet.prepareReplacement(phone.session, Replacement.Cancel, 20))
        val held = assertNotNull(phone.wallet.heldReplacement)
        val attempt = RepostAttempt()
        val draft = SendDraft()
        val scope = CoroutineScope(coroutineContext + Job())
        List(2) { attempt.postReplacement(scope, held, draft) { phone.wallet.postReplacement(phone.session) } }.forEach { it.join() }
        assertEquals(listOf(SIGNED_TXID, CANCEL.first), phone.posts)
    }
}

private const val SENT_AT = 1_792_740_720L
private const val LATER = SENT_AT + 300
private const val PAYEE = "tb1qthepayee"

/** [SIGNED_HEX] with another lock time, so a transaction with its own txid, which is all the guard file checks. */
private fun relocked(lockByte: Int): Pair<String, String> {
    val hex = SIGNED_HEX.dropLast(8) + lockByte.toString(16).padStart(2, '0') + SIGNED_HEX.takeLast(6)
    return assertNotNull(txidOf(hex)) to hex
}

/** What the fake core signs for a cancel and for a fee bump, each a txid and its hex. */
private val CANCEL = relocked(0x20)
private val BUMP = relocked(0x10)

/** The guard of the first payment, whose post ended unknown at [SENT_AT]. */
private val FIRST = UnconfirmedSend(SIGNED_TXID, SENT_AT, SIGNED_HEX)

/** The guard of a bump of [FIRST] posted at [LATER], and of a cancel of it. */
private val BUMP_GUARD = UnconfirmedSend(BUMP.first, LATER, BUMP.second, listOf(SIGNED_TXID))
private val CANCEL_GUARD = UnconfirmedSend(CANCEL.first, LATER, CANCEL.second, listOf(SIGNED_TXID), holdsCancel = true)

/**
 * A live wallet on a phone whose guard file is [guards]. The core signs the first payment as [SIGNED_HEX] and every later one as
 * [OTHER_SIGNED_HEX], as it would from other coins once the first spent its own. It signs a fee bump as [BUMP] and a cancel as
 * [CANCEL], until a test swaps [sign], and records what it was asked in [core]. Every post lands in [posts] and the node answers
 * [answer]. Each history load runs [onHistory] and then answers [history]. The clock reads [LATER] once the first post is behind it.
 */
private class Guarded(val guards: FakePlainFile = FakePlainFile()) {
    val posts = mutableListOf<String>()
    val core = mutableListOf<Pair<Replacement, String>>()
    var answer: (txid: String) -> HotSendResult = { throw MobileSignerException.BroadcastUnknown("unknown") }
    var history: List<HotTx> = emptyList()
    var onHistory: () -> Unit = {}
    var sign: (Replacement) -> PreparedSend = { kind ->
        when (kind) {
            Replacement.FeeBump -> PreparedSend(BUMP.first, BUMP.second, 20uL * 141uL, 100_000uL)
            Replacement.Cancel -> PreparedSend(CANCEL.first, CANCEL.second, 20uL * 110uL, 98_000uL)
        }
    }
    var clock = SENT_AT
    private var payments = 0
    val session = OnboardingSession(
        SavedWallet(FakeSecureStore()),
        restoreKey = { w, _, _, _ -> SoftwareAccountKey(w, TEST_CHECK, "tpub-test", "m/48'/1'/0'/2'") },
        payments = PendingPayments(guards),
    ).apply { restoreFromWords(PUBLIC_WORDS_24) }
    val wallet = SingleSigWallet(
        prepareAtRate = { _, _, _, _, amount, _ ->
            if (payments++ == 0) PreparedSend(SIGNED_TXID, SIGNED_HEX, 1_128uL, 100_000uL) else PreparedSend(OTHER_SIGNED_TXID, OTHER_SIGNED_HEX, 1_128uL, amount)
        },
        prepareAll = { _, _, _, _, _ -> error("not used") },
        postHex = { _, _, txid ->
            posts += txid
            answer(txid)
        },
        snapshot = { _, _, _ -> HotWalletSnapshot("tb1qreceive", 1_000_000uL, 1_000_000uL, KeyNetwork.TEST, HotScan.COMPLETE) },
        fetchHistory = { _, _, _ ->
            onHistory()
            history
        },
        nowSeconds = { clock },
        receiveAddressOf = { _, _, _, _ -> "tb1qoffline" },
        bumpAtRate = { _, _, _, hex, _ ->
            core += Replacement.FeeBump to hex
            sign(Replacement.FeeBump)
        },
        cancelAtRate = { _, _, _, hex, _ ->
            core += Replacement.Cancel to hex
            sign(Replacement.Cancel)
        },
    )

    init {
        wallet.bindSession(session)
    }

    /** The first post of [SIGNED_HEX], whose answer is lost, so the guard keeps the bytes. */
    fun firstPostUnknown(): Guarded {
        assertTrue(wallet.prepare(session, 100_000, 8, PAYEE))
        assertFalse(wallet.post(session), "the first post")
        assertEquals(FIRST, wallet.unconfirmedSend)
        clock = LATER
        return this
    }

    /** The user's fee bump of the guard this wallet holds, at [LATER], past the owner check and then the card's confirm. */
    fun bump(): Repost = replace(Replacement.FeeBump)

    /** The user's cancel of the guard this wallet holds, at [LATER], past the owner check and then the card's confirm. */
    fun cancel(): Repost = replace(Replacement.Cancel)

    private fun replace(kind: Replacement): Repost {
        clock = LATER
        assertTrue(wallet.prepareReplacement(session, kind, 20), "the core signed the $kind")
        return wallet.postReplacement(session)
    }
}
