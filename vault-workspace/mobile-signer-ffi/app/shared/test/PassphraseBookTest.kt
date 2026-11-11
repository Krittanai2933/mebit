package com.mebit.app.data

import com.mebit.app.Place
import com.mebit.app.VaultsStorage
import com.mebit.app.Why
import com.mebit.app.WriteResult
import com.mebit.ffi.signer.SoftwareAccountKey
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val PATH = "m/48'/1'/0'/2'"
private const val FIRST_KEY = "aaaa1111"
private const val PASSPHRASE_KEY = "bbbb2222"
private const val SECOND_PASSPHRASE_KEY = "ffff6666"

/** The key of a wallet this phone no longer holds. */
private const val GONE_KEY = "eeee5555"

private val JADE = SavedKey("key_1_cccc3333", "Jade", "cccc3333", "tpub-jade", PATH, KeySourceLabel.Jade, verified = true)
private val TREZOR = SavedKey("key_2_dddd4444", "Trezor", "dddd4444", "tpub-trezor", PATH, KeySourceLabel.TrezorSafe7, verified = true)
private val TWO_OF_TWO = CreatedVault("vault_1", "Wallet 1", 2, 2, CustodyPolicy.PersonalCustody, "addr", "x\nwsh(sortedmulti(2,tpub-jade,tpub-trezor))", listOf("cccc3333", "dddd4444"))
private val BOOK = VaultBook(listOf(JADE, TREZOR), listOf(TWO_OF_TWO))

/**
 * A book store whose [text] outlives the process. With [anyTime] it reads as Android's KeystoreVaults does. Otherwise it follows
 * KeychainVaults' read rule (src@ios/VaultsStorage.ios.kt): a read answers once after an owner check passed ([ownerPassed]), else ""
 * after a delete this process made, else null. [relaunch] is a new process. The next [failWrites] writes answer false and change nothing.
 */
private class BookStore(private val anyTime: Boolean) : VaultsStorage {
    val name = if (anyTime) "Android" else "iOS"
    var text = ""
    var failWrites = 0
    private var passed = false
    private var emptied = false

    fun ownerPassed() {
        passed = true
    }

    fun relaunch() {
        passed = false
        emptied = false
    }

    /** The owner field of the stored text. */
    fun owner(): String = text.lines()[1].substringAfter(' ')

    override fun read(): String? {
        if (anyTime) return text
        if (!passed) return if (emptied) "" else null
        passed = false
        return text
    }

    override fun write(text: String): Boolean {
        if (failWrites > 0) {
            failWrites--
            return false
        }
        this.text = text
        emptied = false
        return true
    }

    override fun delete(): Boolean {
        text = ""
        passed = false
        emptied = true
        return true
    }
}

/**
 * One phone: its wallet places and its book store, which both outlive the process. The phone wallet's key is [PASSPHRASE_KEY] once it
 * has a passphrase, and [SECOND_PASSPHRASE_KEY] with "another passphrase".
 */
private class SetupPhone(anyTime: Boolean) {
    val shelf = FakeShelf()
    val store = BookStore(anyTime)

    private fun launch() = Wallets(
        shelf,
        forgetScans = {},
        newKey = { p, _, _ -> SoftwareAccountKey(PUBLIC_WORDS_24, keyFor(p), "tpub-own", PATH) },
        restoreKey = { w, p, _, _ -> SoftwareAccountKey(w, keyFor(p), "tpub-own", PATH) },
    )

    private fun keyFor(passphrase: String) = when (passphrase) {
        "" -> FIRST_KEY
        "another passphrase" -> SECOND_PASSPHRASE_KEY
        else -> PASSPHRASE_KEY
    }

    private fun book(wallets: Wallets) = SavedVaults(store, owners = { wallets.vaultOwners() }, deriveAddress = { _, _ -> "addr" })

    /** Welcome, two hardware keys and a 2-of-2 with no phone wallet yet, then Add Key, This device. NavGraph syncs on each new owner. */
    fun firstPhoneWallet(): Pair<OpenWallet, SavedVaults> {
        store.delete() // WalletGate deletes the book of a phone that holds no wallet
        val wallets = launch()
        val vaults = book(wallets).apply { sync() }
        vaults.edit { BOOK }
        val w = assertNotNull(wallets.beginAdd())
        w.session.generateThisDevice()
        vaults.sync()
        return w to vaults
    }

    /** App Lock Enable. Its read-back is an owner check, so iOS hands the book its context, and nothing reads the book before the app is closed. */
    suspend fun appLock(w: OpenWallet): UnlockResult {
        val result = w.session.proveSavedCopy("test")
        store.ownerPassed()
        if (result == UnlockResult.Verified) w.session.markAppLockEnabled()
        return result
    }

    /** The next launch: one owner check for every place, which also hands the book its context, then NavGraph's sync. The wallet's owner and the book it reads. */
    suspend fun nextLaunch(): Pair<List<String>?, VaultBook> {
        store.relaunch()
        val wallets = launch()
        assertIs<Every.Open>(wallets.unlockEvery("test"))
        store.ownerPassed()
        return wallets.vaultOwners()?.fingerprints to book(wallets).apply { sync() }.current
    }
}

private fun phones() = listOf(SetupPhone(anyTime = false), SetupPhone(anyTime = true))

/** The stored text of [BOOK] as the wallet [owner] saved it. */
private fun bookSavedFor(owner: String): String = BookStore(anyTime = true).also { store ->
    SavedVaults(store, owners = { VaultOwners(listOf(owner), everyPlaceOpened = true) }, deriveAddress = { _, _ -> "addr" }).edit { BOOK }
}.text

class PassphraseBookTest {
    @Test
    fun keysAndAWalletMadeBeforeTheFirstPhoneWalletSurviveItsPassphrase(): Unit = runBlocking {
        for (phone in phones()) {
            val (w, vaults) = phone.firstPhoneWallet()
            w.session.changeNewKeyPassphrase("a passphrase") // Penpot 1.4.2: same words, another key, so another owner
            vaults.sync()
            assertEquals(UnlockResult.Verified, phone.appLock(w), phone.store.name)
            assertEquals(listOf(PASSPHRASE_KEY) to BOOK, phone.nextLaunch(), phone.store.name)
        }
    }

    @Test
    fun aPassphraseTakenBackLeavesTheBookWithTheFirstKey(): Unit = runBlocking {
        for (phone in phones()) {
            val (w, vaults) = phone.firstPhoneWallet()
            w.session.changeNewKeyPassphrase("a passphrase")
            vaults.sync()
            w.session.changeNewKeyPassphrase("") // the passphrase row's clear gives the first key back
            vaults.sync()
            assertEquals(UnlockResult.Verified, phone.appLock(w), phone.store.name)
            assertEquals(listOf(FIRST_KEY) to BOOK, phone.nextLaunch(), phone.store.name)
        }
    }

    @Test
    fun aPassphraseWhoseSaveFailedLeavesTheBookWithTheKeyThePhoneStillHolds(): Unit = runBlocking {
        for (phone in phones()) {
            val (w, vaults) = phone.firstPhoneWallet()
            phone.shelf.places.getValue(Place.FIRST).write = WriteResult.Unavailable(Why.Other)
            w.session.changeNewKeyPassphrase("a passphrase")
            assertTrue(w.session.lastSaveFailed, "the Replace failed, so the phone still holds the first key")
            vaults.sync()
            assertEquals(FIRST_KEY, phone.store.owner(), "${phone.store.name}: the book is never saved for a key the phone does not hold")
            assertIs<UnlockResult.Broken>(phone.appLock(w), "the read-back finds the first key, so the wallet is not saved")
            vaults.sync() // NavGraph's, because the owners changed
            assertEquals(listOf(FIRST_KEY) to BOOK, phone.nextLaunch(), phone.store.name)
        }
    }

    @Test
    fun aBookWriteThatFailsAfterThePassphraseKeepsTheBookForTheNextLaunch(): Unit = runBlocking {
        for (phone in phones()) {
            val (w, vaults) = phone.firstPhoneWallet()
            w.session.changeNewKeyPassphrase("a passphrase")
            phone.store.failWrites = 1
            vaults.sync() // NavGraph's, and no later step of setup changes the owners, so it is the last one
            assertEquals(FIRST_KEY, phone.store.owner(), "${phone.store.name}: the move to the new key was not saved")
            w.session.markBackupConfirmed()
            w.session.confirmAddressType()
            assertEquals(UnlockResult.Verified, phone.appLock(w), phone.store.name)
            w.session.endFirstSetup()
            assertEquals(listOf(PASSPHRASE_KEY) to BOOK, phone.nextLaunch(), phone.store.name)
            assertEquals(PASSPHRASE_KEY, phone.store.owner(), "${phone.store.name}: the next launch saves the book for the new key")
        }
    }

    @Test
    fun aBookThatFollowedNeitherOfTwoPassphrasesIsKeptForTheLastKey(): Unit = runBlocking {
        for (phone in phones()) {
            val (w, vaults) = phone.firstPhoneWallet()
            phone.store.failWrites = 2
            w.session.changeNewKeyPassphrase("a passphrase")
            vaults.sync()
            w.session.changeNewKeyPassphrase("another passphrase")
            vaults.sync()
            assertEquals(FIRST_KEY, phone.store.owner(), "${phone.store.name}: neither move was saved")
            assertEquals(listOf(SECOND_PASSPHRASE_KEY) to BOOK, phone.nextLaunch(), phone.store.name)
        }
    }

    @Test
    fun anAppClosedBeforeTheBookMovesToTheNewKeyKeepsTheBook(): Unit = runBlocking {
        for (phone in phones()) {
            val (w, _) = phone.firstPhoneWallet()
            w.session.changeNewKeyPassphrase("a passphrase") // the record names the new key, and the app is closed before a sync
            assertEquals(listOf(PASSPHRASE_KEY) to BOOK, phone.nextLaunch(), phone.store.name)
        }
    }

    @Test
    fun aBookLeftByAWalletThePhoneNoLongerHoldsIsStillDeletedBesideAKeyThatChanged(): Unit = runBlocking {
        for (phone in phones()) {
            val (w, _) = phone.firstPhoneWallet()
            w.session.changeNewKeyPassphrase("a passphrase")
            phone.store.text = bookSavedFor(GONE_KEY) // and the app is closed
            assertEquals(listOf(PASSPHRASE_KEY) to VaultBook(), phone.nextLaunch(), phone.store.name)
            assertEquals("", phone.store.text, "${phone.store.name}: deleted as left behind")
        }
    }
}
