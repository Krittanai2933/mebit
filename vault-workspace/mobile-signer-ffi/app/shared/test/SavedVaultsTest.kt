package com.mebit.app.data

import com.mebit.app.EraseResult
import com.mebit.app.Place
import com.mebit.app.VaultsStorage
import com.mebit.app.Why
import com.mebit.app.WriteMode
import androidx.compose.runtime.snapshots.Snapshot
import com.mebit.app.navigation.Boot
import com.mebit.app.navigation.WalletGateState
import com.mebit.ffi.signer.AddressType
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.SoftwareAccountKey
import com.mebit.ffi.signer.WalletShape
import com.mebit.ffi.signer.generateSoftwareMultisigKey
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SavedVaultsTest {
    @Test
    fun aRestartBringsBackTheKeysAndVaults() {
        val storage = FakeVaultsStorage()
        val first = SavedVaults(storage, owners = one { TEST_CHECK }) // the real derivation, so what CreateWalletDraft writes must pass the check at the next start
        first.sync()
        val keyring = KeyringStore(book = first)
        val draft = CreateWalletDraft(first)
        val third = generateSoftwareMultisigKey("", KeyNetwork.TEST, 3u)
        enrol(keyring, "abcd0123", tpubA)
        enrol(keyring, "00ff00aa", tpubB)
        enrol(keyring, third.masterFingerprint, third.xpub, third.derivationPath)
        keyring.keys.forEach { draft.toggle(it.id, keyring.keys) }
        val made = assertNotNull(draft.create(assertNotNull(draft.ready(keyring.keys))))
        assertEquals("Wallet 1", made.name)

        val second = SavedVaults(storage, owners = one { TEST_CHECK })
        second.sync()
        assertEquals(VaultsHealth.Ok, second.health)
        assertEquals(first.current, second.current)
        assertEquals(listOf("abcd0123", "00ff00aa", third.masterFingerprint), KeyringStore(book = second).keys.map { it.fingerprint })
        assertEquals(made.address, VaultDraft(second).created.single().address)
    }

    @Test
    fun aDamagedStoreOpensTheWalletAndIsLeftExactlyAsItWas(): Unit = runBlocking {
        val store = FakeSecureStore()
        assertIs<SaveResult.Written>(SavedWallet(store).save(testRecord(), WriteMode.Create))
        val storage = FakeVaultsStorage("not a book")
        val wallets = Wallets(OnePlaceShelf(store), restoreKey = { words, _, _, _ -> SoftwareAccountKey(words, TEST_CHECK, "tpub-own", PATH) }, forgetScans = {})
        val session = wallets.at(Place.FIRST).session
        val vaults = SavedVaults(storage, owners = { wallets.vaultOwners() }, deriveAddress = FAKE_DERIVE)
        val gate = WalletGateState(wallets)
        assertEquals(Boot.Locked, gate.boot)

        gate.unlock("test")
        assertIs<Boot.Open>(gate.boot, "the wallet opens")
        assertEquals(TEST_CHECK, session.fingerprint)

        vaults.sync()
        assertEquals(VaultsHealth.Damaged, vaults.health)
        assertEquals(VaultBook(), vaults.current)
        assertNotNull(vaultsNoticeText(vaults.health), "the line on the two screens")

        vaults.edit(added(JADE))
        assertEquals(listOf(JADE), vaults.current.keys, "this run stays in memory")
        assertEquals(VaultsHealth.Damaged, vaults.health)
        assertEquals("not a book", storage.text)
        assertEquals(0, storage.writes)
        assertEquals(0, storage.deletes)
    }

    @Test
    fun theBookNeverReadsWritesOrErasesTheWalletStore() {
        val store = FakeSecureStore()
        val session = session(SavedWallet(store))
        session.generateThisDevice()
        val before = store.activity()

        val storage = FakeVaultsStorage()
        val vaults = SavedVaults(storage, owners = one { session.vaultsOwner() }, deriveAddress = FAKE_DERIVE)
        vaults.sync()
        vaults.edit(added(JADE))
        vaults.edit { it.copy(vaults = it.vaults + VAULT) }
        vaults.sync()
        assertTrue(storage.writes > 0, "the book was saved, or this proves nothing")
        val restart = SavedVaults(storage, owners = one { session.vaultsOwner() }, deriveAddress = FAKE_DERIVE)
        restart.sync()
        assertEquals(vaults.current, restart.current)
        restart.edit { it.copy(keys = emptyList()) }
        storage.delete()

        assertEquals(before, store.activity())
    }

    @Test
    fun everyCutOfAGoodTextIsDamagedNotOpened() {
        // The two wallet numbers cut off whole leave the MV2 text of a build before them, which opens.
        val older = setOf(GOLDEN.removeSuffix("1 0\n1 0\n"), GOLDEN_HW_36C71CF)
        for (golden in listOf(GOLDEN, GOLDEN_HW, GOLDEN_MV1)) {
            for (cut in 1 until golden.length) {
                val text = golden.substring(0, cut)
                if (text in older) continue
                val storage = FakeVaultsStorage(text)
                val vaults = opened(storage)
                assertEquals(VaultsHealth.Damaged, vaults.health, "cut at $cut")
                assertEquals(VaultBook(), vaults.current, "cut at $cut")
                vaults.edit(added(JADE))
                assertEquals(text, storage.text, "cut at $cut")
                assertEquals(0, storage.writes, "cut at $cut")
            }
        }
    }

    @Test
    fun aTextThatEncodeNeverWritesIsDamaged() {
        val damaged = listOf(
            "leftover" to GOLDEN + "1 1\n",
            "version zero" to GOLDEN.replaceFirst("MV2", "MV0"),
            "not canonical" to GOLDEN.replaceFirst("8 0a1b", "08 0a1b"),
            "a count that is not canonical" to GOLDEN.replaceFirst("1 1\n", "2 01\n"),
            "a flag that is neither" to GOLDEN.replaceFirst("1 2\n1 0\n", "1 2\n1 7\n"),
            "m above n" to GOLDEN.replaceFirst("1 1\n1 1\n1 0\n13 addr-1", "1 2\n1 1\n1 0\n13 addr-2"),
            "fewer fingerprints than n" to GOLDEN.replaceFirst("1 1\n1 1\n8 abcd0123\n", "1 1\n1 0\n"),
            "key ids neither none nor n" to GOLDEN_HW.replaceFirst("1 1\n14 key_1_abcd0123\n", "1 2\n14 key_1_abcd0123\n14 key_1_abcd0123\n"),
            "a hardware fingerprint that is not 8 lowercase hex" to GOLDEN_HW.replaceFirst("8 $PUBLIC_FINGERPRINT\n", "8 ${PUBLIC_FINGERPRINT.uppercase()}\n"),
            "an MV1 text that carries MV2 fields" to GOLDEN_HW.replaceFirst("MV2", "MV1"),
            "one wallet number of two" to GOLDEN.removeSuffix("1 0\n"),
            "over 1 MiB" to "MV2\n" + "x".repeat(1_048_576),
        )
        for ((case, text) in damaged) {
            val storage = FakeVaultsStorage(text)
            val vaults = opened(storage)
            assertEquals(VaultsHealth.Damaged, vaults.health, case)
            assertEquals(VaultBook(), vaults.current, case)
            assertEquals(text, storage.text, case)
            assertEquals(0, storage.writes, case)
        }
    }

    @Test
    fun aNewerTextIsNeverOpenedOrOverwritten() {
        val newer = listOf(
            "version 3" to GOLDEN.replaceFirst("MV2", "MV3"),
            "a version too long for an int" to GOLDEN.replaceFirst("MV2", "MV99999999999"),
            "an unknown source" to GOLDEN.replaceFirst("1 2\n", "1 9\n"),
            "an unknown policy" to GOLDEN.replaceFirst("1 0\n13", "1 9\n13"),
            "an unknown source in MV1" to GOLDEN_MV1.replaceFirst("1 2\n", "1 9\n"),
            "an unknown device" to GOLDEN_HW.replaceFirst("1 2\n1 0\n8 $PUBLIC_FINGERPRINT", "1 9\n1 0\n8 $PUBLIC_FINGERPRINT"),
            "an unknown address type" to GOLDEN_HW.replaceFirst("1 2\n1 0\n8 $PUBLIC_FINGERPRINT", "1 2\n1 9\n8 $PUBLIC_FINGERPRINT"),
        )
        for ((case, text) in newer) {
            val storage = FakeVaultsStorage(text)
            val vaults = opened(storage)
            assertEquals(VaultsHealth.Newer, vaults.health, case)
            assertEquals(VaultBook(), vaults.current, case)
            vaults.edit(added(JADE))
            assertEquals(text, storage.text, case)
            assertEquals(0, storage.writes, case)
        }
    }

    @Test
    fun anAddressThatDoesNotMatchItsKeysIsRefused() {
        val derives = listOf<Pair<String, (List<String>, UByte) -> String>>(
            "another address" to { _, _ -> "someone else's" },
            "a derivation that throws" to { _, _ -> throw IllegalArgumentException("bad key") },
        )
        for ((case, derive) in derives) {
            val storage = FakeVaultsStorage(GOLDEN)
            val vaults = SavedVaults(storage, owners = one { TEST_CHECK }, deriveAddress = derive)
            vaults.sync()
            assertEquals(VaultsHealth.Damaged, vaults.health, case)
            assertEquals(VaultBook(), vaults.current, case)
            assertEquals(GOLDEN, storage.text, case)
        }
    }

    @Test
    fun aForgetDeletesTheBookAfterTheEraseAndAFailedEraseKeepsIt() {
        val storage = FakeVaultsStorage(GOLDEN)
        val store = FakeSecureStore(erase = failedErase(Why.Other))
        var erasesAtDelete = -1
        val session = session(
            SavedWallet(store),
            forgetVaults = {
                erasesAtDelete = store.erases
                storage.delete()
            },
        )
        session.generateThisDevice()

        assertFalse(session.forgetSavedWallet(), "the erase failed")
        assertEquals(0, storage.deletes)
        assertEquals(GOLDEN, storage.text)

        store.erase = EraseResult.Erased
        assertTrue(session.forgetSavedWallet())
        assertEquals(2, erasesAtDelete, "the wallet was erased first")
        assertEquals(1, storage.deletes)
        assertEquals("", storage.text)
    }

    @Test
    fun aBookLeftByAnotherWalletIsDeletedAndNeverShown() {
        val storage = FakeVaultsStorage(GOLDEN)
        val vaults = SavedVaults(storage, owners = one { "11112222" }, deriveAddress = FAKE_DERIVE)
        vaults.sync()
        assertEquals(VaultBook(), vaults.current)
        assertEquals(VaultsHealth.Ok, vaults.health)
        assertEquals(1, storage.deletes)
        assertEquals("", storage.text)
    }

    @Test
    fun aReadThatCouldNotFinishIsRetriedAndNeverOverwrites() {
        val storage = FakeVaultsStorage(GOLDEN).apply { unreadable = true }
        val vaults = opened(storage)
        assertEquals(VaultBook(), vaults.current)
        assertEquals(VaultsHealth.Ok, vaults.health, "not yet read is not damage")

        val added = JADE.copy(id = "key_2_ffff0000", fingerprint = "ffff0000")
        vaults.edit(added(added))
        assertEquals(listOf(added), vaults.current.keys, "the edit shows at once")
        assertEquals(VaultsHealth.SaveFailed, vaults.health)
        assertEquals(0, storage.writes)
        assertEquals(GOLDEN, storage.text)

        storage.unreadable = false
        vaults.sync()
        assertEquals(listOf(JADE, added), vaults.current.keys, "the stored book, with the edit run again over it")
        assertEquals(listOf(VAULT), vaults.current.vaults)
        assertEquals(VaultsHealth.Ok, vaults.health)
        assertEquals(1, storage.writes)
        assertEquals(vaults.current, opened(storage).current)
    }

    @Test
    fun walletCreatedKeepsShowingTheWalletThisRunMadeAfterADeferredEditRenumbersIt() {
        fun numbered(n: Int) = JADE.copy(id = "key_$n", fingerprint = "aaaa000$n", xpub = "xpub-$n")
        fun create(book: SavedVaults, keys: List<SavedKey>): CreatedVault {
            val draft = CreateWalletDraft(book, FAKE_DERIVE) { _, _ -> WalletShape(2u, emptyList(), "", 0uL) } // made-up keys, read as any
            keys.forEach { draft.toggle(it.id, keys) }
            return assertNotNull(draft.create(assertNotNull(draft.ready(keys))))
        }
        val storage = FakeVaultsStorage()
        create(opened(storage), (1..3).map(::numbered))

        storage.unreadable = true // this run's first read could not finish, so its create is queued
        val vaults = opened(storage)
        val vaultDraft = VaultDraft(vaults)
        val made = create(vaults, (4..6).map(::numbered))
        vaultDraft.noteCreated(made)
        assertEquals(made.address, vaultDraft.selectedVault()?.address)

        storage.unreadable = false
        vaults.sync() // the replay over the stored book renumbers the new wallet from vault_1 to vault_2
        assertEquals(listOf("vault_1", "vault_2"), vaults.current.vaults.map { it.id })
        val shown = assertNotNull(vaultDraft.selectedVault())
        assertEquals(made.address, shown.address, "Wallet Created still shows this run's wallet")
        assertEquals(made.bsms, shown.bsms, "and exports its BSMS")

        vaultDraft.selectVault("vault_1")
        assertEquals("vault_1", vaultDraft.selectedVault()?.id, "a list tap still selects by id")
    }

    @Test
    fun aFailedWriteIsRetriedByTheNextSync() {
        val storage = FakeVaultsStorage().apply { failWrite = true }
        val vaults = opened(storage)
        vaults.edit(added(JADE))
        assertEquals(listOf(JADE), vaults.current.keys)
        assertEquals(VaultsHealth.SaveFailed, vaults.health)
        assertNotNull(vaultsNoticeText(vaults.health))

        storage.failWrite = false
        vaults.sync()
        assertEquals(VaultsHealth.Ok, vaults.health)
        assertNull(vaultsNoticeText(vaults.health))
        assertEquals(listOf(JADE), opened(storage).current.keys)
    }

    @Test
    fun aSessionThatIsNotSavedWritesNothing() {
        val stale = FakeSecureStore()
        val cases = listOf(
            "memory only" to session(null).apply { generateThisDevice() },
            "hardware only" to session(SavedWallet(FakeSecureStore())),
            "staged import" to session(SavedWallet(FakeSecureStore())).apply { restoreFromWords(PUBLIC_WORDS_12, "a passphrase") },
            "stale" to session(SavedWallet(stale)).apply {
                generateThisDevice()
                stale.erase()
                markBackupConfirmed()
            },
        )
        for ((case, session) in cases) {
            assertFalse(session.saved == SaveState.Saved, case)
            val storage = FakeVaultsStorage(GOLDEN)
            val vaults = SavedVaults(storage, owners = one { session.vaultsOwner() }, deriveAddress = FAKE_DERIVE)
            vaults.sync()
            vaults.edit(added(JADE))
            vaults.sync()
            assertEquals(listOf(JADE), vaults.current.keys, case)
            assertEquals(VaultsHealth.Ok, vaults.health, case)
            assertEquals(GOLDEN, storage.text, case)
            assertEquals(0, storage.writes, case)
            assertEquals(0, storage.deletes, case)
        }
    }

    @Test
    fun aStoreThatDoesNotExistKeepsTheBookInMemoryWithNoError() {
        val vaults = SavedVaults(null, owners = one { TEST_CHECK }, deriveAddress = FAKE_DERIVE)
        vaults.sync()
        vaults.edit(added(JADE))
        vaults.sync()
        assertEquals(listOf(JADE), vaults.current.keys)
        assertEquals(VaultsHealth.Ok, vaults.health)
    }

    @Test
    fun keysAddedBeforeTheWalletExistsAreSavedWhenItIsSaved() {
        val storage = FakeVaultsStorage()
        var owner: String? = null
        val vaults = SavedVaults(storage, owners = one { owner }, deriveAddress = FAKE_DERIVE)
        vaults.edit(added(JADE))
        vaults.sync()
        assertEquals(0, storage.writes)

        owner = TEST_CHECK
        vaults.sync()
        assertEquals(VaultsHealth.Ok, vaults.health)
        assertEquals(listOf(JADE), opened(storage).current.keys)
    }

    @Test
    fun aLaterBackupMakesTheOwnKeyVerified() {
        val storage = FakeVaultsStorage()
        val session = session(SavedWallet(FakeSecureStore()))
        session.generateThisDevice()
        val vaults = SavedVaults(storage, owners = one { session.vaultsOwner() }, deriveAddress = FAKE_DERIVE)
        val keyring = KeyringStore(book = vaults, ownKeys = { listOfNotNull(session.ownKey()) })
        vaults.sync()
        assertFalse(keyring.keys.single().verified)

        session.markBackupConfirmed()
        assertTrue(keyring.keys.single().verified)
        assertEquals(0, storage.writes, "the wallet's own key is never saved in the book")
    }

    @Test
    fun aKeyUsedByASavedVaultCannotBeDeletedAfterARestart() {
        val storage = FakeVaultsStorage(GOLDEN)
        val vaults = opened(storage)
        val keyring = KeyringStore(book = vaults)
        assertFalse(keyring.tryDelete(JADE.id))
        assertEquals(listOf(JADE), keyring.keys)
        assertEquals(0, storage.writes)
    }

    @Test
    fun anUnverifiedKeyIsNeverSaved() {
        val storage = FakeVaultsStorage()
        val vaults = opened(storage)
        vaults.edit(added(JADE.copy(verified = false)))
        assertEquals(VaultsHealth.SaveFailed, vaults.health)
        assertEquals(0, storage.writes)
    }

    @Test
    fun everyKeySourceAndPolicyHasACode() {
        for (source in KeySourceLabel.entries) {
            val storage = FakeVaultsStorage()
            val vaults = opened(storage)
            vaults.edit(added(JADE.copy(source = source)))
            assertEquals(VaultsHealth.Ok, vaults.health, "$source")
            assertEquals(source, opened(storage).current.keys.single().source)
        }
        for (policy in CustodyPolicy.entries) {
            val storage = FakeVaultsStorage()
            val vaults = opened(storage)
            vaults.edit { it.copy(vaults = it.vaults + VAULT.copy(policy = policy)) }
            assertEquals(VaultsHealth.Ok, vaults.health, "$policy")
            assertEquals(policy, opened(storage).current.vaults.single().policy)
        }
    }

    @Test
    fun theTextLayoutIsPinned() {
        val storage = FakeVaultsStorage()
        val vaults = opened(storage)
        vaults.edit { VaultBook(listOf(JADE), listOf(VAULT)) }
        assertEquals(GOLDEN, storage.text)
        assertEquals(VaultBook(listOf(JADE), listOf(VAULT)), opened(storage).current)

        vaults.edit { HW_BOOK }
        assertEquals(GOLDEN_HW, storage.text, "a hardware wallet, a wallet's key ids and its send in progress")
        assertEquals(HW_BOOK, opened(storage).current)
    }

    @Test
    fun anMv1BookOpensWithTheNewFieldsEmptyAndItsNextSaveIsMv2() {
        val storage = FakeVaultsStorage(GOLDEN_MV1)
        val vaults = opened(storage)
        assertEquals(VaultsHealth.Ok, vaults.health)
        assertEquals(VaultBook(listOf(JADE), listOf(VAULT)), vaults.current, "no key ids, no send in progress, no hardware wallet")
        assertEquals(GOLDEN_MV1, storage.text, "an open writes nothing")

        vaults.edit { it.copy(hardware = listOf(PUBLIC_JADE_WALLET)) }
        assertTrue(storage.text.startsWith("MV2\n"))
        assertEquals(VaultBook(listOf(JADE), listOf(VAULT), listOf(PUBLIC_JADE_WALLET)), opened(storage).current)
    }

    @Test
    fun anMv2BookSavedBeforeTheWalletNumbersOpensWithEverythingInIt() {
        val storage = FakeVaultsStorage(GOLDEN_HW_36C71CF)
        val vaults = opened(storage)
        assertEquals(VaultsHealth.Ok, vaults.health, "the book 36c71cf wrote")
        assertEquals(HW_BOOK.copy(lastVaultNumber = 0, lastHardwareNumber = 0), vaults.current, "every key and wallet, and no number given yet")
        assertEquals(GOLDEN_HW_36C71CF, storage.text, "an open writes nothing")

        val safe7 = JADE.copy(id = "key_2_11112222", label = "Safe 7", fingerprint = "11112222", xpub = "tpub-b", source = KeySourceLabel.TrezorSafe7)
        vaults.edit(added(safe7))
        assertEquals(listOf(JADE, safe7), opened(storage).current.keys, "a key added since is saved, not kept in memory only")
    }

    @Test
    fun theBookNamesItsWalletsGuardsOnlyWhileItCanBeJudged() {
        assertEquals(listOf(PUBLIC_JADE_WALLET.guardKey), opened(FakeVaultsStorage(GOLDEN_HW)).guardKeys())
        assertEquals(emptyList(), opened(FakeVaultsStorage(GOLDEN)).guardKeys())
        assertNull(opened(FakeVaultsStorage("not a book")).guardKeys(), "damaged")
        assertNull(opened(FakeVaultsStorage(GOLDEN.replaceFirst("MV2", "MV3"))).guardKeys(), "newer")
        assertNull(SavedVaults(FakeVaultsStorage(GOLDEN_HW).apply { unreadable = true }, owners = one { TEST_CHECK }).apply { sync() }.guardKeys(), "not read yet")
    }

    @Test
    fun aHardwareWalletWhoseKeyDoesNotDeriveItsAddressDamagesTheBook() {
        val storage = FakeVaultsStorage()
        opened(storage).edit { it.copy(hardware = listOf(PUBLIC_JADE_WALLET.copy(address = PUBLIC_ACCOUNTS.getValue(AddressType.TAPROOT).receive0))) }
        val written = storage.text
        val reopened = opened(storage)
        assertEquals(VaultsHealth.Damaged, reopened.health, "another address")
        assertEquals(VaultBook(), reopened.current)
        assertEquals(written, storage.text)

        val refusing = SavedVaults(FakeVaultsStorage(GOLDEN_HW), owners = one { TEST_CHECK }, deriveAddress = FAKE_DERIVE, describe = { _, _ -> throw IllegalArgumentException("no") })
        refusing.sync()
        assertEquals(VaultsHealth.Damaged, refusing.health, "a descriptor the core refuses")
    }

    @Test
    fun theMemoryClearsWhenTheOwnerGoesAway() {
        val storage = FakeVaultsStorage(GOLDEN)
        var owner: String? = TEST_CHECK
        val vaults = SavedVaults(storage, owners = one { owner }, deriveAddress = FAKE_DERIVE)
        vaults.sync()
        assertEquals(listOf(JADE), vaults.current.keys)

        owner = null
        vaults.sync()
        assertEquals(VaultBook(), vaults.current)
        assertEquals(GOLDEN, storage.text, "only a Forget deletes the stored copy")
        assertEquals(0, storage.deletes)
    }

    @Test
    fun aHandOverRewritesTheBookForTheNextWalletBeforeTheErase() {
        val storage = FakeVaultsStorage(GOLDEN)
        var owners = listOf(TEST_CHECK, OTHER_CHECK)
        val vaults = SavedVaults(storage, owners = { VaultOwners(owners, everyPlaceOpened = true) }, deriveAddress = FAKE_DERIVE).apply { sync() }
        assertEquals(1, vaults.current.vaults.size)

        assertTrue(vaults.handOver(TEST_CHECK, OTHER_CHECK), "the book's owner is leaving")
        assertEquals(1, storage.writes, "the stored copy names the next owner before anything is erased")
        assertEquals(OTHER_CHECK, storedOwner(storage))
        assertEquals(VaultsHealth.Ok, vaults.health)

        owners = listOf(OTHER_CHECK)
        vaults.sync()
        assertEquals(0, storage.deletes, "the book survived its first owner's removal")
        assertEquals(listOf(VAULT), vaults.current.vaults)
        assertEquals(1, storage.writes, "nothing was owed")
        val restart = SavedVaults(storage, owners = { VaultOwners(listOf(OTHER_CHECK), everyPlaceOpened = true) }, deriveAddress = FAKE_DERIVE).apply { sync() }
        assertEquals(listOf(VAULT), restart.current.vaults, "the next launch reads the book under the new owner")
    }

    @Test
    fun aHandOverOfAWalletTheBookDoesNotNameWritesNothing() {
        val storage = FakeVaultsStorage(GOLDEN)
        val vaults = SavedVaults(storage, owners = { VaultOwners(listOf(TEST_CHECK, OTHER_CHECK), everyPlaceOpened = true) }, deriveAddress = FAKE_DERIVE).apply { sync() }
        assertTrue(vaults.handOver(OTHER_CHECK, TEST_CHECK), "the book names the wallet that stays")
        assertEquals(0, storage.writes)
        assertEquals(GOLDEN, storage.text)
    }

    @Test
    fun aHandOverThatCannotWriteRefusesAndChangesNothing() {
        val storage = FakeVaultsStorage(GOLDEN).apply { failWrite = true }
        val vaults = SavedVaults(storage, owners = { VaultOwners(listOf(TEST_CHECK, OTHER_CHECK), everyPlaceOpened = true) }, deriveAddress = FAKE_DERIVE).apply { sync() }
        assertFalse(vaults.handOver(TEST_CHECK, OTHER_CHECK), "the write failed, so the wallet must not be erased")
        assertEquals(GOLDEN, storage.text)
    }

    @Test
    fun aHandOverNeverOverwritesANewerBook() {
        val newer = GOLDEN.replaceFirst("MV2", "MV3")
        val storage = FakeVaultsStorage(newer)
        val vaults = SavedVaults(storage, owners = { VaultOwners(listOf(TEST_CHECK, OTHER_CHECK), everyPlaceOpened = true) }, deriveAddress = FAKE_DERIVE).apply { sync() }
        assertEquals(VaultsHealth.Newer, vaults.health)
        assertFalse(vaults.handOver(TEST_CHECK, OTHER_CHECK), "a book it cannot judge is not rewritten")
        assertEquals(newer, storage.text)
        assertEquals(0, storage.writes)
    }

    @Test
    fun aBookUsesAKeyOnlyWhenItCanBeJudged() {
        val healthy = opened(FakeVaultsStorage(GOLDEN))
        assertTrue(healthy.judged)
        assertEquals(true, healthy.usesKey("ABCD0123"), "a fingerprint in a vault, in any case")
        assertEquals(false, healthy.usesKey(OTHER_CHECK), "a fingerprint in no vault")
        for ((case, storage) in listOf("damaged" to FakeVaultsStorage("not a book"), "newer" to FakeVaultsStorage(GOLDEN.replaceFirst("MV2", "MV3")))) {
            assertFalse(opened(storage).judged, case)
            assertNull(opened(storage).usesKey("abcd0123"), case)
        }
        val unread = FakeVaultsStorage(GOLDEN).apply { unreadable = true }
        assertFalse(opened(unread).judged, "a book that could not be read this moment")
        assertNull(opened(unread).usesKey("abcd0123"), "a book that could not be read this moment")
        assertTrue(SavedVaults.memoryOnly().judged)
        assertEquals(false, SavedVaults.memoryOnly().usesKey("abcd0123"), "a memory-only book holds everything it knows")
    }

    // A first read that finds no book changes no other state, so a menu drawn before it said the book could not be read until something else redrew it.
    @Test
    fun aScreenThatJudgedTheBookBeforeItsFirstReadIsToldOfTheRead() {
        val vaults = SavedVaults(FakeVaultsStorage(), owners = one { TEST_CHECK }, deriveAddress = FAKE_DERIVE)
        val read = mutableSetOf<Any>()
        Snapshot.observe(readObserver = { read += it }) { assertFalse(vaults.judged) }
        val written = mutableSetOf<Any>()
        val observer = Snapshot.registerApplyObserver { changed, _ -> written.addAll(changed) }
        try {
            vaults.sync()
            Snapshot.sendApplyNotifications()
        } finally {
            observer.dispose()
        }
        assertTrue(vaults.judged)
        assertTrue(read.any { it in written }, "the read changed state the screen read")
    }

    @Test
    fun aKeyIsNeverDeletedWhileTheBookCannotBeJudged() {
        val storage = FakeVaultsStorage()
        var owner: String? = null
        val vaults = SavedVaults(storage, owners = one { owner }, deriveAddress = FAKE_DERIVE).apply { sync() }
        val keyring = KeyringStore(book = vaults)
        enrol(keyring, "ffff0000", tpubA) // no wallet is saved yet, so memory is the whole book
        storage.unreadable = true
        owner = TEST_CHECK
        vaults.sync()
        assertFalse(keyring.tryDelete(keyring.keys.single().id), "a delete queued now would run over the stored book")
        assertEquals(1, keyring.keys.size)
    }

    @Test
    fun removingAVaultKeepsEveryKeyAndTheOtherVaultsAndASecondRemoveChangesNothing() {
        val storage = FakeVaultsStorage(GOLDEN)
        val vaults = opened(storage)
        val other = CreatedVault("vault_2", "Wallet 2", 1, 1, CustodyPolicy.PersonalCustody, "addr-1-tpub-b", "x\nwsh(sortedmulti(1,tpub-b))", listOf("bbbb0002"))
        vaults.edit { it.copy(vaults = it.vaults + other) }
        val draft = VaultDraft(vaults)
        draft.noteCreated(VAULT)

        assertTrue(draft.remove(VAULT.id))
        assertEquals(listOf(other), vaults.current.vaults)
        assertEquals(listOf(JADE), vaults.current.keys, "the removed wallet's keys stay on the Keyring")
        assertNull(draft.selectedVaultId)
        assertEquals(other, draft.selectedVault(), "the vault screens let go of the removed wallet")

        val stored = storage.text
        assertTrue(draft.remove(VAULT.id))
        assertEquals(listOf(other), vaults.current.vaults)
        assertEquals(stored, storage.text, "a second remove changes nothing")
        assertEquals(listOf(other), opened(storage).current.vaults, "and a restart still has only the other wallet")
    }

    @Test
    fun aRemovalWhoseWriteFailsKeepsTheWalletAndARestartAgrees() {
        val storage = FakeVaultsStorage(GOLDEN).apply { failWrite = true } // a full disk, say
        val vaults = opened(storage)
        val draft = VaultDraft(vaults).apply { noteCreated(VAULT) }

        assertFalse(draft.remove(VAULT.id))
        assertEquals(listOf(VAULT), vaults.current.vaults, "the wallet stays on the list")
        assertEquals(VAULT, draft.selectedVault(), "and on the vault screens")
        assertNull(vaultsNoticeText(vaults.health), "nothing waits for a retry")

        storage.failWrite = false
        vaults.sync()
        assertEquals(listOf(VAULT), opened(storage).current.vaults, "a later save and a restart agree")
    }

    @Test
    fun aRemovalOnABookThatCannotBeJudgedKeepsTheWallet() {
        val cases = listOf(
            "damaged" to FakeVaultsStorage("not a book"),
            "written by a newer app" to FakeVaultsStorage(GOLDEN.replaceFirst("MV2", "MV3")),
            "not readable this moment" to FakeVaultsStorage(GOLDEN).apply { unreadable = true },
        )
        for ((case, storage) in cases) {
            val vaults = opened(storage)
            vaults.edit { it.copy(vaults = listOf(VAULT)) } // this run's wallet, shown though it is not stored
            val text = storage.text
            assertFalse(VaultDraft(vaults).remove(VAULT.id), case)
            assertEquals(listOf(VAULT), vaults.current.vaults, case)
            assertEquals(text, storage.text, case)
        }
    }

    @Test
    fun aKeyDeleteWhoseWriteFailsKeepsTheKey() {
        val storage = FakeVaultsStorage()
        val vaults = opened(storage)
        vaults.edit(added(JADE))
        val keyring = KeyringStore(book = vaults)

        storage.failWrite = true
        assertFalse(keyring.tryDelete(JADE.id))
        assertEquals(listOf(JADE), keyring.keys)
        assertNull(vaultsNoticeText(vaults.health), "nothing waits for a retry")

        storage.failWrite = false
        vaults.sync()
        assertEquals(listOf(JADE), opened(storage).current.keys, "a later save and a restart agree")
    }

    // Check c12's start: a Forget, then only hardware keys and no wallet saved. The store holds nothing, so memory is the whole book. Every delete here was once blocked as unreadable.
    @Test
    fun withNoWalletSavedTheBookIsJudgedSoAKeyAndAWalletCanBeRemoved() {
        val storage = FakeVaultsStorage()
        val vaults = SavedVaults(storage, owners = one { null }, deriveAddress = FAKE_DERIVE)
        assertTrue(vaults.judged, "before any sync too")
        vaults.sync()
        vaults.edit { VaultBook(listOf(JADE), listOf(VAULT)) }
        val keyring = KeyringStore(book = vaults)

        assertTrue(vaults.judged)
        assertFalse(keyring.tryDelete(JADE.id), "the wallet still uses the key")
        assertTrue(VaultDraft(vaults).remove(VAULT.id))
        assertTrue(keyring.tryDelete(JADE.id))
        assertEquals(VaultBook(), vaults.current)
        assertEquals(0, storage.writes, "nothing is stored without a saved wallet")
    }

    // With no wallet saved the stored book is never opened, so it may hold what this run never saw, and a change queued now would run over it once its owner is back.
    @Test
    fun withNoWalletSavedAStoredBookLosesNoKeyAndNoVault() {
        for (synced in listOf(false, true)) {
            val case = if (synced) "after a sync" else "before any sync"
            val storage = FakeVaultsStorage(GOLDEN)
            var owner: String? = null
            val vaults = SavedVaults(storage, owners = one { owner }, deriveAddress = FAKE_DERIVE)
            if (synced) vaults.sync()
            vaults.edit { it.copy(keys = listOf(JADE)) } // the key added again this run, with no vault in memory
            assertFalse(KeyringStore(book = vaults).tryDelete(JADE.id), case)
            assertFalse(VaultDraft(vaults).remove(VAULT.id), "a removal by an id the stored book also holds, $case")
            assertEquals(GOLDEN, storage.text, case)

            owner = TEST_CHECK
            vaults.sync()
            assertEquals(VaultBook(listOf(JADE), listOf(VAULT)), vaults.current, "once the owner is back, $case")
            assertEquals(VaultBook(listOf(JADE), listOf(VAULT)), opened(storage).current, "and after a restart, $case")
        }
    }

    @Test
    fun withNoWalletSavedAStoreThatCannotBeReadRefusesADelete() {
        val vaults = SavedVaults(FakeVaultsStorage().apply { unreadable = true }, owners = one { null }, deriveAddress = FAKE_DERIVE).apply { sync() }
        val keyring = KeyringStore(book = vaults)
        vaults.edit(added(JADE))
        assertFalse(vaults.judged)
        assertFalse(keyring.tryDelete(JADE.id))
        assertEquals(listOf(JADE), keyring.keys)
    }

    @Test
    fun aBookSavedForAWalletIsNeverTakenForAnEmptyStoreOnceTheWalletGoes() {
        val storage = FakeVaultsStorage()
        var owner: String? = null
        val vaults = SavedVaults(storage, owners = one { owner }, deriveAddress = FAKE_DERIVE).apply { sync() }
        assertTrue(vaults.judged, "nothing is stored yet")

        owner = TEST_CHECK
        vaults.edit { VaultBook(listOf(JADE), listOf(VAULT)) }
        assertEquals(GOLDEN, storage.text)
        owner = null
        assertFalse(vaults.judged, "before the next sync")
        vaults.sync()
        assertFalse(vaults.judged, "after it")
    }

    @Test
    fun withNoWalletSavedAScreenThatJudgedTheBookIsToldWhenASyncFindsTheStoreEmptied() {
        val storage = FakeVaultsStorage(GOLDEN)
        val vaults = SavedVaults(storage, owners = one { null }, deriveAddress = FAKE_DERIVE).apply { sync() }
        val read = mutableSetOf<Any>()
        Snapshot.observe(readObserver = { read += it }) { assertFalse(vaults.judged) }
        storage.delete() // a Forget
        val written = mutableSetOf<Any>()
        val observer = Snapshot.registerApplyObserver { changed, _ -> written.addAll(changed) }
        try {
            vaults.sync()
            Snapshot.sendApplyNotifications()
        } finally {
            observer.dispose()
        }
        assertTrue(vaults.judged)
        assertTrue(read.any { it in written }, "the sync changed state the screen read")
    }

    @Test
    fun aBookWhoseOwnerIsNotHeldIsKeptAndReownedWhileAPlaceHasNotOpened() {
        val storage = FakeVaultsStorage(GOLDEN) // owned by TEST_CHECK, which is not among the opened wallets
        val vaults = SavedVaults(storage, owners = { VaultOwners(listOf(OTHER_CHECK), everyPlaceOpened = false) }, deriveAddress = FAKE_DERIVE).apply { sync() }
        assertEquals(0, storage.deletes, "the owner may be the wallet that did not open")
        assertEquals(listOf(VAULT), vaults.current.vaults)
        assertEquals(OTHER_CHECK, storedOwner(storage), "re-owned at its next save")
        assertEquals(1, storage.writes)

        val next = SavedVaults(storage, owners = { VaultOwners(listOf(OTHER_CHECK), everyPlaceOpened = true) }, deriveAddress = FAKE_DERIVE).apply { sync() }
        assertEquals(0, storage.deletes, "the book now names a wallet the phone holds")
        assertEquals(listOf(VAULT), next.current.vaults)
    }

    @Test
    fun aBookWhoseOwnerIsNotHeldIsDeletedOnceEveryPlaceOpened() {
        val storage = FakeVaultsStorage(GOLDEN)
        SavedVaults(storage, owners = { VaultOwners(listOf(OTHER_CHECK), everyPlaceOpened = true) }, deriveAddress = FAKE_DERIVE).sync()
        assertEquals(1, storage.deletes)
        assertEquals("", storage.text)
    }

    @Test
    fun aBookReadForOneHeldOwnerStaysReadWhenAnotherIsAdded() {
        val storage = FakeVaultsStorage(GOLDEN)
        var owners = listOf(TEST_CHECK)
        val vaults = SavedVaults(storage, owners = { VaultOwners(owners, everyPlaceOpened = true) }, deriveAddress = FAKE_DERIVE).apply { sync() }
        owners = listOf(TEST_CHECK, OTHER_CHECK)
        vaults.sync()
        vaults.edit(added(JADE.copy(id = "key_2_ffff0000", fingerprint = "ffff0000")))
        assertEquals(TEST_CHECK, storedOwner(storage), "the book keeps its owner while that wallet stays")
    }

    private fun enrol(keyring: KeyringStore, fingerprint: String, xpub: String, path: String = PATH) {
        assertTrue(keyring.startMebitOtherFromPaste("$fingerprint\n$xpub\n$path"))
        assertTrue(keyring.confirmFingerprint(fingerprint))
    }

    private fun opened(storage: VaultsStorage) = SavedVaults(storage, owners = one { TEST_CHECK }, deriveAddress = FAKE_DERIVE).apply { sync() }

    private fun session(wallet: SavedWallet?, forgetVaults: () -> Unit = {}) = OnboardingSession(
        wallet,
        newKey = { _, _, _ -> SoftwareAccountKey(PUBLIC_WORDS_24, TEST_CHECK, "tpub-own", PATH) },
        restoreKey = { words, _, _, _ -> SoftwareAccountKey(words, TEST_CHECK, "tpub-own", PATH) },
        forgetScans = {},
        forgetVaults = forgetVaults,
    )

    private fun added(key: SavedKey): (VaultBook) -> VaultBook = { it.copy(keys = it.keys + key) }

    private fun FakeSecureStore.activity() = listOf(writes.size, served.size, erases, markerWrites.size)
}

/** A [VaultsStorage] in memory. [text] is what is saved. [unreadable] and [failWrite] make each read or write fail, and it counts writes and deletes. */
internal class FakeVaultsStorage(var text: String = "") : VaultsStorage {
    var unreadable = false
    var failWrite = false
    var writes = 0
        private set
    var deletes = 0
        private set

    override fun read(): String? = if (unreadable) null else text

    override fun write(text: String): Boolean {
        if (failWrite) return false
        writes++
        this.text = text
        return true
    }

    override fun delete(): Boolean {
        deletes++
        text = ""
        return true
    }
}

private const val PATH = "m/48'/1'/0'/2'"

/** Another wallet's check value, distinct from [TEST_CHECK]. */
private const val OTHER_CHECK = "9f8e7d6c"

/** Owners for a phone with one wallet that every place opened for, so a test of one wallet's rules reads as it did. */
private fun one(fingerprint: () -> String?): () -> VaultOwners? = { fingerprint()?.let { VaultOwners(listOf(it), everyPlaceOpened = true) } }

/** The owner field of the stored text, read by hand from the layout. */
internal fun storedOwner(storage: FakeVaultsStorage): String = storage.text.lines()[1].substringAfter(' ')

private val FAKE_DERIVE: (List<String>, UByte) -> String = { xpubs, m -> "addr-$m-" + xpubs.joinToString("+") }

private val JADE = SavedKey("key_1_abcd0123", "กุญแจบ้าน", "abcd0123", "tpub-a", PATH, KeySourceLabel.Jade, verified = true)

private val VAULT = CreatedVault("vault_1", "Vault 1", 1, 1, CustodyPolicy.PersonalCustody, "addr-1-tpub-a", "x\nwsh(sortedmulti(1,tpub-a))", listOf("abcd0123"), hasMockKey = true)

/** The MV1 text of a book holding [JADE] and [VAULT] for [TEST_CHECK], as a build before the hardware wallets wrote it, written out by hand from the layout. */
private val GOLDEN_MV1 =
    "MV1\n" +
        "8 0a1b2c3d\n" +
        "1 1\n" +
        "14 key_1_abcd0123\n9 กุญแจบ้าน\n8 abcd0123\n6 tpub-a\n14 m/48'/1'/0'/2'\n1 2\n1 0\n" +
        "1 1\n" +
        "7 vault_1\n7 Vault 1\n1 1\n1 1\n1 0\n13 addr-1-tpub-a\n28 x\nwsh(sortedmulti(1,tpub-a))\n1 1\n1 1\n8 abcd0123\n"

/** The same book as this build writes it: MV2, the vault's empty key ids and send in progress, no hardware wallet, and no number given. */
private val GOLDEN =
    "MV2\n" +
        "8 0a1b2c3d\n" +
        "1 1\n" +
        "14 key_1_abcd0123\n9 กุญแจบ้าน\n8 abcd0123\n6 tpub-a\n14 m/48'/1'/0'/2'\n1 2\n1 0\n" +
        "1 1\n" +
        "7 vault_1\n7 Vault 1\n1 1\n1 1\n1 0\n13 addr-1-tpub-a\n28 x\nwsh(sortedmulti(1,tpub-a))\n1 1\n1 1\n8 abcd0123\n1 0\n0 \n" +
        "1 0\n" +
        "1 0\n1 0\n"

/** [GOLDEN]'s book with the vault's key id, a send in progress, [PUBLIC_JADE_WALLET], and higher numbers given to wallets removed since. */
private val HW_BOOK = VaultBook(
    listOf(JADE),
    listOf(VAULT.copy(keyIds = listOf(JADE.id), pending = "cHNidP8B")),
    listOf(PUBLIC_JADE_WALLET),
    lastVaultNumber = 4,
    lastHardwareNumber = 2,
)

private val GOLDEN_HW =
    "MV2\n" +
        "8 0a1b2c3d\n" +
        "1 1\n" +
        "14 key_1_abcd0123\n9 กุญแจบ้าน\n8 abcd0123\n6 tpub-a\n14 m/48'/1'/0'/2'\n1 2\n1 0\n" +
        "1 1\n" +
        "7 vault_1\n7 Vault 1\n1 1\n1 1\n1 0\n13 addr-1-tpub-a\n28 x\nwsh(sortedmulti(1,tpub-a))\n1 1\n1 1\n8 abcd0123\n1 1\n14 key_1_abcd0123\n8 cHNidP8B\n" +
        "1 1\n" +
        "4 hw_1\n4 Jade\n1 2\n1 0\n8 $PUBLIC_FINGERPRINT\n111 ${PUBLIC_JADE_WALLET.xpub}\n42 ${PUBLIC_JADE_WALLET.address}\n0 \n" +
        "1 4\n1 2\n"

/** [GOLDEN_HW] as 36c71cf pinned it: MV2 as every build from 9d1c581 to 36c71cf wrote it, without the two wallet numbers at the end. */
private val GOLDEN_HW_36C71CF =
    "MV2\n" +
        "8 0a1b2c3d\n" +
        "1 1\n" +
        "14 key_1_abcd0123\n9 กุญแจบ้าน\n8 abcd0123\n6 tpub-a\n14 m/48'/1'/0'/2'\n1 2\n1 0\n" +
        "1 1\n" +
        "7 vault_1\n7 Vault 1\n1 1\n1 1\n1 0\n13 addr-1-tpub-a\n28 x\nwsh(sortedmulti(1,tpub-a))\n1 1\n1 1\n8 abcd0123\n1 1\n14 key_1_abcd0123\n8 cHNidP8B\n" +
        "1 1\n" +
        "4 hw_1\n4 Jade\n1 2\n1 0\n8 $PUBLIC_FINGERPRINT\n111 ${PUBLIC_JADE_WALLET.xpub}\n42 ${PUBLIC_JADE_WALLET.address}\n0 \n"
