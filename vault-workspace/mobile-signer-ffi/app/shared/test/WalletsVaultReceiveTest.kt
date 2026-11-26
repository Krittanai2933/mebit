package com.mebit.app.data

import com.mebit.ffi.signer.HotScan
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.WalletBalance
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A several-key wallet's Receive address (4.3.1): the next unused one by its scan, as a one-key wallet's is. */
class WalletsVaultReceiveTest {
    private val vault = CreatedVault(
        "vault_1", "Wallet 1", 2, 3, CustodyPolicy.PersonalCustody, "tb1qvault1",
        "BSMS 1.0\nwsh(sortedmulti(2,[00000001/48'/1'/0'/2']tpubA,[00000001/48'/1'/0'/2']tpubB,[00000001/48'/1'/0'/2']tpubC))\n/0/*,/1/*\ntb1qvault1",
        List(3) { "00000001" },
    )
    private val calls = mutableListOf<String>()
    private var balance: () -> WalletBalance = { WalletBalance(50_000uL, HotScan.COMPLETE) }
    private var last: () -> String = { "tb1qnextunused" }

    private val wallets = Wallets(
        FakeShelf(),
        vaultCore = DescribedCore(
            balance = { _, _ ->
                calls += "balance"
                balance()
            },
            lastReceive = { d, n ->
                calls += "last $n ${d == descriptorIn(vault.bsms)}"
                last()
            },
        ),
        nowSeconds = { 1_000L },
        vaultPayments = PendingPayments(FakePlainFile()),
    )

    @Test
    fun theAddressComesFromTheScanAfterARead(): Unit = runBlocking {
        assertEquals("tb1qnextunused", wallets.readVaultReceiveAddress(vault))
        assertEquals(listOf("balance", "last TEST true"), calls, "the read first, then the address its scan handed out, by this wallet's descriptor")
        assertFalse(wallets.vaultReadFailed(vault))
    }

    @Test
    fun aFailedReadStillGivesTheAddressAnEarlierReadHandedOutAndSaysSo(): Unit = runBlocking {
        balance = { throw IllegalStateException("esplora: unreachable") }
        assertEquals("tb1qnextunused", wallets.readVaultReceiveAddress(vault))
        assertTrue(wallets.vaultReadFailed(vault))
    }

    @Test
    fun theBooksAddress0WhenTheCoreGivesNone() {
        last = { throw IllegalStateException("no scan") }
        assertEquals(vault.address, wallets.vaultReceiveAddress(vault))
    }

    @Test
    fun aMainnetShelfAsksOnMainnet() {
        val main = Wallets(
            FakeShelf(network = KeyNetwork.MAIN),
            vaultCore = DescribedCore(lastReceive = { _, n -> n.name }),
            nowSeconds = { 1_000L },
            vaultPayments = PendingPayments(FakePlainFile()),
        )
        assertEquals("MAIN", main.vaultReceiveAddress(vault))
    }
}
