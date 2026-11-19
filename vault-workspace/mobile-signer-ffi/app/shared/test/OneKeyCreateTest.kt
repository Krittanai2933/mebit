package com.mebit.app.data

import com.mebit.ffi.signer.AddressType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PATH_84 = "m/84'/1'/0'"

class OneKeyCreateTest {
    private val safe7Key = SavedKey("key_1_$PUBLIC_FINGERPRINT", "Trezor ในตู้เซฟ", PUBLIC_FINGERPRINT, "tpub-48", TESTNET_MULTISIG_ACCOUNT, KeySourceLabel.TrezorSafe7, verified = true)
    private val jadeKey = safe7Key.copy(id = "key_2_$PUBLIC_FINGERPRINT", label = "Jade ของฉัน", source = KeySourceLabel.Jade)

    @Test
    fun aOneKeyReadOffersTheKeptPairingReadsTheTypesPathAndKeepsThePickedKeysName(): Unit = runBlocking {
        val pairings = FakePairings().apply { write(PUBLIC_FINGERPRINT, byteArrayOf(7)) }
        val link = FakeDeviceLink()
        val store = KeyringStore(book = SavedVaults.memoryOnly(), pairings = pairings, openLink = { link })
        assertTrue(store.keepsPairing(PUBLIC_FINGERPRINT))

        assertEquals(EnrollResult.Enrolled, store.enrollHardware(SAFE7, PATH_84, expect = safe7Key))
        assertTrue(store.pairingKept, "2.3.5: this phone offered the pairing it kept")
        assertFalse(store.pairingAsked, "and the Safe 7 took it, so no code field")
        assertContentEquals(byteArrayOf(7), link.offeredAtConnect)
        assertEquals(listOf("connect", "unlock", "accountXpub $PATH_84", "disconnect", "close"), link.calls)
        val staged = assertNotNull(store.pending)
        assertEquals(listOf("Trezor ในตู้เซฟ", PATH_84, PUBLIC_FINGERPRINT), listOf(staged.label, staged.derivationPath, staged.fingerprint))
    }

    @Test
    fun aSafe7ThatForgotThisPhoneAsksForItsCodeAndItsNewPairingTakesThePlaceOfTheOld(): Unit = runBlocking {
        val pairings = FakePairings().apply { write(PUBLIC_FINGERPRINT, byteArrayOf(7)) }
        val store = KeyringStore(book = SavedVaults.memoryOnly(), pairings = pairings, openLink = { FakeDeviceLink(fresh = byteArrayOf(9), asksCode = true) })
        val typing = launch {
            while (!store.pairingAsked) delay(10)
            assertTrue(store.pairingKept, "asked while a kept pairing was offered: 2.3.6")
            store.pairingCode = "654321"
        }
        assertEquals(EnrollResult.Enrolled, withTimeout(5_000) { store.enrollHardware(SAFE7, PATH_84, expect = safe7Key) })
        typing.join()
        assertContentEquals(byteArrayOf(9), pairings.of(PUBLIC_FINGERPRINT))
    }

    @Test
    fun aFirstPairingOffersNothingAndAJadeIsNeverOfferedAPairing(): Unit = runBlocking {
        val pairings = FakePairings()
        val store = KeyringStore(book = SavedVaults.memoryOnly(), pairings = pairings, openLink = { FakeDeviceLink() })
        assertEquals(EnrollResult.Enrolled, store.enrollHardware(SAFE7, PATH_84, expect = safe7Key))
        assertFalse(store.pairingKept, "2.3.4: nothing kept for this key yet")

        pairings.write(PUBLIC_FINGERPRINT, byteArrayOf(7))
        assertEquals(EnrollResult.Enrolled, store.enrollHardware(JADE_FOUND, PATH_84, expect = jadeKey))
        assertFalse(store.pairingKept, "a Jade has no pairing to keep")
    }

    @Test
    fun aDeviceHoldingAnotherKeyThanThePickedOneIsRefusedInT14sWords(): Unit = runBlocking {
        val store = KeyringStore(book = SavedVaults.memoryOnly(), openLink = { FakeDeviceLink(fingerprint = "aaaa0001") })
        assertEquals(EnrollResult.Failed, store.enrollHardware(JADE_FOUND, PATH_84, expect = jadeKey))
        assertEquals("Fingerprint ไม่ตรง", store.lastError)
        assertNull(store.pending)
    }

    @Test
    fun chooseKeyOffersOnlyConfirmedKeysThatADeviceHolds() {
        val phone = jadeKey.copy(id = "onboard_x", label = "มือถือ · กระเป๋าใช้จ่าย", source = KeySourceLabel.ThisDevice)
        val other = jadeKey.copy(id = "key_3", source = KeySourceLabel.MebitOther)
        val unconfirmed = jadeKey.copy(id = "key_4", verified = false)
        val demo = jadeKey.copy(id = "key_5", label = "Jade · จำลอง", isMock = true)
        assertEquals(listOf(jadeKey, safe7Key), oneKeyChoices(listOf(phone, other, unconfirmed, demo, jadeKey, safe7Key, jadeKey)))
    }

    @Test
    fun theDraftsPickIsThisPhoneUntilADevicesKeyIsTappedAndForgetsAKeyGoneSince() {
        val draft = CreateWalletDraft()
        val keys = listOf(jadeKey, safe7Key)
        assertNull(draft.device(keys), "3.2.1: this phone first")
        assertEquals(AddressType.NATIVE_SEGWIT, draft.addressType, "3.3.2: Native SegWit until another is tapped")
        draft.deviceKey = safe7Key.id
        assertEquals(safe7Key, draft.device(keys))
        assertNull(draft.device(listOf(jadeKey)), "the key left the Keyring")
    }

    @Test
    fun confirmKeyRenamesTheSavedKeyAndNothingElse() {
        val book = SavedVaults.memoryOnly()
        book.edit { it.copy(keys = listOf(jadeKey, safe7Key)) }
        val store = KeyringStore(book = book)
        assertTrue(store.rename(jadeKey.id, "Jade บนโต๊ะ"))
        assertEquals(listOf("Jade บนโต๊ะ", "Trezor ในตู้เซฟ"), book.current.keys.map { it.label })
    }

    @Test
    fun aHardwareWalletsKeyIsNamedAsTheKeyringNamesItsDevicesKey() {
        val keys = listOf(safe7Key, jadeKey)
        assertEquals("Jade ของฉัน", hardwareKeyName(PUBLIC_JADE_WALLET, keys))
        assertEquals("Trezor ในตู้เซฟ", hardwareKeyName(PUBLIC_JADE_WALLET.copy(kind = com.mebit.ffi.signer.WalletKind.TREZOR_SAFE7), keys))
        assertEquals("Jade ของฉัน", hardwareKeyName(PUBLIC_JADE_WALLET, emptyList()), "a key gone from the Keyring takes the name a Jade's key starts with")
    }
}
