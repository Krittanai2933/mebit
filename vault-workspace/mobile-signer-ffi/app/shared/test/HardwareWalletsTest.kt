package com.mebit.app.data

import com.mebit.ffi.signer.AddressType
import com.mebit.ffi.signer.BleException
import com.mebit.ffi.signer.FoundWallet
import com.mebit.ffi.signer.HwAccountKey
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.WalletKind
import com.mebit.ffi.signer.hotAccountPath
import com.mebit.ffi.signer.hotReceiveAddress
import com.mebit.ffi.signer.walletDescribe
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HardwareWalletsTest {
    @Test
    fun nearestTakesTheOnlyDeviceOfItsKindAndRefusesNoneOrSeveral(): Unit = runBlocking {
        val jade = FoundWallet(WalletKind.JADE, "a", "Jade a")
        var inRange = listOf(jade)
        val hardware = HardwareWallets(SavedVaults.memoryOnly(), scan = { inRange })
        assertEquals(jade, hardware.nearest(WalletKind.JADE))
        inRange = listOf(jade, FoundWallet(WalletKind.JADE, "b", "Jade b"))
        assertFailsWith<SeveralDevices>("a PIN typed on the wrong Jade would be wasted") { hardware.nearest(WalletKind.JADE) }
        inRange = emptyList()
        assertFailsWith<BleException.NotFound> { hardware.nearest(WalletKind.JADE) }
    }

    @Test
    fun everyAddressTypeIsADescriptorTheCoreReadsAsThePhoneWalletOfThatPhrase() {
        for (type in AddressType.entries) {
            val account = PUBLIC_ACCOUNTS.getValue(type)
            val wallet = PUBLIC_JADE_WALLET.copy(addressType = type, xpub = account.tpub, address = account.receive0)
            assertEquals(hotAccountPath(type), wallet.path, "$type: the path the phone wallet derives from")
            val shape = walletDescribe(wallet.descriptor, KeyNetwork.TEST)
            assertEquals(1u, shape.needed.toUInt(), "$type")
            assertEquals(listOf(Triple(PUBLIC_FINGERPRINT, wallet.path, account.tpub)), shape.keys.map { Triple(it.fingerprint, it.path, it.xpub) }, "$type")
            assertEquals(account.receive0, shape.firstAddress, "$type")
            assertEquals(hotReceiveAddress(PUBLIC_WORDS_12, "", KeyNetwork.TEST, type, 0u), shape.firstAddress, "$type: the same addresses as the phone wallet of the phrase")
        }
    }

    @Test
    fun eachWalletOfOneKeyHasItsOwnGuardKey() {
        val wallets = AddressType.entries.map { PUBLIC_JADE_WALLET.copy(addressType = it, xpub = PUBLIC_ACCOUNTS.getValue(it).tpub) }
        val keys = wallets.map { it.guardKey }
        assertTrue(keys.all { Regex("[0-9a-f]{8}").matches(it) }, "the shape a phone wallet's line has: $keys")
        assertEquals(keys.size, keys.toSet().size, "four address types, four guards")
        assertEquals(keys, wallets.map { it.copy(id = "hw_9", name = "other", kind = WalletKind.TREZOR_SAFE7).guardKey }, "the key's descriptor alone names the guard")
    }

    @Test
    fun aDeviceKeyIsSavedOnceTheCoreReadsItsDescriptorBack(): Unit = runBlocking {
        val book = SavedVaults.memoryOnly()
        val added = assertIs<HardwareAdd.Added>(HardwareWallets(book).add(WalletKind.JADE, AddressType.NATIVE_SEGWIT, key(AddressType.NATIVE_SEGWIT), phones = emptyList()))
        assertEquals(
            HardwareWallet("hw_1", "กระเป๋า Jade", WalletKind.JADE, AddressType.NATIVE_SEGWIT, PUBLIC_FINGERPRINT, PUBLIC_ACCOUNTS.getValue(AddressType.NATIVE_SEGWIT).tpub, PUBLIC_ACCOUNTS.getValue(AddressType.NATIVE_SEGWIT).receive0),
            added.wallet,
        )
        assertEquals(listOf(added.wallet), book.current.hardware)
    }

    @Test
    fun namesFollowTheKindAndNoNameOrNumberRepeatsASavedOne(): Unit = runBlocking {
        val book = SavedVaults.memoryOnly()
        val hardware = HardwareWallets(book)
        suspend fun add(kind: WalletKind, type: AddressType) = assertIs<HardwareAdd.Added>(hardware.add(kind, type, key(type), emptyList())).wallet.let { it.id to it.name }

        assertEquals("hw_1" to "กระเป๋า Jade", add(WalletKind.JADE, AddressType.NATIVE_SEGWIT))
        assertEquals("hw_2" to "กระเป๋า Trezor", add(WalletKind.TREZOR_SAFE7, AddressType.TAPROOT))
        assertEquals("hw_3" to "กระเป๋า Jade 2", add(WalletKind.JADE, AddressType.LEGACY))
        assertTrue(book.editSaved { b -> b.copy(hardware = b.hardware.filterNot { it.id == "hw_1" }) })
        assertEquals("hw_4" to "กระเป๋า Jade 3", add(WalletKind.JADE, AddressType.NESTED_SEGWIT), "the first Jade's name is gone, but a higher one is saved")
        assertEquals(listOf("hw_2", "hw_3", "hw_4"), book.current.hardware.map { it.id }, "in the order they were made")
    }

    @Test
    fun aRemovedWalletsNumberIsNeverGivenAgainNotAfterARestartEither(): Unit = runBlocking {
        val storage = FakeVaultsStorage()
        val hardware = HardwareWallets(savedBook(storage))
        assertEquals("hw_1", hardware.added(AddressType.NATIVE_SEGWIT).id)
        val newest = hardware.added(AddressType.TAPROOT)
        assertEquals(HardwareRemoval.Removed, hardware.remove(newest.id) { true })

        val next = hardware.added(AddressType.LEGACY)
        assertEquals("hw_3", next.id, "${newest.id} was removed, and its number is never given again")
        assertEquals(newest.name, next.name, "a name may repeat a removed wallet's, never a saved one's")
        assertEquals(HardwareRemoval.Removed, hardware.remove(next.id) { true })
        assertEquals("hw_4", HardwareWallets(savedBook(storage)).added(AddressType.NESTED_SEGWIT).id, "a new process reads the highest number from the stored book")
    }

    @Test
    fun theSameAddressesAlreadyOnThePhoneAreRefusedWithTheirWalletsName(): Unit = runBlocking {
        val book = SavedVaults.memoryOnly()
        val hardware = HardwareWallets(book)
        val jade = assertIs<HardwareAdd.Added>(hardware.add(WalletKind.JADE, AddressType.NATIVE_SEGWIT, key(AddressType.NATIVE_SEGWIT), emptyList())).wallet

        assertEquals(HardwareAdd.Duplicate(jade.name), hardware.add(WalletKind.TREZOR_SAFE7, AddressType.NATIVE_SEGWIT, key(AddressType.NATIVE_SEGWIT), emptyList()), "a Safe 7 holding the Jade's phrase")
        val phone = PhoneWallet("กระเป๋า 2", PUBLIC_FINGERPRINT.uppercase(), AddressType.TAPROOT)
        assertEquals(HardwareAdd.Duplicate("กระเป๋า 2"), hardware.add(WalletKind.JADE, AddressType.TAPROOT, key(AddressType.TAPROOT), listOf(phone)), "the phone wallet of that phrase and type")
        assertEquals(listOf(jade), book.current.hardware, "neither was saved")

        assertIs<HardwareAdd.Added>(hardware.add(WalletKind.JADE, AddressType.LEGACY, key(AddressType.LEGACY), listOf(phone)), "the phrase in another address type has other addresses")
    }

    @Test
    fun aKeyTheCoreCannotReadOrABookThatCannotBeSavedSavesNothing(): Unit = runBlocking {
        val book = SavedVaults.memoryOnly()
        val bip48 = HwAccountKey("tpubDFH9dgzveyD8zTbPUFuLrGmCydNvxehyNdUXKJAQN8x4aZ4j6UZqGfnqFrD4NqyaTVGKbvEW54tsvPTK2UoSbCC1PJY8iCNiwTL3RWZEheQ", PUBLIC_FINGERPRINT, "m/48'/1'/0'/2'")
        assertIs<HardwareAdd.Unreadable>(HardwareWallets(book).add(WalletKind.JADE, AddressType.NATIVE_SEGWIT, bip48, emptyList()), "a several-key account is not a BIP-84 one")
        assertIs<HardwareAdd.Unreadable>(
            HardwareWallets(book).add(WalletKind.JADE, AddressType.TAPROOT, key(AddressType.NATIVE_SEGWIT), emptyList()),
            "the core would read the 84' account under 86', and the device could never sign for that wallet",
        )
        assertEquals(VaultBook(), book.current)

        val unread = SavedVaults(FakeVaultsStorage().apply { unreadable = true }, owners = { VaultOwners(listOf(TEST_CHECK), everyPlaceOpened = true) })
        assertEquals(HardwareAdd.NotSaved, HardwareWallets(unread).add(WalletKind.JADE, AddressType.NATIVE_SEGWIT, key(AddressType.NATIVE_SEGWIT), emptyList()))
        assertEquals(VaultBook(), unread.current)
    }

    @Test
    fun aRemovalAsksTheOwnerSavesForGoodAndTakesThePairingWithTheLastWalletOfItsKey(): Unit = runBlocking {
        val storage = FakeVaultsStorage()
        val book = savedBook(storage)
        val taproot = PUBLIC_JADE_WALLET.copy(
            id = "hw_2",
            kind = WalletKind.TREZOR_SAFE7,
            addressType = AddressType.TAPROOT,
            xpub = PUBLIC_ACCOUNTS.getValue(AddressType.TAPROOT).tpub,
            address = PUBLIC_ACCOUNTS.getValue(AddressType.TAPROOT).receive0,
        )
        book.edit { it.copy(hardware = listOf(PUBLIC_JADE_WALLET, taproot)) }
        val pairings = FakePairings().apply { write(PUBLIC_FINGERPRINT, byteArrayOf(7)) }
        val hardware = HardwareWallets(book, pairings)
        var asked = 0

        assertEquals(HardwareRemoval.Refused, hardware.remove("hw_2") { asked++; false })
        assertEquals(listOf(PUBLIC_JADE_WALLET, taproot), book.current.hardware, "a refused owner check removes nothing")

        assertEquals(HardwareRemoval.Removed, hardware.remove("hw_2") { asked++; true })
        assertEquals(listOf(PUBLIC_JADE_WALLET), savedBook(storage).current.hardware, "saved for good")
        assertNotNull(pairings.of(PUBLIC_FINGERPRINT), "the Jade wallet of the same key keeps the pairing")

        assertEquals(HardwareRemoval.Removed, hardware.remove("hw_1") { asked++; true })
        assertNull(pairings.of(PUBLIC_FINGERPRINT), "the last wallet of the key took it")
        assertEquals(3, asked)
        assertEquals(HardwareRemoval.Removed, hardware.remove("hw_1") { error("a removal of a wallet that is gone asks nothing") })
    }

    @Test
    fun nothingIsRemovedWhileTheBookCannotBeSavedAndNoOwnerCheckIsAskedWhileItCannotBeJudged(): Unit = runBlocking {
        val storage = FakeVaultsStorage()
        val book = savedBook(storage).apply { edit { it.copy(hardware = listOf(PUBLIC_JADE_WALLET)) } }
        storage.failWrite = true
        assertEquals(HardwareRemoval.NotSaved, HardwareWallets(book).remove("hw_1") { true })
        assertEquals(listOf(PUBLIC_JADE_WALLET), book.current.hardware, "a write that failed keeps the wallet")

        val damaged = savedBook(FakeVaultsStorage("not a book")).apply { edit { it.copy(hardware = listOf(PUBLIC_JADE_WALLET)) } }
        assertEquals(HardwareRemoval.NotSaved, HardwareWallets(damaged).remove("hw_1") { error("no owner check while the book cannot be judged") })
        assertEquals(listOf(PUBLIC_JADE_WALLET), damaged.current.hardware)
    }

    private fun savedBook(storage: FakeVaultsStorage) = SavedVaults(storage, owners = { VaultOwners(listOf(TEST_CHECK), everyPlaceOpened = true) }).apply { sync() }

    private fun key(type: AddressType) = HwAccountKey(PUBLIC_ACCOUNTS.getValue(type).tpub, PUBLIC_FINGERPRINT, oneKeyPath(type))

    private suspend fun HardwareWallets.added(type: AddressType) = assertIs<HardwareAdd.Added>(add(WalletKind.JADE, type, key(type), emptyList())).wallet
}
