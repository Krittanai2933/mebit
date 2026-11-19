package com.mebit.app.data

import com.mebit.app.data.SingleSigWallet.DamagedGuard
import com.mebit.app.data.SingleSigWallet.UnconfirmedSend
import com.mebit.app.data.SingleSigWallet.WalletMode
import com.mebit.app.feature.send.RepostAttempt
import com.mebit.ffi.signer.AddressType
import com.mebit.ffi.signer.BleException
import com.mebit.ffi.signer.HotScan
import com.mebit.ffi.signer.HotSendResult
import com.mebit.ffi.signer.HotTx
import com.mebit.ffi.signer.HotWalletSnapshot
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.MobileSignerException
import com.mebit.ffi.signer.PreparedSend
import com.mebit.ffi.signer.SpendOutput
import com.mebit.ffi.signer.SpendReview
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The unsigned txid wallet_draft named, which every later step must be given. */
private val DRAFT_TXID = "d".repeat(64)

private fun review(psbt: String, signed: List<String> = emptyList()) =
    SpendReview(psbt, DRAFT_TXID, listOf(SpendOutput("tb1qdestination", 10_000uL)), 39_500uL, 500uL, signed, 1u)

/** One hardware wallet over fakes of the core, the guard file and the device. [calls] lists what reached the core, in order. */
private class Rig {
    val guards = FakePlainFile()
    val calls = mutableListOf<String>()
    var snapshot: () -> HotWalletSnapshot = { HotWalletSnapshot("tb1qnextunused", 50_000uL, 40_000uL, KeyNetwork.TEST, HotScan.COMPLETE) }
    var history: List<HotTx> = emptyList()
    var post: () -> HotSendResult = { HotSendResult(SIGNED_TXID) }
    var now = 1_000L
    val device = FakeDeviceLink(sign = { _, psbt, _ -> review("$psbt+signed", signed = listOf(PUBLIC_FINGERPRINT)) })
    val core = DescribedCore(
        snapshot = { d, _ ->
            calls += "snapshot $d"
            snapshot()
        },
        history = { _, _ -> history },
        draft = { _, _, to, sats, rate ->
            calls += "draft $to $sats $rate"
            review("unsigned")
        },
        finish = { _, _, psbt, txid ->
            calls += "finish $psbt $txid"
            PreparedSend(SIGNED_TXID, SIGNED_HEX, 500uL, 10_000uL)
        },
        postHex = { _, _, txid ->
            calls += "post $txid"
            post()
        },
    )
    val chain = DescribedWallet(PUBLIC_JADE_WALLET, PendingPayments(guards), HardwareLink(null) { device }, { listOf(TEST_CHECK) }, core, { now })
}

class DescribedWalletTest {
    @Test
    fun aRefreshReadsTheWalletByItsDescriptorAndAFailedOneKeepsReceiveAddress0(): Unit = runBlocking {
        val rig = Rig()
        val chain = rig.chain
        assertEquals(WalletMode.Demo, chain.mode, "no refresh has ended")
        assertFalse(chain.balanceKnown)
        assertEquals(PUBLIC_JADE_WALLET.address, chain.receiveAddress)

        chain.refresh()
        assertEquals(listOf("snapshot ${PUBLIC_JADE_WALLET.descriptor}"), rig.calls)
        assertEquals(listOf(WalletMode.Live, 50_000L, 40_000L, "tb1qnextunused"), listOf(chain.mode, chain.balanceSats, chain.spendableSats, chain.receiveAddress))
        assertTrue(chain.balanceKnown)
        assertTrue(chain.boundWithin(60))

        rig.snapshot = { HotWalletSnapshot("tb1qnextunused", 20_000uL, 0uL, KeyNetwork.TEST, HotScan.CONTINUING) }
        chain.refresh()
        assertFalse(chain.balanceKnown, "a sum of part of the wallet is no balance")

        rig.snapshot = { throw MobileSignerException.Network("esplora: unreachable") }
        chain.refresh()
        assertEquals(WalletMode.Unavailable, chain.mode)
        assertFalse(chain.balanceKnown)
        assertEquals(0L, chain.balanceSats)
        assertEquals(PUBLIC_JADE_WALLET.address, chain.receiveAddress, "receive address 0, which the book checked against the key")
        assertNotNull(chain.lastError)
        assertEquals("snapshot: esplora: unreachable", chain.lastFailure)
    }

    @Test
    fun aPaymentIsDraftedSignedOnTheDeviceFinishedUnderTheDraftsTxidAndPostedOnce(): Unit = runBlocking {
        val rig = Rig()
        val chain = rig.chain
        chain.refresh()
        assertTrue(chain.draft("tb1qdestination", 10_000, 2))
        assertEquals(review("unsigned"), assertIs<DevicePayment.Drafted>(chain.payment).review)
        assertTrue(rig.device.calls.isEmpty(), "Review asks nothing of the device")

        assertTrue(chain.sign(JADE_FOUND, NO_CODES, NO_STEPS))
        assertEquals(listOf("connect", "unlock", "accountXpub m/84'/1'/0'", "signWalletPsbt $DRAFT_TXID", "disconnect", "close"), rig.device.calls)
        assertEquals(PreparedSend(SIGNED_TXID, SIGNED_HEX, 500uL, 10_000uL), assertIs<DevicePayment.Signed>(chain.payment).prepared)

        assertTrue(chain.post())
        assertEquals(
            listOf("draft tb1qdestination 10000 2", "finish unsigned+signed $DRAFT_TXID", "post $SIGNED_TXID", "snapshot ${PUBLIC_JADE_WALLET.descriptor}"),
            rig.calls.drop(1),
        )
        assertEquals(listOf(SIGNED_TXID, 500L, 10_000L, 1_000L), listOf(chain.lastTxid, chain.lastFeeSats, chain.lastAmountSats, chain.lastSentAtSeconds))
        assertNull(chain.unconfirmedSend)
        assertNull(PendingPayments(rig.guards).of(PUBLIC_JADE_WALLET.guardKey), "the guard went once the payment was out")
        assertNull(chain.payment)
        assertFalse(chain.post(), "nothing is held, so a second tap posts nothing")
        assertEquals(1, rig.calls.count { it.startsWith("post") })
    }

    @Test
    fun aDeviceHoldingAnotherKeyIsShownNothingAndThePaymentStaysDrafted(): Unit = runBlocking {
        val rig = Rig()
        rig.device.fingerprint = "11112222"
        rig.chain.refresh()
        assertTrue(rig.chain.draft("tb1qdestination", null, 2))
        assertFailsWith<BleException.WrongKey> { rig.chain.sign(JADE_FOUND, NO_CODES, NO_STEPS) }
        assertTrue(rig.device.calls.none { it.startsWith("signWalletPsbt") })
        assertIs<DevicePayment.Drafted>(rig.chain.payment)
        assertTrue(rig.calls.none { it.startsWith("finish") })
    }

    @Test
    fun noPaymentStartsWhileTheBalanceIsUnknownOrAnEarlierPaymentMayBeOut(): Unit = runBlocking {
        val rig = Rig()
        assertFalse(rig.chain.draft("tb1qdestination", 10_000, 2))
        assertEquals(BALANCE_UNKNOWN, rig.chain.lastError)

        val earlier = UnconfirmedSend(TEST_TXID, 900L)
        assertEquals(Claim.Written, PendingPayments(rig.guards).claim(PUBLIC_JADE_WALLET.guardKey, earlier))
        rig.chain.refresh()
        assertEquals(earlier, rig.chain.unconfirmedSend, "the guard the phone keeps under this wallet's key")
        assertFalse(rig.chain.draft("tb1qdestination", 10_000, 2))
        assertEquals(EARLIER_UNSETTLED, rig.chain.lastError)
        assertTrue(rig.calls.none { it.startsWith("draft") })

        rig.history = listOf(HotTx(TEST_TXID, -10_500, 0u, null))
        rig.chain.refresh()
        assertNull(rig.chain.unconfirmedSend, "a history that lists the payment settles it")
        assertNull(PendingPayments(rig.guards).of(PUBLIC_JADE_WALLET.guardKey))
        assertTrue(rig.chain.draft("tb1qdestination", 10_000, 2))
    }

    @Test
    fun anUnknownOutcomeKeepsTheGuardUnderTheWalletsKeyAndAPostOfTheSameBytesDropsIt(): Unit = runBlocking {
        val rig = Rig()
        val chain = rig.chain
        chain.refresh()
        assertTrue(chain.draft("tb1qdestination", 10_000, 2))
        assertTrue(chain.sign(JADE_FOUND, NO_CODES, NO_STEPS))
        rig.post = { throw MobileSignerException.BroadcastUnknown("answer lost") }
        assertFalse(chain.post())
        assertEquals(SEND_UNKNOWN, chain.lastError)
        val guard = UnconfirmedSend(SIGNED_TXID, 1_000L, SIGNED_HEX)
        assertEquals(guard, chain.unconfirmedSend)
        assertEquals(guard, PendingPayments(rig.guards).of(PUBLIC_JADE_WALLET.guardKey), "the signed bytes are kept with it")
        assertNull(PendingPayments(rig.guards).of(TEST_CHECK), "no other wallet's guard")
        assertFalse(chain.draft("tb1qdestination", 10_000, 2), "no second payment while this one may be out")

        rig.now = 1_200L
        assertTrue(chain.unconfirmedClearOffered())
        rig.post = { HotSendResult(SIGNED_TXID) }
        assertEquals(Repost.Out, chain.repost())
        assertEquals(2, rig.calls.count { it == "post $SIGNED_TXID" })
        assertNull(chain.unconfirmedSend)
        assertNull(PendingPayments(rig.guards).of(PUBLIC_JADE_WALLET.guardKey))
    }

    @Test
    fun aRefusalByTheNodeDropsTheGuardAndSaysWhy(): Unit = runBlocking {
        val rig = Rig()
        rig.chain.refresh()
        assertTrue(rig.chain.draft("tb1qdestination", 10_000, 2))
        assertTrue(rig.chain.sign(JADE_FOUND, NO_CODES, NO_STEPS))
        rig.post = { throw MobileSignerException.InvalidPsbtJson("esplora post: 400 bad-txns-inputs-missingorspent") }
        assertFalse(rig.chain.post())
        assertEquals(sendErrorThai("invalid PSBT json: esplora post: 400 bad-txns-inputs-missingorspent"), rig.chain.lastError)
        assertNull(rig.chain.unconfirmedSend)
        assertNull(PendingPayments(rig.guards).of(PUBLIC_JADE_WALLET.guardKey))
    }

    @Test
    fun aPaymentThatEndedUnknownIsPostedAgainOrClearedThroughThePhoneWalletsControls(): Unit = runBlocking {
        val rig = Rig()
        val exits: GuardExits = rig.chain
        rig.post = { throw MobileSignerException.BroadcastUnknown(SIGNED_TXID) }
        rig.chain.refresh()
        assertTrue(rig.chain.draft("tb1qdestination", 10_000, 2) && rig.chain.sign(JADE_FOUND, NO_CODES, NO_STEPS))
        assertFalse(rig.chain.post(), "offline, the post ends unknown")
        val guard = assertIs<UnconfirmedSend>(exits.unconfirmedSend)
        assertEquals(SIGNED_HEX, guard.hex, "the guard keeps the signed bytes, so the retry is offered at once")
        assertFalse(rig.chain.draft("tb1qdestination", 10_000, 2), "and no new payment starts while it may be out")

        rig.post = { HotSendResult(SIGNED_TXID) }
        val draft = SendDraft().apply { destination = "tb1qdestination" }
        val attempt = RepostAttempt()
        attempt.start(this, guard.txid, confirm = { true }, draft) { exits.repost() }.join()
        assertEquals(Repost.Out, attempt.done?.outcome)
        assertNull(exits.unconfirmedSend, "the retry put the same transaction out and dropped its guard")
        assertEquals("", draft.destination, "with the form of that payment, so no second review can pay it twice")
        assertTrue(rig.chain.draft("tb1qdestination", 10_000, 2), "a new payment can start")

        rig.post = { throw MobileSignerException.BroadcastUnknown(SIGNED_TXID) }
        assertTrue(rig.chain.sign(JADE_FOUND, NO_CODES, NO_STEPS))
        assertFalse(rig.chain.post(), "a payment that never left the phone")
        val stuck = assertIs<UnconfirmedSend>(exits.unconfirmedSend)
        assertFalse(exits.unconfirmedClearOffered(), "no clear while it may still land")
        rig.now += UNCONFIRMED_CLEAR_OFFER_SECONDS
        assertTrue(exits.unconfirmedClearOffered())
        assertTrue(exits.clearUnconfirmedSend(stuck))
        assertNull(exits.unconfirmedSend)
        assertNull(PendingPayments(rig.guards).of(PUBLIC_JADE_WALLET.guardKey), "on the phone too")
        assertTrue(rig.chain.draft("tb1qdestination", 10_000, 2))
    }

    @Test
    fun theDamagedLineAPhoneWalletsClearLeftIsClearedAtOnceThroughThePhoneWalletsControls(): Unit = runBlocking {
        val rig = Rig()
        rig.guards.text = "garbage\n"
        assertTrue(PendingPayments(rig.guards).settle(TEST_CHECK, DamagedGuard, listOf(PUBLIC_JADE_WALLET.guardKey)), "a phone wallet's user cleared the damaged file")
        rig.chain.refresh()
        val exits: GuardExits = rig.chain
        assertEquals(DamagedGuard, exits.unconfirmedSend, "the line that clear left for this wallet")
        assertFalse(rig.chain.draft("tb1qdestination", 10_000, 2), "stops its payments")
        assertTrue(exits.unconfirmedClearOffered(), "and its clear is offered at once")
        assertTrue(exits.clearUnconfirmedSend(DamagedGuard))
        assertNull(exits.unconfirmedSend)
        assertTrue(rig.chain.draft("tb1qdestination", 10_000, 2))
    }

    @Test
    fun aDroppedPaymentIsNeverSignedOrPosted(): Unit = runBlocking {
        val rig = Rig()
        rig.chain.refresh()
        assertTrue(rig.chain.draft("tb1qdestination", 10_000, 2))
        rig.chain.dropPayment()
        assertFalse(rig.chain.sign(JADE_FOUND, NO_CODES, NO_STEPS))
        assertTrue(rig.device.calls.isEmpty(), "nothing reached the device")
        assertFalse(rig.chain.post())
        assertTrue(rig.calls.none { it.startsWith("post") })
    }

    @Test
    fun eachHardwareWalletHasOneChainAndAListRefreshStopsAtAScanThatContinues(): Unit = runBlocking {
        val book = SavedVaults.memoryOnly()
        val second = PUBLIC_JADE_WALLET.copy(id = "hw_2", addressType = AddressType.TAPROOT, xpub = PUBLIC_ACCOUNTS.getValue(AddressType.TAPROOT).tpub)
        book.edit { it.copy(hardware = listOf(PUBLIC_JADE_WALLET, second)) }
        val read = mutableListOf<String>()
        var scan = HotScan.CONTINUING
        val core = DescribedCore(snapshot = { d, _ ->
            read += d
            HotWalletSnapshot("tb1q", 1uL, 1uL, KeyNetwork.TEST, scan)
        }, history = { _, _ -> emptyList() })
        val hardware = HardwareWallets(book, core = core)
        assertTrue(hardware.chain(PUBLIC_JADE_WALLET) === hardware.chain(PUBLIC_JADE_WALLET.copy()), "Home, Send and the list share one")

        hardware.refreshBalances()
        assertEquals(listOf(PUBLIC_JADE_WALLET.descriptor), read, "a host that limits one wallet would limit the next")
        scan = HotScan.COMPLETE
        hardware.refreshBalances()
        assertEquals(listOf(PUBLIC_JADE_WALLET.descriptor, second.descriptor), read, "the first was read in the last minute")
    }
}
