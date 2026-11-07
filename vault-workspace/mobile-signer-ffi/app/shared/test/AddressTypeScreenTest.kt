package com.mebit.app.feature.onboarding

import com.mebit.app.data.FakeSecureStore
import com.mebit.app.data.OnboardingSession
import com.mebit.app.data.PUBLIC_WORDS_24
import com.mebit.app.data.SavedWallet
import com.mebit.app.data.TEST_CHECK
import com.mebit.app.data.UnlockResult
import com.mebit.app.data.openNow
import com.mebit.ffi.signer.AddressType
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.SoftwareAccountKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Penpot 1.5.1's rows and eyebrow. */
class AddressTypeScreenTest {
    @Test
    fun theRowsAreEveryTypeInPenpotsOrder() {
        assertEquals(
            listOf(AddressType.LEGACY, AddressType.NESTED_SEGWIT, AddressType.NATIVE_SEGWIT, AddressType.TAPROOT),
            AddressTypeRows.map { it.type },
        )
        assertEquals(listOf("Legacy", "Nested SegWit", "Native SegWit", "Taproot"), AddressTypeRows.map { it.label })
    }

    // The detail line names the prefixes of the network the wallet runs on, so it agrees with the address Receive shows.
    @Test
    fun eachRowsPrefixesFollowTheNetwork() {
        assertEquals(
            listOf("BIP44 · เริ่มด้วย m หรือ n", "BIP49 · เริ่มด้วย 2", "BIP84 · เริ่มด้วย tb1q", "BIP86 · เริ่มด้วย tb1p"),
            AddressTypeRows.map { it.detail(KeyNetwork.TEST) },
        )
        assertEquals(
            listOf("BIP44 · เริ่มด้วย 1", "BIP49 · เริ่มด้วย 3", "BIP84 · เริ่มด้วย bc1q", "BIP86 · เริ่มด้วย bc1p"),
            AddressTypeRows.map { it.detail(KeyNetwork.MAIN) },
        )
    }

    // Mainnet takes Native SegWit alone, as the core's accounts refuse the rest, so only its row is enabled there. Testnet takes all four.
    @Test
    fun onMainnetOnlyNativeSegwitsRowIsEnabled() {
        assertEquals(listOf(true, true, true, true), AddressTypeRows.map { it.enabled(KeyNetwork.TEST) })
        assertEquals(listOf(false, false, true, false), AddressTypeRows.map { it.enabled(KeyNetwork.MAIN) })
    }

    @Test
    fun theEyebrowNamesTheFlow() {
        assertEquals("NEW WALLET · ADDRESS TYPE", addressTypeEyebrow(imported = false))
        assertEquals("IMPORT WALLET · ADDRESS TYPE", addressTypeEyebrow(imported = true))
    }

    // The Create fingerprint sheet (1.5.4) follows saved facts, a Create whose passphrase words were verified, so the screen a restart
    // brings back asks it too. An import, or a key with no passphrase, never does.
    @Test
    fun theCreateFingerprintIsDueForAVerifiedPassphraseCreateInTheRunAndAfterARestart() {
        val store = FakeSecureStore()
        val created = session(store)
        created.generateThisDevice("a passphrase")
        assertFalse(createFingerprintDue(created), "before Verify Words")
        created.markBackupConfirmed()
        assertTrue(createFingerprintDue(created), "after Verify Words")
        val opened = assertIs<UnlockResult.Opened>(SavedWallet(store).openNow())
        val restarted = session(FakeSecureStore())
        assertTrue(restarted.install(opened.record, opened.version), "install")
        assertTrue(createFingerprintDue(restarted), "after a restart")
        val plain = session(FakeSecureStore()).apply {
            generateThisDevice()
            markBackupConfirmed()
        }
        assertFalse(createFingerprintDue(plain), "no passphrase")
        val imported = session(FakeSecureStore()).apply { restoreFromWords(PUBLIC_WORDS_24, "a passphrase", accepted = false) }
        assertFalse(createFingerprintDue(imported), "an import")
    }

    private fun session(store: FakeSecureStore) = OnboardingSession(
        SavedWallet(store),
        newKey = { p, _, _ -> key(p) },
        restoreKey = { _, p, _, _ -> key(p) },
        forgetScans = {},
    )

    private fun key(passphrase: String) =
        SoftwareAccountKey(PUBLIC_WORDS_24, if (passphrase.isEmpty()) TEST_CHECK else "5a5a5a5a", "tpub-test", "m/84'/1'/0'")
}
