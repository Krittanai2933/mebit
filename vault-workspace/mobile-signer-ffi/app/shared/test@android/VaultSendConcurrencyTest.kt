package com.mebit.app.data

import com.mebit.ffi.signer.SpendOutput
import com.mebit.ffi.signer.SpendReview
import com.mebit.ffi.signer.WalletKey
import com.mebit.ffi.signer.WalletShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Two เซ็น taps that run at once on the JVM's threads: each Rust call builds on the PSBT the other returned, so no signature is lost. */
class VaultSendConcurrencyTest {
    @Test
    fun twoSignsStartedTogetherKeepBothSignatures(): Unit = runBlocking {
        val vault = CreatedVault("vault_1", "Wallet 1", 2, 3, CustodyPolicy.PersonalCustody, "tb1qvault", "", listOf("AAAA0000", "BBBB0000", "CCCC0000"))
        // The PSBT text is the fingerprints that signed it, so an answer builds on exactly the PSBT the send passed.
        fun review(signed: List<String>) = SpendReview(signed.joinToString(","), "ab".repeat(32), listOf(SpendOutput("", 1_000uL)), 0uL, 0uL, signed, 2u)
        val send = VaultSend(
            vault,
            PendingPayments(FakePlainFile()),
            worker = Dispatchers.Default,
            core = DescribedCore(
                describe = { _, _ -> WalletShape(2u, vault.keyFingerprints.map { WalletKey(it.lowercase(), "m/48'/1'/0'/2'", "") }, vault.address, 201uL) },
                draft = { _, _, _, _, _ -> review(emptyList()) },
                signWithWords = { _, _, psbt, _, words, _ ->
                    Thread.sleep(50)
                    review((psbt.split(",").filter { it.isNotEmpty() } + words.single()).distinct())
                },
            ),
        )
        assertTrue(send.prepare())
        val first = async(Dispatchers.Default) { send.sign(KeySigner.Phone(0, listOf("aaaa0000"), "")) { true } }
        val second = async(Dispatchers.Default) { send.sign(KeySigner.Phone(1, listOf("bbbb0000"), "")) { true } }
        assertTrue(first.await() && second.await())
        assertEquals(setOf("AAAA0000", "BBBB0000"), send.signedFingerprints)
    }
}
