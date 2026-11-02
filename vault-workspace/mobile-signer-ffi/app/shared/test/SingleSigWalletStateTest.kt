package com.mebit.app.data

import com.mebit.app.data.SingleSigWallet.WalletMode
import com.mebit.ffi.signer.HotTx
import com.mebit.ffi.signer.HotWalletSnapshot
import com.mebit.ffi.signer.KeyNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.fail

private val KEY_WORDS = List(11) { "abandon" } + "about"
private const val KEY_PASSPHRASE = "mebit-test"
private const val CHAIN_ADDRESS = "tb1qaddressthesnapshotreported"
private const val OFFLINE_ADDRESS = "tb1qaddressderivedfromthekeyalone"
private const val DEMO_ADDRESS = "tb1qmebitdemosinglesig0000000000000000recv"
private val HISTORY = listOf(HotTx("txid-0", 1_000_000L, 3u, 1_760_000_000uL))

/** Everything the screens read from a bound wallet. */
private data class Shown(
    val mode: WalletMode,
    val balanceSats: Long,
    val receiveAddress: String,
    val history: List<HotTx>,
    val lastError: String?,
    val live: Boolean,
    val balanceKnown: Boolean,
    val addressIsReal: Boolean,
)

private fun SingleSigWallet.shown() = Shown(mode, balanceSats, receiveAddress, history, lastError, live, balanceKnown, addressIsReal)

private data class Derivation(val words: List<String>, val passphrase: String, val network: KeyNetwork, val index: UInt)

/** Fakes for the hot_* calls a bind makes. A test swaps a result to change what the next bind gets. Nothing here may send. */
private class FakeChain {
    var snapshotResult: () -> HotWalletSnapshot = { HotWalletSnapshot(CHAIN_ADDRESS, 1_000_000uL, KeyNetwork.TEST) }
    var historyResult: () -> List<HotTx> = { HISTORY }
    var offlineResult: () -> String = { OFFLINE_ADDRESS }
    val derivations = mutableListOf<Derivation>()
    val wallet = SingleSigWallet(
        send = { _, _, _, _, _, _ -> fail("send ran") },
        snapshot = { _, _, _ -> snapshotResult() },
        fetchHistory = { _, _, _ -> historyResult() },
        receiveAddressOf = { words, passphrase, network, index ->
            derivations += Derivation(words, passphrase, network, index)
            offlineResult()
        },
    )
    val session = sessionWithWords(KEY_WORDS, KEY_PASSPHRASE)

    fun bind() = wallet.bindSession(session)
}

class SingleSigWalletStateTest {
    @Test
    fun aSessionWithoutTwelveWordsIsTheDemoFixture() {
        val chain = FakeChain()
        chain.snapshotResult = { fail("snapshot ran") }
        chain.historyResult = { fail("history ran") }
        chain.offlineResult = { fail("offline derivation ran") }
        chain.wallet.bindSession(OnboardingSession())
        assertEquals(
            Shown(WalletMode.Demo, 1_250_000L, DEMO_ADDRESS, emptyList(), null, live = false, balanceKnown = true, addressIsReal = false),
            chain.wallet.shown(),
        )
    }

    @Test
    fun aLoadedSnapshotIsLiveWithItsBalanceAddressAndHistory() {
        val chain = FakeChain()
        chain.bind()
        assertEquals(
            Shown(WalletMode.Live, 1_000_000L, CHAIN_ADDRESS, HISTORY, null, live = true, balanceKnown = true, addressIsReal = true),
            chain.wallet.shown(),
        )
    }

    @Test
    fun aFailedSnapshotIsUnavailableWithTheKeysOwnAddressAndNoBalance() {
        val chain = FakeChain()
        chain.snapshotResult = { throw IllegalStateException("esplora unreachable") }
        chain.bind()
        assertEquals(
            Shown(WalletMode.Unavailable, 0L, OFFLINE_ADDRESS, emptyList(), "esplora unreachable", live = false, balanceKnown = false, addressIsReal = true),
            chain.wallet.shown(),
        )
        assertEquals(listOf(Derivation(KEY_WORDS, KEY_PASSPHRASE, KeyNetwork.TEST, 0u)), chain.derivations, "the key's first receive address, the one the snapshot watches")
    }

    @Test
    fun aFailedSnapshotAndAFailedDerivationLeaveNoAddress() {
        val chain = FakeChain()
        chain.snapshotResult = { throw IllegalStateException("esplora unreachable") }
        chain.offlineResult = { throw IllegalStateException("derivation failed") }
        chain.bind()
        assertEquals(
            Shown(WalletMode.Unavailable, 0L, "", emptyList(), "esplora unreachable", live = false, balanceKnown = false, addressIsReal = false),
            chain.wallet.shown(),
        )
    }

    @Test
    fun aLiveWalletWhoseNextBindFailsIsUnavailableAndKeepsNoStaleBalance() {
        val chain = FakeChain()
        chain.bind()
        chain.snapshotResult = { throw IllegalStateException("esplora unreachable") }
        chain.bind()
        assertEquals(
            Shown(WalletMode.Unavailable, 0L, OFFLINE_ADDRESS, emptyList(), "esplora unreachable", live = false, balanceKnown = false, addressIsReal = true),
            chain.wallet.shown(),
        )
        assertFalse(chain.wallet.broadcast(chain.session, 100_000, 1_128, "tb1qdestinationthatneverleaves"), "the old balance pays for no mock payment")
        assertEquals("", chain.wallet.lastTxid, "no mock txid")
    }

    @Test
    fun anUnavailableWalletWhoseNextBindLoadsIsLive() {
        val chain = FakeChain()
        chain.snapshotResult = { throw IllegalStateException("esplora unreachable") }
        chain.bind()
        chain.snapshotResult = { HotWalletSnapshot(CHAIN_ADDRESS, 1_000_000uL, KeyNetwork.TEST) }
        chain.bind()
        assertEquals(
            Shown(WalletMode.Live, 1_000_000L, CHAIN_ADDRESS, HISTORY, null, live = true, balanceKnown = true, addressIsReal = true),
            chain.wallet.shown(),
        )
    }

    @Test
    fun aFailedHistoryLeavesTheWalletLiveWithItsBalance() {
        val chain = FakeChain()
        chain.historyResult = { throw IllegalStateException("history page unreadable") }
        chain.bind()
        assertEquals(
            Shown(WalletMode.Live, 1_000_000L, CHAIN_ADDRESS, emptyList(), null, live = true, balanceKnown = true, addressIsReal = true),
            chain.wallet.shown(),
        )
    }

    @Test
    fun anUnknownBalanceKeepsEveryPaymentFromReview() {
        assertEquals(SendIssue.BalanceUnavailable, sendIssue(null, 0, 1_128, 0, balanceKnown = false), "before anything is typed")
        assertEquals(SendIssue.BalanceUnavailable, sendIssue(true, 100_000, 1_128, 0, balanceKnown = false), "not a shortfall against a balance nobody knows")
        assertEquals(SendIssue.BalanceUnavailable, sendIssue(true, 1_000, 1_128, 1_000_000, balanceKnown = false), "a balance number that is not known pays for nothing")
        assertEquals(SendIssue.Insufficient(1_128), sendIssue(true, 100_000, 1_128, 100_000), "a known balance still names the shortfall")
    }

    @Test
    fun anInvalidAddressIsNamedBeforeAnUnknownBalance() {
        assertEquals(SendIssue.InvalidAddress, sendIssue(false, 100_000, 1_128, 0, balanceKnown = false))
    }

    @Test
    fun anUnsettledPaymentKeepsEveryNewOneFromReviewAfterTheAddressAndBalanceChecks() {
        assertEquals(SendIssue.PaymentUnconfirmed, sendIssue(null, 0, 1_128, 1_000_000, unconfirmedSend = true), "before anything is typed")
        assertEquals(SendIssue.PaymentUnconfirmed, sendIssue(true, 100_000, 1_128, 1_000_000, unconfirmedSend = true), "a payment the balance covers")
        assertEquals(SendIssue.PaymentUnconfirmed, sendIssue(true, 2_000_000, 1_128, 1_000_000, unconfirmedSend = true), "named before a shortfall")
        assertEquals(SendIssue.BalanceUnavailable, sendIssue(true, 100_000, 1_128, 0, balanceKnown = false, unconfirmedSend = true))
        assertEquals(SendIssue.InvalidAddress, sendIssue(false, 100_000, 1_128, 1_000_000, unconfirmedSend = true))
    }
}
