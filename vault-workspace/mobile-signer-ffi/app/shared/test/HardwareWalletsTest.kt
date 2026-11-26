package com.mebit.app.data

import com.mebit.ffi.signer.AddressType
import com.mebit.ffi.signer.BleException
import com.mebit.ffi.signer.FoundWallet
import com.mebit.ffi.signer.HwAccountKey
import com.mebit.ffi.signer.WalletShape
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
            val wallet = PUBLIC_JADE_WALLET.copy(addressType = type, xpub = account.xpub, address = account.receive0)
            assertEquals(hotAccountPath(type, KeyNetwork.TEST), wallet.path, "$type: the path the phone wallet derives from")
            val shape = walletDescribe(wallet.descriptor, KeyNetwork.TEST)
            assertEquals(1u, shape.needed.toUInt(), "$type")
            assertEquals(listOf(Triple(PUBLIC_FINGERPRINT, wallet.path, account.xpub)), shape.keys.map { Triple(it.fingerprint, it.path, it.xpub) }, "$type")
            assertEquals(account.receive0, shape.firstAddress, "$type")
            assertEquals(hotReceiveAddress(PUBLIC_WORDS_12, "", KeyNetwork.TEST, type, 0u), shape.firstAddress, "$type: the same addresses as the phone wallet of the phrase")
        }
    }

    // Native: reads the core's own paths and the first address of the mainnet fixture's xpub.
    @Test
    fun theKotlinPathsAreTheCoresOnBothNetworks() {
        for (network in KeyNetwork.entries) {
            for (type in AddressType.entries) assertEquals(hotAccountPath(type, network), oneKeyPath(type, network), "$type on $network")
        }
        // The fixture xpub was derived outside the app. Its first address is the published BIP-84 vector's, and the core derives the same address from the words.
        val main = PUBLIC_MAIN_ACCOUNTS.getValue(AddressType.NATIVE_SEGWIT)
        val descriptor = oneKeyDescriptor(AddressType.NATIVE_SEGWIT, KeyNetwork.MAIN, PUBLIC_FINGERPRINT, main.xpub)
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", walletDescribe(descriptor, KeyNetwork.MAIN).firstAddress)
        assertEquals(main.receive0, hotReceiveAddress(PUBLIC_WORDS_12, "", KeyNetwork.MAIN, AddressType.NATIVE_SEGWIT, 0u))
    }

    // Native: the core reads the descriptor of the key the fake device gives on mainnet.
    @Test
    fun aMainnetJadeWalletIsNativeSegwitAtCoinType0(): Unit = runBlocking {
        val fake = FakeDevice()
        val main = SavedVaults(null, KeyNetwork.MAIN, owners = { null })
        val hardware = HardwareWallets(main, openLink = fake::open, scan = fake::scan)
        val link = HardwareLink(null, KeyNetwork.MAIN, fake::open)
        val jade = fake.scan(WalletKind.JADE).single()
        val native = link.use(jade, oneKeyPath(AddressType.NATIVE_SEGWIT, KeyNetwork.MAIN), expect = null, NO_CODES) { it.key }
        assertEquals("m/84'/0'/0'", native.derivationPath)
        val wallet = assertIs<HardwareAdd.Added>(hardware.add(WalletKind.JADE, AddressType.NATIVE_SEGWIT, native, emptyList())).wallet
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", wallet.address)
        assertFailsWith<BleException.Refused>("the fake device holds no mainnet Taproot key") { link.use(jade, oneKeyPath(AddressType.TAPROOT, KeyNetwork.MAIN), expect = null, NO_CODES) { it.key } }
        assertFailsWith<BleException.Refused>("nor one at coin type 1'") { link.use(jade, oneKeyPath(AddressType.NATIVE_SEGWIT, KeyNetwork.TEST), expect = null, NO_CODES) { it.key } }
        val atTestnet = native.copy(derivationPath = oneKeyPath(AddressType.NATIVE_SEGWIT, KeyNetwork.TEST))
        assertIs<HardwareAdd.Unreadable>(hardware.add(WalletKind.JADE, AddressType.NATIVE_SEGWIT, atTestnet, emptyList()), "a key read at m/84'/1'/0' is not mainnet's")
        assertEquals(listOf(wallet), main.current.hardware)
    }

    // Native: the real core refuses mainnet Taproot, which the fake device never reaches.
    @Test
    fun theCoreRefusesAMainnetTaprootKey(): Unit = runBlocking {
        val main = SavedVaults(null, KeyNetwork.MAIN, owners = { null })
        val key = HwAccountKey("xpub6BgBgsespWvERF3LHQu6CnqdvfEvtMcQjYrcRzx53QJjSxarj2afYWcLteoGVky7D3UKDP9QyrLprQ3VCECoY49yfdDEHGCtMMj92pReUsQ", PUBLIC_FINGERPRINT, "m/86'/0'/0'")
        assertIs<HardwareAdd.Unreadable>(HardwareWallets(main).add(WalletKind.JADE, AddressType.TAPROOT, key, emptyList()))
        assertEquals(emptyList(), main.current.hardware)
    }

    @Test
    fun aWalletTakesTheNetworkOfItsBookAndAKeyReadAtTheOtherCoinTypeIsRefused(): Unit = runBlocking {
        val main = SavedVaults(null, KeyNetwork.MAIN, owners = { null })
        val described = mutableListOf<KeyNetwork>()
        val hardware = HardwareWallets(main, describe = { _, n ->
            described += n
            WalletShape(1u, emptyList(), PUBLIC_MAIN_ACCOUNTS.getValue(AddressType.NATIVE_SEGWIT).receive0, 0uL)
        })
        val atTestnet = HwAccountKey("xpub", PUBLIC_FINGERPRINT, oneKeyPath(AddressType.NATIVE_SEGWIT, KeyNetwork.TEST))
        assertIs<HardwareAdd.Unreadable>(hardware.add(WalletKind.JADE, AddressType.NATIVE_SEGWIT, atTestnet, emptyList()), "m/84'/1'/0' is not mainnet's path")
        val atMainnet = HwAccountKey("xpub", PUBLIC_FINGERPRINT, "m/84'/0'/0'")
        val wallet = assertIs<HardwareAdd.Added>(hardware.add(WalletKind.JADE, AddressType.NATIVE_SEGWIT, atMainnet, emptyList())).wallet
        assertEquals(KeyNetwork.MAIN, wallet.network)
        assertEquals("m/84'/0'/0'", wallet.path)
        assertEquals(listOf(KeyNetwork.MAIN), described, "the core reads it as mainnet")
        assertEquals(listOf(wallet), main.current.hardware)
    }

    @Test
    fun eachWalletOfOneKeyHasItsOwnGuardKey() {
        val wallets = AddressType.entries.map { PUBLIC_JADE_WALLET.copy(addressType = it, xpub = PUBLIC_ACCOUNTS.getValue(it).xpub) }
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
            HardwareWallet("hw_1", "กระเป๋า Jade", WalletKind.JADE, AddressType.NATIVE_SEGWIT, PUBLIC_FINGERPRINT, PUBLIC_ACCOUNTS.getValue(AddressType.NATIVE_SEGWIT).xpub, PUBLIC_ACCOUNTS.getValue(AddressType.NATIVE_SEGWIT).receive0, network = KeyNetwork.TEST),
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

        val unread = SavedVaults(FakeVaultsStorage().apply { unreadable = true }, KeyNetwork.TEST, owners = { VaultOwners(listOf(TEST_CHECK), everyPlaceOpened = true) })
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
            xpub = PUBLIC_ACCOUNTS.getValue(AddressType.TAPROOT).xpub,
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

    private fun savedBook(storage: FakeVaultsStorage) = SavedVaults(storage, KeyNetwork.TEST, owners = { VaultOwners(listOf(TEST_CHECK), everyPlaceOpened = true) }).apply { sync() }

    private fun key(type: AddressType) = HwAccountKey(PUBLIC_ACCOUNTS.getValue(type).xpub, PUBLIC_FINGERPRINT, oneKeyPath(type, KeyNetwork.TEST))

    private suspend fun HardwareWallets.added(type: AddressType) = assertIs<HardwareAdd.Added>(add(WalletKind.JADE, type, key(type), emptyList())).wallet
}
