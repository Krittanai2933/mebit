package com.mebit.app.data

import com.mebit.app.data.SingleSigWallet.DamagedGuard
import com.mebit.app.data.SingleSigWallet.UnconfirmedSend
import com.mebit.ffi.signer.HotScan
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
        assertTrue(cleared.wallet.clearUnconfirmedSend(cleared.session))
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
 * A live wallet on a phone whose guard file is [guards]. The core signs a fee bump as [BUMP] and a cancel as [CANCEL], until a test
 * swaps [sign], and records what it was asked in [core]. Every post lands in [posts] and the node answers [answer]. The clock reads
 * [LATER] once the first post is behind it.
 */
private class Guarded(val guards: FakePlainFile = FakePlainFile()) {
    val posts = mutableListOf<String>()
    val core = mutableListOf<Pair<Replacement, String>>()
    var answer: (txid: String) -> HotSendResult = { throw MobileSignerException.BroadcastUnknown("unknown") }
    var history: List<HotTx> = emptyList()
    var sign: (Replacement) -> PreparedSend = { kind ->
        when (kind) {
            Replacement.FeeBump -> PreparedSend(BUMP.first, BUMP.second, 20uL * 141uL, 100_000uL)
            Replacement.Cancel -> PreparedSend(CANCEL.first, CANCEL.second, 20uL * 110uL, 98_000uL)
        }
    }
    private var clock = SENT_AT
    val session = OnboardingSession(
        SavedWallet(FakeSecureStore()),
        restoreKey = { w, _, _, _ -> SoftwareAccountKey(w, TEST_CHECK, "tpub-test", "m/48'/1'/0'/2'") },
        payments = PendingPayments(guards),
    ).apply { restoreFromWords(PUBLIC_WORDS_24) }
    val wallet = SingleSigWallet(
        prepareAtRate = { _, _, _, _, _, _ -> PreparedSend(SIGNED_TXID, SIGNED_HEX, 1_128uL, 100_000uL) },
        prepareAll = { _, _, _, _, _ -> error("not used") },
        postHex = { _, _, txid ->
            posts += txid
            answer(txid)
        },
        snapshot = { _, _, _ -> HotWalletSnapshot("tb1qreceive", 1_000_000uL, 1_000_000uL, KeyNetwork.TEST, HotScan.COMPLETE) },
        fetchHistory = { _, _, _ -> history },
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
}
