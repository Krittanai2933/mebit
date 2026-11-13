package com.mebit.app.data

import com.mebit.ffi.signer.AddressType
import com.mebit.ffi.signer.PairingCodes
import com.mebit.ffi.signer.WalletKind
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FakeDeviceTest {
    /** Answers with a code and counts the asks. */
    private class Codes : PairingCodes {
        var asked = 0

        override suspend fun pairingCode(): String {
            asked++
            return "123456"
        }
    }

    @Test
    fun theFakeHoldsThePublicPhraseAtEveryPathTheAppReads(): Unit = runBlocking {
        val fake = FakeDevice()
        val link = HardwareLink(FakePairings(), fake::open)
        val jade = fake.scan(WalletKind.JADE).single()
        for (type in AddressType.entries) {
            val key = link.use(jade, oneKeyPath(type), expect = PUBLIC_FINGERPRINT, NO_CODES) { it.key }
            assertEquals(PUBLIC_ACCOUNTS.getValue(type).tpub, key.xpub, "$type")
        }
        val multisig = link.use(jade, TESTNET_MULTISIG_ACCOUNT, expect = null, NO_CODES) { it.key }
        assertEquals(PUBLIC_FINGERPRINT, multisig.masterFingerprint, "Add Key's key, read through the core from the same phrase")
        assertTrue(multisig.xpub.startsWith("tpub"))
    }

    @Test
    fun itsSafe7AsksForItsCodeOnceThenTakesThePairingThisPhoneKept(): Unit = runBlocking {
        val pairings = FakePairings()
        val fake = FakeDevice()
        val link = HardwareLink(pairings, fake::open)
        val safe7 = fake.scan(WalletKind.TREZOR_SAFE7).single()
        val codes = Codes()
        link.use(safe7, TESTNET_MULTISIG_ACCOUNT, expect = PUBLIC_FINGERPRINT, codes) { it.key }
        assertEquals(1, codes.asked, "a first pairing asks")
        assertNotNull(pairings.of(PUBLIC_FINGERPRINT), "and this phone keeps the pairing under the key's fingerprint")
        link.use(safe7, oneKeyPath(AddressType.NATIVE_SEGWIT), expect = PUBLIC_FINGERPRINT, codes) { it.key }
        assertEquals(1, codes.asked, "2.3.5: the kept pairing connects with no code")
        HardwareLink(pairings, FakeDevice()::open).use(safe7, oneKeyPath(AddressType.NATIVE_SEGWIT), expect = PUBLIC_FINGERPRINT, codes) { it.key }
        assertEquals(2, codes.asked, "2.3.6: a fake of a new run forgot this phone and asks again")
    }

    @Test
    fun aWalletOfItsKeyIsSavedWithThePhrasesAddresses(): Unit = runBlocking {
        val fake = FakeDevice()
        val hardware = HardwareWallets(SavedVaults.memoryOnly(), openLink = fake::open, scan = fake::scan)
        val key = hardware.link.use(fake.scan(WalletKind.JADE).single(), oneKeyPath(AddressType.TAPROOT), expect = PUBLIC_FINGERPRINT, NO_CODES) { it.key }
        val added = assertIs<HardwareAdd.Added>(hardware.add(WalletKind.JADE, AddressType.TAPROOT, key, emptyList()))
        assertEquals(PUBLIC_ACCOUNTS.getValue(AddressType.TAPROOT).receive0, added.wallet.address)
        assertEquals("กระเป๋า Jade", added.wallet.name)
        assertEquals(WalletKind.JADE, hardware.nearest(added.wallet).kind, "Sign finds the fake of the wallet's kind")
    }
}
