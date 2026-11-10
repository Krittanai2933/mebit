package com.mebit.app.data

import com.mebit.app.EraseResult
import com.mebit.app.Place
import com.mebit.app.VaultsStorage
import com.mebit.app.Why
import com.mebit.app.WriteMode
import com.mebit.app.navigation.Boot
import com.mebit.app.navigation.WalletGateState
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.SoftwareAccountKey
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
        for (cut in 1 until GOLDEN.length) {
            val text = GOLDEN.substring(0, cut)
            val storage = FakeVaultsStorage(text)
            val vaults = opened(storage)
            assertEquals(VaultsHealth.Damaged, vaults.health, "cut at $cut")
            assertEquals(VaultBook(), vaults.current, "cut at $cut")
            vaults.edit(added(JADE))
            assertEquals(text, storage.text, "cut at $cut")
            assertEquals(0, storage.writes, "cut at $cut")
        }
    }

    @Test
    fun aTextThatEncodeNeverWritesIsDamaged() {
        val damaged = listOf(
            "leftover" to GOLDEN + "1 1\n",
            "version zero" to GOLDEN.replaceFirst("MV1", "MV0"),
            "not canonical" to GOLDEN.replaceFirst("8 0a1b", "08 0a1b"),
            "a count that is not canonical" to GOLDEN.replaceFirst("1 1\n", "2 01\n"),
            "a flag that is neither" to GOLDEN.replaceFirst("1 2\n1 0\n", "1 2\n1 7\n"),
            "m above n" to GOLDEN.replaceFirst("1 1\n1 1\n1 0\n13 addr-1", "1 2\n1 1\n1 0\n13 addr-2"),
            "fewer fingerprints than n" to GOLDEN.replaceFirst("1 1\n1 1\n8 abcd0123\n", "1 1\n1 0\n"),
            "over 1 MiB" to "MV1\n" + "x".repeat(1_048_576),
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
            "version 2" to GOLDEN.replaceFirst("MV1", "MV2"),
            "a version too long for an int" to GOLDEN.replaceFirst("MV1", "MV99999999999"),
            "an unknown source" to GOLDEN.replaceFirst("1 2\n", "1 9\n"),
            "an unknown policy" to GOLDEN.replaceFirst("1 0\n13", "1 9\n13"),
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
            val draft = CreateWalletDraft(book, FAKE_DERIVE)
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
        val newer = GOLDEN.replaceFirst("MV1", "MV2")
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
        assertEquals(true, healthy.usesKey("ABCD0123"), "a fingerprint in a vault, in any case")
        assertEquals(false, healthy.usesKey(OTHER_CHECK), "a fingerprint in no vault")
        for ((case, storage) in listOf("damaged" to FakeVaultsStorage("not a book"), "newer" to FakeVaultsStorage(GOLDEN.replaceFirst("MV1", "MV2")))) {
            assertNull(opened(storage).usesKey("abcd0123"), case)
        }
        val unread = FakeVaultsStorage(GOLDEN).apply { unreadable = true }
        assertNull(opened(unread).usesKey("abcd0123"), "a book that could not be read this moment")
        assertEquals(false, SavedVaults.memoryOnly().usesKey("abcd0123"), "a memory-only book holds everything it knows")
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

/** The stored text of a book holding [JADE] and [VAULT] for [TEST_CHECK], written out by hand from the layout. */
private val GOLDEN =
    "MV1\n" +
        "8 0a1b2c3d\n" +
        "1 1\n" +
        "14 key_1_abcd0123\n9 กุญแจบ้าน\n8 abcd0123\n6 tpub-a\n14 m/48'/1'/0'/2'\n1 2\n1 0\n" +
        "1 1\n" +
        "7 vault_1\n7 Vault 1\n1 1\n1 1\n1 0\n13 addr-1-tpub-a\n28 x\nwsh(sortedmulti(1,tpub-a))\n1 1\n1 1\n8 abcd0123\n"
