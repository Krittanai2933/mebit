package com.mebit.app.data

import com.mebit.app.data.SingleSigWallet.UnconfirmedSend
import com.mebit.ffi.signer.HotScan
import com.mebit.ffi.signer.HotTx
import com.mebit.ffi.signer.WalletBalance
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** A several-key wallet's pay-twice guard as the Wallet detail's read settles it, and the read a payment out drops. */
class WalletsVaultSendTest {
    private val vault = CreatedVault(
        "vault_1", "Wallet 1", 2, 3, CustodyPolicy.PersonalCustody, "tb1qvault1",
        "BSMS 1.0\nwsh(sortedmulti(2,[00000001/48'/1'/0'/2']tpubA,[00000001/48'/1'/0'/2']tpubB,[00000001/48'/1'/0'/2']tpubC))\n/0/*,/1/*\ntb1qvault1",
        List(3) { "00000001" },
    )
    private val sent = "ab".repeat(32)
    private var reads = 0
    private var listed = listOf(HotTx(sent, -600_000, 0u, null))
    private var duringRead: () -> Unit = {}
    private val guards = PendingPayments(FakePlainFile())

    private val wallets: Wallets = Wallets(
        FakeShelf(),
        vaultCore = DescribedCore(
            balance = { _, _ ->
                reads++
                duringRead()
                WalletBalance(84_200_000uL, HotScan.COMPLETE)
            },
            history = { _, _ -> listed },
        ),
        nowSeconds = { 1_000L },
        vaultPayments = guards,
    )

    @Test
    fun aHistoryThatListsTheGuardsPaymentSettlesIt(): Unit = runBlocking {
        assertEquals(Claim.Written, guards.claim(vaultGuardKey(vault), UnconfirmedSend(sent, 900L)))
        wallets.readVaultDetail(vault)
        assertNull(guards.of(vaultGuardKey(vault)))
    }

    @Test
    fun aHistoryWithoutThePaymentKeepsTheGuard(): Unit = runBlocking {
        listed = listOf(HotTx("cd".repeat(32), 84_200_000, 3u, 1_790_000_000uL))
        assertEquals(Claim.Written, guards.claim(vaultGuardKey(vault), UnconfirmedSend(sent, 900L)))
        wallets.readVaultDetail(vault)
        assertIs<UnconfirmedSend>(guards.of(vaultGuardKey(vault)))
    }

    @Test
    fun aPaymentOutDropsTheReadSoTheDetailReadsAgain(): Unit = runBlocking {
        wallets.readVaultDetail(vault)
        wallets.readVaultDetail(vault)
        assertEquals(1, reads, "a read of the last minute answers")
        wallets.forgetVaultRead(vault)
        assertNull(wallets.vaultSats(vault))
        wallets.readVaultDetail(vault)
        assertEquals(2, reads)
        assertEquals(84_200_000L, wallets.vaultSats(vault))
    }

    @Test
    fun aReadThatBeganBeforeThePaymentWentOutWritesNothing(): Unit = runBlocking {
        duringRead = { wallets.forgetVaultRead(vault) }
        wallets.readVaultDetail(vault)
        assertNull(wallets.vaultSats(vault))
    }
}
