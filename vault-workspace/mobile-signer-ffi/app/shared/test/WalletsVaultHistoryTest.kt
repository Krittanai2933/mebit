package com.mebit.app.data

import com.mebit.ffi.signer.HotScan
import com.mebit.ffi.signer.HotTx
import com.mebit.ffi.signer.HotTxDetail
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.WalletBalance
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** A several-key wallet's history screens (3.7.2 to 3.7.5): the read a pull forces, and one transaction in full. */
class WalletsVaultHistoryTest {
    private val vault = CreatedVault(
        "vault_1", "Wallet 1", 2, 3, CustodyPolicy.PersonalCustody, "tb1qvault1",
        "BSMS 1.0\nwsh(sortedmulti(2,[00000001/48'/1'/0'/2']tpubA,[00000001/48'/1'/0'/2']tpubB,[00000001/48'/1'/0'/2']tpubC))\n/0/*,/1/*\ntb1qvault1",
        List(3) { "00000001" },
    )
    private val txid = "ab".repeat(32)
    private var reads = 0
    private var asked: Triple<String, KeyNetwork, String>? = null
    private var answer: () -> HotTxDetail? = { null }

    private val wallets = Wallets(
        FakeShelf(),
        vaultCore = DescribedCore(
            balance = { _, _ ->
                reads++
                WalletBalance(84_200_000uL, HotScan.COMPLETE)
            },
            history = { _, _ -> emptyList() },
            detail = { d, n, id ->
                asked = Triple(d, n, id)
                answer()
            },
        ),
        nowSeconds = { 1_000L },
        vaultPayments = PendingPayments(FakePlainFile()),
    )

    @Test
    fun aPullReadsAgainInsideTheMinuteAReadStaysFresh(): Unit = runBlocking {
        wallets.readVaultDetail(vault)
        wallets.readVaultDetail(vault)
        assertEquals(1, reads, "an open inside the minute keeps the last read")
        wallets.readVaultDetail(vault, force = true)
        assertEquals(2, reads, "a pull reads again")
    }

    @Test
    fun theDetailAsksForTheTxidInThisWalletsDescriptorOnTestnet() {
        wallets.vaultTxDetail(vault, txid)
        assertEquals(Triple(descriptorIn(vault.bsms), KeyNetwork.TEST, txid), asked)
    }

    // On a mainnet shelf the reads ask on mainnet: the network is the shelf's, never a default.
    @Test
    fun onAMainnetShelfTheReadAndTheDetailAskOnMainnet(): Unit = runBlocking {
        var readOn: KeyNetwork? = null
        val main = Wallets(
            FakeShelf(network = KeyNetwork.MAIN),
            vaultCore = DescribedCore(
                balance = { _, n ->
                    readOn = n
                    WalletBalance(0uL, HotScan.COMPLETE)
                },
                history = { _, _ -> emptyList() },
                detail = { d, n, id ->
                    asked = Triple(d, n, id)
                    null
                },
            ),
            nowSeconds = { 1_000L },
            vaultPayments = PendingPayments(FakePlainFile()),
        )
        main.readVaultDetail(vault)
        assertEquals(KeyNetwork.MAIN, readOn)
        main.vaultTxDetail(vault, txid)
        assertEquals(KeyNetwork.MAIN, asked?.second)
    }

    @Test
    fun aTransactionTheExplorerKnowsLoads() {
        val detail = HotTxDetail(HotTx(txid, 120_000, 6u, 1_790_000_000uL), 120_000uL, emptyList(), emptyList(), 141uL, 222uL, 141uL, false)
        answer = { detail }
        assertEquals(TxDetailLoad.Loaded(detail), wallets.vaultTxDetail(vault, txid))
    }

    @Test
    fun aTransactionTheExplorerDoesNotKnowIsNotFound() {
        assertEquals(TxDetailLoad.NotFound, wallets.vaultTxDetail(vault, txid))
    }

    @Test
    fun aFailedReadCarriesTheCoresMessage() {
        answer = { throw IllegalStateException("host down") }
        assertEquals(TxDetailLoad.Failed("host down"), wallets.vaultTxDetail(vault, txid))
    }
}
