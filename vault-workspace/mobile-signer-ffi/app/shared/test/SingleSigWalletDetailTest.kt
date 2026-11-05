package com.mebit.app.data

import com.mebit.app.data.SingleSigWallet.WalletMode
import com.mebit.ffi.signer.HotTx
import com.mebit.ffi.signer.HotTxDetail
import com.mebit.ffi.signer.HotScan
import com.mebit.ffi.signer.HotWalletSnapshot
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.MobileSignerException
import com.mebit.ffi.signer.SoftwareAccountKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

private val DETAIL_WORDS = List(11) { "abandon" } + "about"
private const val DETAIL_PASSPHRASE = "mebit-test"
private const val DETAIL_TXID = "9f2c4b7a0d1e3f5a6b8c9d0e1f2a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c"
private const val PENDING_TXID = "3e4a1db3652a715a6bb202bcaec79818edae3dff4db24a9c928af5279f2b8f20"
private val ROW = HotTx(DETAIL_TXID, -1_801_240L, 3u, 1_787_036_520uL)
private val FOUND = HotTxDetail(ROW, 903_412uL, emptyList(), emptyList(), 1_240uL, 374uL, 294uL, true)

private data class Asked(val words: List<String>, val passphrase: String, val network: KeyNetwork, val txid: String)

/** Everything a detail must leave as it found it. */
private data class WalletState(val mode: WalletMode, val history: List<HotTx>, val lastError: String?, val lastFailure: String?, val unconfirmedSend: SingleSigWallet.UnconfirmedSend?)

private fun SingleSigWallet.state() = WalletState(mode, history, lastError, lastFailure, unconfirmedSend)

/** A wallet whose detail call answers [answer], bound live with one row and an earlier payment's guard. */
private class DetailChain(var answer: () -> HotTxDetail?) {
    val asked = mutableListOf<Asked>()
    val wallet = SingleSigWallet(
        prepareAtRate = { _, _, _, _, _, _ -> fail("prepare ran") },
        postHex = { _, _, _ -> fail("post ran") },
        snapshot = { _, _, network -> HotWalletSnapshot("tb1qaddressthesnapshotreported", 1_000_000uL, 1_000_000uL, network, HotScan.COMPLETE) },
        fetchHistory = { _, _, _ -> listOf(ROW) },
        fetchDetail = { words, passphrase, network, txid ->
            asked += Asked(words, passphrase, network, txid)
            answer()
        },
    )
    // Staged, because of the passphrase, so nothing is saved, and the guard needs the fingerprint the key gives.
    val session = OnboardingSession(restoreKey = { w, _, _, _ -> SoftwareAccountKey(w, TEST_CHECK, "tpub-test", "m/84'/1'/0'") }, payments = PendingPayments(FakePlainFile())).also {
        it.restoreFromWords(DETAIL_WORDS, DETAIL_PASSPHRASE)
        assertEquals(Claim.Written, it.recordUnconfirmedSend(PENDING_TXID, 1_792_740_720L))
        wallet.bindSession(it)
    }
}

class SingleSigWalletDetailTest {
    @Test
    fun theDemoWalletShowsItsRowAndAsksNothing() {
        val wallet = SingleSigWallet(fetchDetail = { _, _, _, _ -> fail("the demo asked the explorer") })
        assertEquals(TxDetailLoad.RowOnly, wallet.detail(OnboardingSession(), DETAIL_TXID))
    }

    @Test
    fun aFoundTransactionIsLoadedFromTheSessionsKeyOnTheNetworkAskedFor() {
        val chain = DetailChain { FOUND }
        assertEquals(TxDetailLoad.Loaded(FOUND), chain.wallet.detail(chain.session, DETAIL_TXID))
        assertEquals(listOf(Asked(DETAIL_WORDS, DETAIL_PASSPHRASE, KeyNetwork.TEST, DETAIL_TXID)), chain.asked)
        chain.wallet.detail(chain.session, DETAIL_TXID, KeyNetwork.MAIN)
        assertEquals(KeyNetwork.MAIN, chain.asked.last().network)
    }

    @Test
    fun aTxidTheExplorerDoesNotKnowIsNotFound() {
        val chain = DetailChain { null }
        assertEquals(TxDetailLoad.NotFound, chain.wallet.detail(chain.session, DETAIL_TXID))
    }

    @Test
    fun aFailureKeepsTheRustText() {
        val rust = "invalid PSBT json: esplora get: HTTP status 429, gave up after 3 attempts"
        val chain = DetailChain { throw MobileSignerException.InvalidPsbtJson(rust) }
        assertEquals(TxDetailLoad.Failed(rust), chain.wallet.detail(chain.session, DETAIL_TXID))
    }

    @Test
    fun noAnswerChangesTheWallet() {
        val rust = "invalid PSBT json: esplora get: x"
        val answers = listOf<() -> HotTxDetail?>({ FOUND }, { null }, { throw MobileSignerException.InvalidPsbtJson(rust) })
        for (answer in answers) {
            val chain = DetailChain(answer)
            val before = chain.wallet.state()
            assertEquals(WalletMode.Live, before.mode)
            assertEquals(PENDING_TXID, before.unconfirmedSend?.txid, "the guard is there to be kept")
            chain.wallet.detail(chain.session, DETAIL_TXID)
            assertEquals(before, chain.wallet.state())
        }
    }
}
