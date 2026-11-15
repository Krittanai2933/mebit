package com.mebit.app.data

import com.mebit.ffi.signer.SpendOutput
import com.mebit.ffi.signer.SpendReview
import com.mebit.ffi.signer.WalletKey
import com.mebit.ffi.signer.WalletShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Two เซ็น taps that run at once on the JVM's threads: one claims the send and signs, and the other is refused before Rust, so no answer
 * builds on a PSBT another answer replaced and no signature is lost. The refused key signs once the first ends.
 */
class VaultSendConcurrencyTest {
    @Test
    fun twoSignsStartedTogetherNeverMeetInRustAndLoseNoSignature(): Unit = runBlocking {
        val vault = CreatedVault("vault_1", "Wallet 1", 2, 3, CustodyPolicy.PersonalCustody, "tb1qvault", "", listOf("AAAA0000", "BBBB0000", "CCCC0000"))
        // The PSBT text is the fingerprints that signed it, so an answer builds on exactly the PSBT the send passed.
        fun review(signed: List<String>) = SpendReview(signed.joinToString(","), "ab".repeat(32), listOf(SpendOutput("", 1_000uL)), 0uL, 0uL, signed, 2u)
        val inRust = AtomicInteger(0)
        val met = AtomicBoolean(false)
        val send = VaultSend(
            vault,
            PendingPayments(FakePlainFile()),
            worker = Dispatchers.Default,
            core = DescribedCore(
                describe = { _, _ -> WalletShape(2u, vault.keyFingerprints.map { WalletKey(it.lowercase(), "m/48'/1'/0'/2'", "") }, vault.address, 201uL) },
                draft = { _, _, _, _, _ -> review(emptyList()) },
                signWithWords = { _, _, psbt, _, words, _ ->
                    if (inRust.incrementAndGet() > 1) met.set(true)
                    Thread.sleep(50)
                    inRust.decrementAndGet()
                    review((psbt.split(",").filter { it.isNotEmpty() } + words.single()).distinct())
                },
            ),
        )
        val phone = { key: Int -> KeySigner.Phone(key, listOf(vault.keyFingerprints[key].lowercase()), "") }
        assertTrue(send.prepare())
        val first = async(Dispatchers.Default) { send.sign(phone(0)) { true } }
        val second = async(Dispatchers.Default) { send.sign(phone(1)) { true } }
        val signed = listOf(first.await(), second.await())
        assertFalse(met.get(), "two signs reached Rust at once")
        assertEquals(signed.count { it }, send.signedFingerprints.size, "every sign that said true kept its signature")
        signed.withIndex().filterNot { it.value }.forEach { assertTrue(send.sign(phone(it.index)) { true }, "the refused key signs once the first ends") }
        assertEquals(setOf("AAAA0000", "BBBB0000"), send.signedFingerprints)
    }
}
