package com.mebit.app.data

import com.mebit.ffi.signer.AddressType
import com.mebit.ffi.signer.BleException
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.PairingCodes
import com.mebit.ffi.signer.WalletKind
import com.mebit.ffi.signer.restoreSoftwareMultisigKey
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    /** A 2-of-3 of the public phrase's key and two keys of the same phrase under other passphrases, so the fake holds one key of it. */
    private fun twoOfThree(): String = listOf("", "one", "two").joinToString(",", "wsh(sortedmulti(2,", "))") { passphrase ->
        val key = restoreSoftwareMultisigKey(PUBLIC_WORDS_12, passphrase, KeyNetwork.TEST, 0u)
        "[${key.masterFingerprint}/48'/1'/0'/2']${key.xpub}"
    }

    /** A device of [fake] of [kind], linked anew, as each signature links it. */
    private suspend fun FakeDevice.link(kind: WalletKind) = open(scan(kind).single())

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
        assertEquals(WalletKind.JADE, hardware.nearest(added.wallet.kind).kind, "Sign finds the fake of the wallet's kind")
    }

    @Test
    fun itsJadeRegistersASeveralKeyWalletOncePerLaunchAndTellsTheSteps(): Unit = runBlocking {
        val wallet = twoOfThree()
        val fake = FakeDevice(List(4) { FakeBehaviour.Decline }, pause = {})
        val jade = HeardSteps()
        repeat(2) { assertFailsWith<BleException.Refused> { fake.link(WalletKind.JADE).signWalletPsbt(wallet, "psbt", "txid", jade) } }
        assertEquals(listOf("registering", "showing", "showing"), jade.steps, "registered at its first signature only")
        val safe7 = HeardSteps()
        assertFailsWith<BleException.Refused> { fake.link(WalletKind.TREZOR_SAFE7).signWalletPsbt(wallet, "psbt", "txid", safe7) }
        assertEquals(listOf("showing"), safe7.steps, "a Safe 7 registers nothing")
        val oneKey = HeardSteps()
        assertFailsWith<BleException.Refused> { fake.link(WalletKind.JADE).signWalletPsbt(PUBLIC_JADE_WALLET.descriptor, "psbt", "txid", oneKey) }
        assertEquals(listOf("showing"), oneKey.steps, "nor does a one-key wallet")
        val relaunched = HeardSteps()
        val next = FakeDevice(listOf(FakeBehaviour.Decline), pause = {})
        assertFailsWith<BleException.Refused> { next.link(WalletKind.JADE).signWalletPsbt(wallet, "psbt", "txid", relaunched) }
        assertEquals(listOf("registering", "showing"), relaunched.steps, "a relaunch forgot the wallet, as a wiped Jade would")
    }

    @Test
    fun aDeclinedRegistrationIsNotKeptAndADropComesWhileThePaymentShows(): Unit = runBlocking {
        val wallet = twoOfThree()
        val fake = FakeDevice(listOf(FakeBehaviour.RegistrationDeclined, FakeBehaviour.Drop), pause = {})
        val declined = HeardSteps()
        assertFailsWith<BleException.Refused> { fake.link(WalletKind.JADE).signWalletPsbt(wallet, "psbt", "txid", declined) }
        assertEquals(listOf("registering"), declined.steps)
        val dropped = HeardSteps()
        assertFailsWith<BleException.Disconnected> { fake.link(WalletKind.JADE).signWalletPsbt(wallet, "psbt", "txid", dropped) }
        assertEquals(listOf("registering", "showing"), dropped.steps, "the declined registration was not kept")
    }

    @Test
    fun theLaunchSwitchNamesTheBehavioursInOrderAndSkipsATypo() {
        assertEquals(
            listOf(FakeBehaviour.Decline, FakeBehaviour.RegistrationDeclined, FakeBehaviour.WrongAnswer, FakeBehaviour.Slow, FakeBehaviour.Drop, FakeBehaviour.Sign),
            fakeScript("decline, Registration_Declined,wrong-answer,SLOW,drop,sign"),
        )
        assertEquals(listOf(FakeBehaviour.Drop), fakeScript("dorp,drop"))
        assertEquals(emptyList(), fakeScript(null))
    }
}
