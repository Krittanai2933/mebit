package com.mebit.app.data

import com.mebit.ffi.signer.VaultPsbt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Two เซ็น taps that run at once on the JVM's threads: each Rust call builds on the PSBT the other returned, so no signature is lost. */
class VaultSendConcurrencyTest {
    @Test
    fun twoSignsStartedTogetherKeepBothSignatures() = runBlocking {
        val vault = CreatedVault("vault_1", "Wallet 1", 2, 3, CustodyPolicy.PersonalCustody, "tb1qvault", "", listOf("AAAA0000", "BBBB0000", "CCCC0000"))
        val send = VaultSend(
            vault,
            PendingPayments(FakePlainFile()),
            worker = Dispatchers.Default,
            typicalVsize = { _, _ -> 201uL },
            prepareSend = { _, _, _, _, _ -> VaultPsbt("", "ab".repeat(32), "", 0uL, 0uL, 0uL, 0u, 0uL, emptyList()) },
            signPsbt = { _, _, held, key, _, _ ->
                Thread.sleep(50)
                held.copy(signedKeys = (held.signedKeys + key).distinct().sorted())
            },
        )
        assertTrue(send.prepare())
        val first = async(Dispatchers.Default) { send.sign(KeySigner.Phone(0, emptyList(), "")) { true } }
        val second = async(Dispatchers.Default) { send.sign(KeySigner.Phone(1, emptyList(), "")) { true } }
        assertTrue(first.await() && second.await())
        assertEquals(setOf("AAAA0000", "BBBB0000"), send.signedFingerprints)
    }
}
