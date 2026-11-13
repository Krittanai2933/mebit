package com.mebit.app.data

import com.mebit.app.Marker
import com.mebit.app.Place
import com.mebit.app.PlainFile
import com.mebit.app.ReadResult
import com.mebit.app.StoreProbe
import com.mebit.app.Why
import com.mebit.app.WriteMode
import com.mebit.app.WriteResult
import com.mebit.app.data.SingleSigWallet.UnconfirmedSend
import com.mebit.app.feature.vault.duplicateLine
import com.mebit.app.navigation.Boot
import com.mebit.app.navigation.MovieRoute
import com.mebit.app.navigation.Stop
import com.mebit.app.navigation.WalletGateState
import com.mebit.app.navigation.endSetup
import com.mebit.app.walletName
import com.mebit.ffi.signer.AddressType
import com.mebit.ffi.signer.HotScan
import com.mebit.ffi.signer.HotTx
import com.mebit.ffi.signer.HotWalletSnapshot
import com.mebit.ffi.signer.HwAccountKey
import com.mebit.ffi.signer.SoftwareAccountKey
import com.mebit.ffi.signer.VaultSnapshot
import com.mebit.ffi.signer.WalletKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private val P1 = Place.FIRST
private val P2 = Place.parse(2)!!
private val P3 = Place.parse(3)!!
private val P4 = Place.parse(4)!!

/** Twelve test words for wallet [n], so each wallet derives to its own fingerprint. Never a real wallet, and no test prints them. */
private fun words(n: Int): List<String> = List(11) { "abandon" } + "word$n"

/** [words], derived by the fakes below with no native library: the check value a real derive would give, from a hash of the words. */
private fun check(words: List<String>, passphrase: String = ""): String =
    if (words == PUBLIC_WORDS_24) TEST_CHECK else (words.joinToString(" ") + "/" + passphrase).hashCode().toUInt().toString(16).padStart(8, '0')

private val SENT_AT = 1_792_740_720L

/** The keys vault [n]'s BSMS names, in order. */
private fun vaultKeys(n: Int): List<String> = listOf("tpub$n-a", "tpub$n-b", "tpub$n-c")

/** Vault [n] of the book, a 2-of-3 whose BSMS descriptor line names [vaultKeys] of [n]. */
private fun vault(n: Int, address: String = "tb1qvault$n") = CreatedVault(
    "vault_$n", "Wallet $n", 2, 3, CustodyPolicy.PersonalCustody, address,
    "BSMS 1.0\nwsh(sortedmulti(2,${vaultKeys(n).joinToString(",") { "[0000000$n/48'/1'/0'/2']$it" }}))\n/0/*,/1/*\n$address",
    List(3) { "0000000$n" },
)

/** One place's record as an earlier launch saved it, the words of wallet [n]. */
private fun recordOf(n: Int, addressTypeConfirmed: Boolean = true, backupConfirmed: Boolean = true) =
    testRecord(words = words(n), fingerprint = check(words(n)), backupConfirmed = backupConfirmed, addressTypeConfirmed = addressTypeConfirmed)

/** What the phone holds and what the fakes around [Wallets] saw. [launch] starts a new process over it. */
private class ShelfPhone {
    val shelf = FakeShelf()
    val guards = FakePlainFile()
    val last = FakePlainFile()
    var booksForgotten = 0
        private set
    var scansForgotten = 0
        private set

    /** The words the next Create makes. */
    var nextWords = words(50)

    /** Maps a derive to a fingerprint, so a test can make two wallets collide. */
    var derive: (List<String>, String) -> String = { w, p -> check(w, p) }

    var nowSeconds = 1_000L
    val binds = mutableListOf<String>()
    var scanResult = HotScan.COMPLETE

    /** Each vault read the core was asked for, as its keys and m. */
    val vaultReads = mutableListOf<Pair<List<String>, UByte>>()
    var vaultScan = HotScan.COMPLETE
    var vaultSatsOnChain = 84_200_000uL
    var vaultFails = false

    /** Runs inside each vault read, so a test can erase the phone while one is out. */
    var duringVaultRead: () -> Unit = {}

    /** Runs each time [Wallets] reads its own clock, which a vault read does after the network answers and before it writes the figure. */
    var onWalletsNow: () -> Unit = {}

    /** Each vault history the core was asked for, as its keys and m. */
    val historyReads = mutableListOf<Pair<List<String>, UByte>>()
    var vaultTxsOnChain = listOf(HotTx("tx1", 84_200_000, 3u, 1_790_000_000uL))
    var historyFails = false

    /** Runs inside each history read, so a test can erase the phone while one is out. */
    var duringHistoryRead: () -> Unit = {}

    /** The book's wallets' guard keys, null for a book that cannot be judged. */
    var bookKeys: List<String>? = emptyList()

    /** The book's Jade and Trezor wallets. */
    var bookHardware: List<HardwareWallet> = emptyList()

    /** Writes a record at [place] as an earlier launch did, and forgets that write, so a test sees only what this launch writes. */
    fun saved(place: Place, record: WalletRecord): ShelfPhone {
        assertIs<SaveResult.Written>(SavedWallet(shelf.places.getValue(place)).save(record, WriteMode.Create), "the earlier launch's save")
        place(place).writes.clear()
        place(place).markerWrites.clear()
        return this
    }

    fun saved(place: Place, n: Int): ShelfPhone = saved(place, recordOf(n))

    fun place(p: Place): FakeSecureStore = shelf.places.getValue(p)

    /** The [Wallets] of a new process. */
    fun launch(lastFile: PlainFile = last): Wallets = Wallets(
        shelf,
        PendingPayments(guards),
        lastFile,
        forgetBook = { booksForgotten++ },
        forgetScans = { scansForgotten++ },
        newKey = { _, _, _ -> key(nextWords, "") },
        restoreKey = { w, p, _, _ -> key(w, p) },
        newChain = {
            SingleSigWallet(
                snapshot = { w, _, n, _ ->
                    binds += check(w)
                    HotWalletSnapshot("tb1qaddress", 1_000uL, 1_000uL, n, scanResult)
                },
                fetchHistory = { _, _, _, _ -> emptyList() },
                nowSeconds = { nowSeconds },
            )
        },
        vaultSnapshotOf = { xpubs, m ->
            vaultReads += xpubs to m
            duringVaultRead()
            if (vaultFails) error("network error: down")
            VaultSnapshot(vaultSatsOnChain, vaultScan)
        },
        vaultHistoryOf = { xpubs, m ->
            historyReads += xpubs to m
            duringHistoryRead()
            if (historyFails) error("esplora history: tx 0: unreadable")
            vaultTxsOnChain
        },
        nowSeconds = {
            onWalletsNow()
            nowSeconds
        },
        bookGuardKeys = { bookKeys },
        bookHardware = { bookHardware },
    )

    private fun key(w: List<String>, passphrase: String) = SoftwareAccountKey(w, derive(w, passphrase), "tpub-${derive(w, passphrase)}", "m/48'/1'/0'/2'")

    /** A launch already past its one owner check. */
    suspend fun unlocked(lastFile: PlainFile = last): Wallets = launch(lastFile).also { assertIs<Every.Open>(it.unlockEvery("test"), "the unlock") }
}

class WalletsTest {
    @Test
    fun aVersion1RecordAtPlaceOneOpensWithOneOwnerCheckAndIsNeverRewritten(): Unit = runBlocking {
        val phone = ShelfPhone()
        phone.place(P1).write(v1Bytes(), WriteMode.Create) // the bytes an earlier build left
        phone.place(P1).writes.clear()
        val wallets = phone.launch()
        val every = assertIs<Every.Open>(wallets.unlockEvery("test"))
        assertEquals(1, phone.shelf.sheets, "owner checks for the whole phone")
        assertSame(P1, every.resume.place)
        val session = wallets.at(P1).session
        assertEquals(PUBLIC_WORDS_24, session.words)
        assertEquals(TEST_CHECK, session.fingerprint)
        assertEquals(SaveState.Saved, session.saved)
        assertTrue(Place.all.all { phone.place(it).writes.isEmpty() }, "an open must never write a record")
        assertEquals(Marker.Kept, phone.shelf.marker)
    }

    @Test
    fun everyWalletOpensAtItsOwnPlaceWithItsOwnNameAndOneOwnerCheck(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1).saved(P3, 3)
        val wallets = phone.unlocked()
        assertEquals(listOf(P1, P3), wallets.all.map { it.place }, "a gap at place 2 stays a gap")
        assertEquals(words(1), wallets.at(P1).session.words)
        assertEquals(words(3), wallets.at(P3).session.words)
        assertEquals("กระเป๋าใช้จ่าย", wallets.at(P1).session.walletName)
        assertEquals("กระเป๋า 3", wallets.at(P3).session.walletName)
        assertEquals(1, phone.shelf.sheets, "one owner check for every wallet")
        assertEquals(listOf(P1, P3), wallets.listed.map { it.place })
    }

    @Test
    fun placesAreNamedAndParsedAtTheEdges() {
        assertEquals("กระเป๋าใช้จ่าย", P1.walletName)
        assertEquals("กระเป๋า 2", P2.walletName)
        assertEquals("กระเป๋า 10", Place.parse(Place.COUNT)!!.walletName)
        assertNull(Place.parse(0))
        assertNull(Place.parse(Place.COUNT + 1))
        assertEquals(Place.COUNT, Place.all.size)
    }

    @Test
    fun aDamagedPlaceStaysOccupiedWhileTheOthersOpen(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1).saved(P2, 2).saved(P3, 3)
        phone.place(P2).read = ReadResult.Unreadable
        val wallets = phone.launch()
        val every = assertIs<Every.Open>(wallets.unlockEvery("test"))
        assertSame(P1, every.resume.place)
        assertEquals(listOf(P1, P3), wallets.all.map { it.place })
        assertEquals(setOf(P2), wallets.unopened.keys)
        assertEquals(Cause.Corrupt, assertIs<UnlockResult.Broken>(wallets.unopened.getValue(P2)).cause)
        assertFalse(wallets.vaultOwners()!!.everyPlaceOpened, "the damaged place might own the book")

        val added = assertNotNull(wallets.beginAdd(), "a damaged place must not stop an add")
        assertSame(P4, added.place, "the place of the damaged record is taken, so the next free one is used")
        added.session.generateThisDevice()
        assertTrue(phone.place(P2).writes.isEmpty(), "a damaged place is never written")
        assertEquals(0, phone.place(P2).erases)
    }

    @Test
    fun aNewerRecordIsTreatedAsDamagedAndNeverOverwritten(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1)
        phone.place(P2).write(v1Bytes(version = 2), WriteMode.Create)
        phone.place(P2).writes.clear()
        val wallets = phone.unlocked()
        assertEquals(setOf(P2), wallets.unopened.keys)
        assertEquals(Cause.NewerVersion, assertIs<UnlockResult.Broken>(wallets.unopened.getValue(P2)).cause)
        wallets.beginAdd()?.session?.generateThisDevice()
        assertTrue(phone.place(P2).writes.isEmpty(), "a Newer record must never be overwritten")
    }

    @Test
    fun aPlaceThatCouldNotBeReadThisMomentNeverBecomesARecoveryScreen(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1).saved(P2, 2)
        phone.place(P1).read = ReadResult.Cancelled
        phone.place(P2).read = ReadResult.Unreadable
        val refused = assertIs<Every.Refused>(phone.launch().unlockEvery("test"), "one place only failed to answer")
        assertEquals(UnlockResult.Cancelled, refused.result)

        val broken = ShelfPhone().saved(P1, 1).saved(P2, 2)
        broken.place(P1).read = ReadResult.Unreadable
        broken.place(P2).read = ReadResult.KeyGone
        assertIs<Every.Broken>(broken.launch().unlockEvery("test"), "every place can never open")

        val empty = ShelfPhone().launch()
        assertEquals(Cause.Missing, assertIs<Every.Broken>(empty.unlockEvery("test")).cause, "nothing found at all")
    }

    @Test
    fun aRefusedOwnerCheckInstallsNothing(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1).saved(P2, 2)
        phone.shelf.refuse = ReadResult.Denied
        val wallets = phone.launch()
        assertEquals(UnlockResult.Denied, assertIs<Every.Refused>(wallets.unlockEvery("test")).result)
        assertTrue(wallets.at(P1).session.words.isEmpty(), "a refused check installs nothing")
    }

    @Test
    fun aRecordThatFailsToInstallStaysOccupiedAndTheOthersOpen(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1).saved(P2, 2)
        phone.derive = { w, p -> if (w == words(2)) "deadbeef" else check(w, p) } // place 2's words no longer derive to its record's check value
        val wallets = phone.launch()
        assertIs<Every.Open>(wallets.unlockEvery("test"))
        assertEquals(setOf(P2), wallets.unopened.keys)
        assertEquals(listOf(P1), wallets.all.map { it.place })
        assertEquals(0, phone.place(P2).erases, "a mismatch is never erased")
        assertEquals(1, phone.shelf.sheets)
    }

    @Test
    fun anUnlockThatInstallsNothingLeavesTheFirstEntryUsable(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1)
        phone.derive = { _, _ -> "deadbeef" }
        val wallets = phone.launch()
        assertIs<Every.Broken>(wallets.unlockEvery("test"))
        assertEquals(listOf(P1), wallets.all.map { it.place })
        assertTrue(wallets.at(P1).session.words.isEmpty(), "a mismatch left no key in memory")
    }

    @Test
    fun anUnlockKeepsTheFirstEntrysObjectSoAReferenceTakenBeforeItStaysGood(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1).saved(P2, 2)
        val wallets = phone.launch()
        val before = wallets.at(P1)
        assertIs<Every.Open>(wallets.unlockEvery("test"))
        assertSame(before, wallets.at(P1))
        assertEquals(words(1), before.session.words)
    }

    @Test
    fun theWalletToOpenIsTheUnfinishedOneThenTheLastUsedThenTheLowest(): Unit = runBlocking {
        val unfinished = ShelfPhone().saved(P1, 1).saved(P2, recordOf(2, addressTypeConfirmed = false)).saved(P3, 3)
        unfinished.last.text = "3"
        assertSame(P2, assertIs<Every.Open>(unfinished.launch().unlockEvery("test")).resume.place, "an unfinished wallet opens first")

        val lastUsed = ShelfPhone().saved(P1, 1).saved(P2, 2).saved(P3, 3)
        lastUsed.last.text = "3"
        assertSame(P3, assertIs<Every.Open>(lastUsed.launch().unlockEvery("test")).resume.place, "the last used")

        for (text in listOf("not a number", "11", "0", "")) {
            val damagedFile = ShelfPhone().saved(P1, 1).saved(P2, 2)
            damagedFile.last.text = text
            assertSame(P1, assertIs<Every.Open>(damagedFile.launch().unlockEvery("test")).resume.place, "a last-wallet file of [$text] falls to the lowest place")
        }

        val goneWallet = ShelfPhone().saved(P2, 2).saved(P3, 3)
        goneWallet.last.text = "1" // the wallet last used was since removed
        assertSame(P2, assertIs<Every.Open>(goneWallet.launch().unlockEvery("test")).resume.place, "the lowest place that opened")
    }

    @Test
    fun theGateOpensAtTheResumeWalletsRoute(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1).saved(P2, recordOf(2, backupConfirmed = false))
        phone.last.text = "2"
        val wallets = phone.launch()
        val gate = WalletGateState(wallets)
        assertEquals(Boot.Locked, gate.boot)
        gate.unlock("test")
        val open = assertIs<Boot.Open>(gate.boot)
        assertSame(P2, open.place)
        assertEquals(MovieRoute.VaultList, open.route, "the wallet not backed up lands on the Wallet list")
        assertEquals(Stop(MovieRoute.VaultList, P2), Stop(open.route, open.place))
    }

    @Test
    fun whenNoPlaceOpensTheGateIsBrokenAndForgetErasesEveryPlace(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1).saved(P2, 2)
        phone.place(P1).read = ReadResult.Unreadable
        phone.place(P2).read = ReadResult.KeyGone
        val wallets = phone.launch()
        val gate = WalletGateState(wallets)
        gate.unlock("test")
        assertIs<Boot.Broken>(gate.boot)
        gate.forget("test")
        assertEquals(Boot.Onboarding, gate.boot, "a recovery screen's forget")
        assertEquals(1, phone.place(P1).erases)
        assertEquals(1, phone.place(P2).erases)
        assertEquals(1, phone.scansForgotten)
        assertEquals(1, phone.booksForgotten)
        assertEquals(Marker.Empty, phone.shelf.marker)
    }

    @Test
    fun forgetFromLockedIsOneOwnerCheckAndErasesEveryPlace(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1).saved(P2, 2)
        phone.last.text = "2"
        val wallets = phone.launch()
        val gate = WalletGateState(wallets)
        gate.forget("test")
        assertEquals(Boot.Onboarding, gate.boot)
        assertEquals(1, phone.shelf.sheets, "one owner check for the whole phone")
        assertTrue(Place.all.all { phone.place(it).erases == 1 }, "every place is erased")
        assertEquals(StoreProbe.Empty, phone.shelf.store(P1).probe())
        assertNull(phone.last.text, "the last-wallet file goes with a Forget")
        assertEquals(listOf(P1), wallets.all.map { it.place })
        assertTrue(wallets.at(P1).session.words.isEmpty(), "the phone is back to one empty first wallet")
        assertEquals(emptyList(), wallets.listed)
    }

    @Test
    fun aForgetMovesEveryOlderGuardFirstOrErasesNothing(): Unit = runBlocking {
        val pendingA = UnconfirmedSend(TEST_TXID, SENT_AT)
        val pendingB = UnconfirmedSend("b".repeat(64), SENT_AT + 1)
        val a = recordOf(1)
        val b = recordOf(2)
        val phone = ShelfPhone()
        phone.place(P1).write(oldRecordBytesFor(a, pendingA), WriteMode.Create)
        phone.place(P2).write(oldRecordBytesFor(b, pendingB), WriteMode.Create)
        val wallets = phone.launch()
        phone.guards.text = "garbage\n" // a guard file that cannot take them
        val gate = WalletGateState(wallets)
        gate.forget("test")
        assertEquals(Boot.Locked, gate.boot, "a forget that cannot move the older guards")
        assertEquals(0, phone.place(P1).erases + phone.place(P2).erases, "nothing is erased")

        phone.guards.delete()
        gate.forget("test")
        assertEquals(Boot.Onboarding, gate.boot)
        assertEquals(pendingA, phone.guards.guardOf(a.fingerprint))
        assertEquals(pendingB, phone.guards.guardOf(b.fingerprint))
    }

    @Test
    fun aNewWalletTakesTheLowestFreePlaceAndSavesItselfThere(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1).saved(P3, 3)
        val wallets = phone.unlocked()
        val w = assertNotNull(wallets.beginAdd())
        assertSame(P2, w.place, "the gap at 2 is used first, so a removed number comes back")
        assertEquals(listOf(P1, P2, P3), wallets.all.map { it.place })
        assertTrue(Place.all.all { phone.place(it).writes.isEmpty() }, "beginAdd writes nothing")
        assertEquals(emptyList(), wallets.listed.filter { it === w }, "an empty new wallet is not listed")

        phone.nextWords = words(2)
        w.session.generateThisDevice()
        assertEquals(1, phone.place(P2).writes.size)
        assertTrue(phone.place(P1).writes.isEmpty() && phone.place(P3).writes.isEmpty(), "no other place is written")
        assertEquals(SaveState.Saved, w.session.saved)
        assertEquals("กระเป๋า 2", w.session.walletName)
        assertTrue(w in wallets.listed)
        assertNotSame(wallets.at(P1).chain, w.chain, "a wallet's chain state is its own")
    }

    @Test
    fun anAbandonedAddIsReusedNotLeftBehind(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1)
        val wallets = phone.unlocked()
        val first = wallets.beginAdd()
        assertSame(first, wallets.beginAdd(), "an add that made nothing is the same empty entry")
        assertEquals(2, wallets.all.size)

        first!!.session.restoreFromWords(words(9), "a passphrase") // staged, waits for the fingerprint check, nothing is written
        assertEquals(SaveState.Staged, first.session.saved)
        assertFalse(first in wallets.listed, "a staged import is not a wallet yet")
        assertTrue(phone.place(P2).writes.isEmpty())
        assertSame(first, wallets.beginAdd(), "a staged import gives way to the next add")
        first.session.restoreFromWords(words(9))
        assertEquals(SaveState.Saved, first.session.saved)
    }

    @Test
    fun anAddIsRefusedWhenEveryPlaceIsTaken(): Unit = runBlocking {
        val phone = ShelfPhone()
        for (i in 1..Place.COUNT) phone.saved(Place.parse(i)!!, i)
        val wallets = phone.unlocked()
        assertEquals(AddRefusal.Full, wallets.addRefusal())
        assertNull(wallets.beginAdd())
    }

    @Test
    fun aDamagedPlaceCountsTowardFull(): Unit = runBlocking {
        val phone = ShelfPhone()
        for (i in 1..Place.COUNT) phone.saved(Place.parse(i)!!, i)
        phone.place(Place.parse(10)!!).read = ReadResult.Unreadable
        val wallets = phone.unlocked()
        assertEquals(AddRefusal.Full, wallets.addRefusal(), "a place holding damaged bytes is occupied, not free")
    }

    @Test
    fun anAddIsRefusedWhileAWalletHasNotFinishedSetup(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1).saved(P2, recordOf(2, addressTypeConfirmed = false))
        val wallets = phone.unlocked()
        assertEquals(AddRefusal.SetupUnfinished, wallets.addRefusal())
        assertNull(wallets.beginAdd())
        wallets.at(P2).session.confirmAddressType()
        assertNull(wallets.addRefusal(), "once the type is confirmed the add goes on")
        assertNotNull(wallets.beginAdd())
    }

    @Test
    fun anAddIsRefusedWhileAWalletIsOnlyInMemory() {
        val phone = ShelfPhone()
        phone.place(P1).write = WriteResult.Unavailable(Why.NoScreenLock)
        val wallets = phone.launch()
        wallets.at(P1).session.generateThisDevice()
        assertEquals(SaveState.NotSaved, wallets.at(P1).session.saved)
        assertEquals(AddRefusal.NotSavedHere, wallets.addRefusal())
        assertNull(wallets.beginAdd())
    }

    @Test
    fun theWordsOfAWalletWhosePlaceDidNotOpenAreNotAddedASecondTime(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1).saved(P2, 2)
        phone.place(P2).read = ReadResult.Cancelled // its sheet was cancelled, so it opens at the next launch
        val wallets = phone.unlocked()
        val refusal = wallets.addRefusal()
        wallets.beginAdd()?.session?.restoreFromWords(words(2))
        phone.place(P2).read = null
        val fingerprints = phone.unlocked().all.map { it.session.fingerprint }
        assertEquals(fingerprints.distinct(), fingerprints, "the same words are on the phone twice")
        assertEquals(AddRefusal.Unopened, refusal)
    }

    @Test
    fun anAddIsRefusedWhileAPlaceThatMayOpenLaterDidNotOpen(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1).saved(P2, 2)
        phone.place(P2).read = ReadResult.Unavailable(Why.TryLater)
        val wallets = phone.unlocked()
        assertEquals(setOf(P2), wallets.unopened.keys)
        wallets.beginAdd()?.session?.restoreFromWords(words(2))
        assertEquals(listOf(P1), wallets.listed.map { it.place }, "the words of the place that did not open were added again")
        assertEquals(AddRefusal.Unopened, wallets.addRefusal())
    }

    @Test
    fun aHardwareWalletIsRefusedWhileAPlaceThatMayOpenLaterDidNotOpen(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1).saved(P2, testRecord(words = words(2), fingerprint = PUBLIC_FINGERPRINT, backupConfirmed = true))
        phone.derive = { w, p -> if (w == words(2) && p.isEmpty()) PUBLIC_FINGERPRINT else check(w, p) }
        phone.place(P2).read = ReadResult.Unavailable(Why.TryLater)
        val wallets = phone.unlocked()
        val storage = FakeVaultsStorage()
        val type = AddressType.NATIVE_SEGWIT
        val key = HwAccountKey(PUBLIC_ACCOUNTS.getValue(type).tpub, PUBLIC_FINGERPRINT, oneKeyPath(type))

        val added = HardwareWallets(book(storage, wallets).apply { sync() }).add(WalletKind.JADE, type, key, wallets.phoneWallets())
        assertEquals(HardwareAdd.Unopened, added, "refused as a phone add is then")

        phone.place(P2).read = null
        val next = phone.unlocked()
        val reread = book(storage, next).apply { sync() }
        assertEquals(emptyList(), reread.current.hardware, "a Jade wallet over place 2's addresses, added as $added")
        assertEquals(HardwareAdd.Duplicate(next.at(P2).session.walletName), HardwareWallets(reread).add(WalletKind.JADE, type, key, next.phoneWallets()), "with place 2 open, its wallet")
        val broken = ShelfPhone().saved(P1, 1).saved(P2, 2).apply { place(P2).read = ReadResult.Unreadable }.unlocked()
        assertNotNull(broken.phoneWallets(), "a place that can never open in this build holds no add back")
    }

    @Test
    fun aPlaceThatCanNeverOpenStillAllowsAnAddEvenOfItsWords(): Unit = runBlocking {
        val broken = listOf(
            Cause.Corrupt to ReadResult.Unreadable,
            Cause.KeyGone to ReadResult.KeyGone,
            Cause.NewerVersion to ReadResult.Bytes(v1Bytes(version = 2), ANY_VERSION),
        )
        for ((cause, read) in broken) {
            val phone = ShelfPhone().saved(P1, 1).saved(P2, 2)
            phone.place(P2).read = read
            val wallets = phone.unlocked()
            assertEquals(cause, assertIs<UnlockResult.Broken>(wallets.unopened.getValue(P2)).cause)
            assertNull(wallets.addRefusal(), "$cause")
            val w = assertNotNull(wallets.beginAdd(), "$cause")
            w.session.restoreFromWords(words(2)) // its fingerprint is unknown and it can never open in this build
            assertEquals(SaveState.Saved, w.session.saved, "a copy beside it is accepted, $cause")
        }
    }

    @Test
    fun aNewWalletCopiesThePhonesAppLock(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1)
        val wallets = phone.unlocked()
        wallets.at(P1).session.markAppLockEnabled()
        val w = wallets.beginAdd()!!
        assertTrue(w.session.appLockEnabled, "App Lock is one setting for the phone")
        w.session.clear()
        assertTrue(w.session.appLockEnabled, "clear returns to the phone's value")
    }

    @Test
    fun importingWordsAlreadyOnThePhoneIsRefusedAndChangesNothing(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1)
        val wallets = phone.unlocked()
        val w = wallets.beginAdd()!!
        w.session.restoreFromWords(words(1))
        assertNotNull(w.session.lastError, "another wallet holds these words")
        assertTrue(w.session.words.isEmpty(), "a refused import applies no key")
        assertTrue(phone.place(w.place).writes.isEmpty(), "and writes nothing")

        // A staged import is checked by the fingerprint its passphrase gives, before anything is staged.
        phone.derive = { word, passphrase -> if (word == words(2) && passphrase == "p") check(words(1)) else check(word, passphrase) }
        w.session.restoreFromWords(words(2), "p")
        assertNotNull(w.session.lastError, "the staged import's fingerprint is on the phone")
        assertEquals(SaveState.NotSaved, w.session.saved, "nothing was staged")
        assertFalse(w in wallets.listed)
    }

    @Test
    fun aNewKeysPassphraseCannotMoveOntoAnotherWalletsFingerprint(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1)
        phone.nextWords = words(2)
        val wallets = phone.unlocked()
        val w = wallets.beginAdd()!!
        w.session.generateThisDevice()
        val before = w.session.fingerprint
        phone.derive = { word, passphrase -> if (word == words(2) && passphrase == "secret") check(words(1)) else check(word, passphrase) }
        w.session.changeNewKeyPassphrase("secret")
        assertNotNull(w.session.lastError)
        assertEquals(before, w.session.fingerprint, "the session keeps its own key")
    }

    @Test
    fun theSameWalletKeepsItsOwnPassphraseChange(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1)
        phone.nextWords = words(2)
        val wallets = phone.unlocked()
        val w = wallets.beginAdd()!!
        w.session.generateThisDevice()
        w.session.changeNewKeyPassphrase("mine")
        assertNull(w.session.lastError, "a new passphrase on this wallet's own words is fine")
        assertEquals(check(words(2), "mine"), w.session.fingerprint)
    }

    @Test
    fun everySetupEndsOnTheWalletListAndItsKeyIsFinalFromThere(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1)
        phone.nextWords = words(5)
        val wallets = phone.unlocked()
        val w = assertNotNull(wallets.beginAdd())
        w.session.generateThisDevice()
        w.session.confirmAddressType()
        assertTrue(w.session.inFirstSetup)
        assertEquals(MovieRoute.VaultList, endSetup(w.session))
        assertFalse(w.session.inFirstSetup)
        val key = w.session.fingerprint // a vault may use it from here on
        w.session.changeNewKeyPassphrase("later") // Backup Words' passphrase row
        assertEquals(key, w.session.fingerprint, "a key the list has shown must stay")
    }

    @Test
    fun theWalletListReadsAWalletWhoseSetupJustEnded(): Unit = runBlocking {
        val fresh = ShelfPhone().apply { nextWords = words(5) }
        val first = fresh.launch()
        first.at(P1).session.generateThisDevice()
        first.at(P1).session.confirmAddressType()
        endSetup(first.at(P1).session)
        first.refreshBalances(vaults = emptyList())
        assertEquals(listOf(check(words(5))), fresh.binds, "a first wallet on a fresh phone")

        val phone = ShelfPhone().saved(P1, 1)
        val wallets = phone.unlocked()
        wallets.refreshBalances(vaults = emptyList())
        phone.binds.clear()
        phone.nextWords = words(6)
        val w = assertNotNull(wallets.beginAdd())
        w.session.generateThisDevice()
        w.session.confirmAddressType()
        endSetup(w.session)
        wallets.refreshBalances(vaults = emptyList())
        assertEquals(listOf(check(words(6))), phone.binds, "a second wallet beside one read a moment ago")
    }

    @Test
    fun theWalletInUseIsReadFirstSoAnotherScanThatStopsThePassCannotLeaveItUnbound(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1).saved(P2, 2)
        val wallets = phone.unlocked()
        phone.scanResult = HotScan.CONTINUING
        wallets.refreshBalances(vaults = emptyList(), first = P2)
        assertEquals(listOf(check(words(2))), phone.binds)
    }

    @Test
    fun guardsAndChainStateStayWithTheirWallet(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1).saved(P2, 2)
        val wallets = phone.unlocked()
        assertEquals(Claim.Written, wallets.at(P1).session.recordUnconfirmedSend(TEST_TXID, SENT_AT))
        assertEquals(UnconfirmedSend(TEST_TXID, SENT_AT), wallets.at(P1).session.unconfirmedSend())
        assertNull(wallets.at(P2).session.unconfirmedSend(), "a payment of one wallet never reaches another")
        assertNotSame(wallets.at(P1).chain, wallets.at(P2).chain)
    }

    @Test
    fun clearingADamagedGuardFileOnOneWalletKeepsItDamagedForTheOthers(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1).saved(P2, 2)
        val wallets = phone.unlocked()
        val a = wallets.at(P1).session
        assertEquals(Claim.Written, wallets.at(P2).session.recordUnconfirmedSend(TEST_TXID, SENT_AT))
        phone.guards.text += "x" // the file no longer decodes
        assertTrue(a.clearUnconfirmedSend(SingleSigWallet.DamagedGuard), "wallet 1's clear")
        assertNull(a.unconfirmedSend(), "wallet 1 may pay again")
        val b = phone.unlocked().at(P2).session
        assertEquals(SingleSigWallet.DamagedGuard, b.unconfirmedSend(), "wallet 2's payment may be out, so it must still refuse a new one")
        assertEquals(Claim.Damaged, b.recordUnconfirmedSend("b".repeat(64), SENT_AT))
        assertTrue(b.clearUnconfirmedSend(SingleSigWallet.DamagedGuard), "wallet 2's own clear")
        assertNull(b.unconfirmedSend())
        assertNull(phone.guards.text, "the last line's clear deletes the file")
    }

    @Test
    fun aPhoneWalletsClearOfADamagedGuardFileKeepsItDamagedForTheBooksWallets(): Unit = runBlocking {
        val hardware = PUBLIC_JADE_WALLET.guardKey
        val phone = ShelfPhone().saved(P1, 1).saved(P2, 2)
        phone.bookKeys = listOf(hardware)
        val wallets = phone.unlocked()
        assertEquals(listOf(check(words(1)), check(words(2)), hardware), wallets.guardKeys())
        phone.guards.text = "garbage\n"
        assertTrue(wallets.at(P1).session.clearUnconfirmedSend(SingleSigWallet.DamagedGuard), "wallet 1's clear")
        assertEquals(SingleSigWallet.DamagedGuard, PendingPayments(phone.guards).of(hardware), "the hardware wallet's payment may be out")
        assertEquals(SingleSigWallet.DamagedGuard, wallets.at(P2).session.unconfirmedSend())

        phone.guards.text = "garbage\n"
        phone.bookKeys = null
        assertNull(wallets.guardKeys())
        assertFalse(wallets.at(P1).session.clearUnconfirmedSend(SingleSigWallet.DamagedGuard), "a book that cannot be judged may hold a wallet whose guard this would drop")
        assertEquals("garbage\n", phone.guards.text)
    }

    @Test
    fun aDamagedGuardFileIsNotClearedWhileAWalletThatMayOpenLaterIsUnread(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1).saved(P2, 2)
        phone.guards.text = "garbage\n"
        phone.place(P2).read = ReadResult.Cancelled
        assertFalse(phone.unlocked().at(P1).session.clearUnconfirmedSend(SingleSigWallet.DamagedGuard), "wallet 2 is unread, so no line can keep its guard")
        assertEquals("garbage\n", phone.guards.text)

        phone.place(P2).read = null
        val wallets = phone.unlocked()
        assertTrue(wallets.at(P1).session.clearUnconfirmedSend(SingleSigWallet.DamagedGuard))
        assertEquals(SingleSigWallet.DamagedGuard, wallets.at(P2).session.unconfirmedSend())

        val broken = ShelfPhone().saved(P1, 1).saved(P2, 2)
        broken.guards.text = "garbage\n"
        broken.place(P2).read = ReadResult.KeyGone
        assertTrue(broken.unlocked().at(P1).session.clearUnconfirmedSend(SingleSigWallet.DamagedGuard), "a place that can never open holds nothing back")
    }

    @Test
    fun removingTheFirstWalletKeepsTheOthersAndHandsTheBookToTheNext(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1).saved(P2, 2).saved(P3, 3)
        val wallets = phone.unlocked()
        val storage = FakeVaultsStorage()
        val book = book(storage, wallets).apply { sync() }
        book.edit { it.copy(keys = listOf(HARDWARE)) } // the book's first save names the wallet at the lowest place
        assertEquals(check(words(1)), storedOwner(storage), "the book names the first wallet before the removal")

        var shown: OpenWallet? = null
        val removed = wallets.remove(wallets.at(P1), book, "test") { shown = it }
        assertEquals(WalletRemoval.Removed, removed)
        assertEquals(check(words(2)), storedOwner(storage), "the book was handed over before the erase")
        assertEquals(1, phone.place(P1).erases, "the removed wallet's place is erased")
        assertEquals(0, phone.place(P2).erases + phone.place(P3).erases, "no other place is touched")
        assertEquals(Marker.Kept, phone.shelf.marker, "other wallets are still here, so the phone is not marked empty")
        assertSame(P2, shown?.place, "the next wallet is handed to the Back stack")
        assertEquals(listOf(P2, P3), wallets.all.map { it.place })

        val w = assertNotNull(wallets.beginAdd())
        assertSame(P1, w.place, "a removed number comes back")
        assertEquals("กระเป๋าใช้จ่าย", w.session.walletName)
    }

    @Test
    fun aBookNamingAnotherWalletIsNotRewrittenByARemoval(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1).saved(P2, 2)
        val wallets = phone.unlocked()
        val storage = FakeVaultsStorage()
        val book = book(storage, wallets).apply { sync() }
        book.edit { it.copy(keys = listOf(HARDWARE)) }
        val writes = storage.writes
        assertEquals(WalletRemoval.Removed, wallets.remove(wallets.at(P2), book, "test") {})
        assertEquals(writes, storage.writes, "the book already names a wallet that stays")
        assertEquals(check(words(1)), storedOwner(storage))
    }

    @Test
    fun aKeyInAVaultCannotBeRemovedAndNoOwnerCheckIsAsked(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1).saved(P2, 2)
        val wallets = phone.unlocked()
        val storage = FakeVaultsStorage()
        val book = book(storage, wallets).apply { sync() }
        book.edit { it.copy(vaults = listOf(vaultUsing(check(words(2))))) }
        val read = phone.place(P2).served.size
        assertEquals(WalletRemoval.InVault, wallets.remove(wallets.at(P2), book, "test") { error("not gone") })
        assertEquals(read, phone.place(P2).served.size, "a refused removal asks for no owner check")
        assertEquals(0, phone.place(P2).erases)
        assertEquals(listOf(P1, P2), wallets.all.map { it.place })
    }

    @Test
    fun aVaultRemovalThatCouldNotBeSavedStillKeepsTheWalletWhoseKeyItUses(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1).saved(P2, 2)
        val wallets = phone.unlocked()
        val storage = FakeVaultsStorage()
        val book = book(storage, wallets).apply { sync() }
        val vault = vaultUsing(check(words(2)))
        book.edit { it.copy(vaults = listOf(vault)) }

        storage.failWrite = true // a full disk, say: the book cannot be written, while a wallet's own record could still be erased
        assertFalse(VaultDraft(book).remove(vault.id))
        assertEquals(true, book.usesKey(check(words(2))), "the vault still uses wallet 2's key")
        assertEquals(WalletRemoval.InVault, wallets.remove(wallets.at(P2), book, "test") { error("not gone") })
        assertEquals(0, phone.place(P2).erases, "wallet 2's words are not erased")
    }

    @Test
    fun aBookThatCannotBeJudgedRefusesARemovalWithoutAnOwnerCheck(): Unit = runBlocking {
        val cases = listOf(
            "damaged" to FakeVaultsStorage("not a book"),
            "written by a newer app" to FakeVaultsStorage("MV3\n8 0a1b2c3d\n"),
            "not readable this moment" to FakeVaultsStorage().apply { unreadable = true },
        )
        for ((case, storage) in cases) {
            val phone = ShelfPhone().saved(P1, 1).saved(P2, 2)
            val wallets = phone.unlocked()
            val before = storage.text
            val book = book(storage, wallets)
            val read = phone.place(P2).served.size
            assertEquals(WalletRemoval.InVault, wallets.remove(wallets.at(P2), book, "test") { error("not gone") }, case)
            assertEquals(read, phone.place(P2).served.size, "no owner check, $case")
            assertEquals(0, phone.place(P2).erases, "nothing erased, $case")
            assertEquals(before, storage.text, "the book is left alone, $case")
        }
    }

    @Test
    fun theOnlyWalletCannotBeRemoved(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1)
        val wallets = phone.unlocked()
        val book = book(FakeVaultsStorage(), wallets).apply { sync() }
        assertEquals(WalletRemoval.NotErased, wallets.remove(wallets.at(P1), book, "test") { error("not gone") })
        assertEquals(0, phone.place(P1).erases)
    }

    @Test
    fun aRefusedOwnerCheckRemovesNothingAndTheBookIsNotHandedOver(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1).saved(P2, 2)
        val wallets = phone.unlocked()
        val storage = FakeVaultsStorage()
        val book = book(storage, wallets).apply { sync() }
        book.edit { it.copy(keys = listOf(HARDWARE)) }
        val writes = storage.writes
        phone.place(P1).read = ReadResult.Cancelled
        val removal = wallets.remove(wallets.at(P1), book, "test") { error("not gone") }
        assertEquals(UnlockResult.Cancelled, assertIs<WalletRemoval.Refused>(removal).result)
        assertEquals(writes, storage.writes, "the book stays with its owner")
        assertEquals(0, phone.place(P1).erases)
    }

    @Test
    fun aHandOverThatFailsRemovesNothing(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1).saved(P2, 2)
        val wallets = phone.unlocked()
        val storage = FakeVaultsStorage()
        val book = book(storage, wallets).apply { sync() }
        book.edit { it.copy(keys = listOf(HARDWARE)) }
        storage.failWrite = true
        assertEquals(WalletRemoval.NotErased, wallets.remove(wallets.at(P1), book, "test") { error("not gone") })
        assertEquals(0, phone.place(P1).erases, "the wallet stays while the book cannot move")
        assertTrue(wallets.at(P1).session.words == words(1), "nothing left memory")
    }

    @Test
    fun aFailedEraseKeepsTheWalletInMemory(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1).saved(P2, 2)
        phone.place(P1).erase = failedErase(Why.Other)
        val wallets = phone.unlocked()
        val book = book(FakeVaultsStorage(), wallets).apply { sync() }
        assertEquals(WalletRemoval.NotErased, wallets.remove(wallets.at(P1), book, "test") { error("not gone") })
        assertEquals(listOf(P1, P2), wallets.all.map { it.place })
        assertEquals(words(1), wallets.at(P1).session.words)
        wallets.at(P1).session.markBackupConfirmed()
        assertEquals(SaveState.Saved, wallets.at(P1).session.saved, "the session keeps its copy's version after the failed erase")
    }

    @Test
    fun anOlderGuardMovesBeforeARemovalAndARefusalKeepsTheWallet(): Unit = runBlocking {
        val pending = UnconfirmedSend(TEST_TXID, SENT_AT)
        val a = recordOf(2)
        val phone = ShelfPhone().saved(P1, 1)
        phone.place(P2).write(oldRecordBytesFor(a, pending), WriteMode.Create)
        phone.place(P2).writes.clear()
        phone.guards.unreadable = true // at the unlock the guard file cannot take it, so the record keeps it
        val wallets = phone.unlocked()
        val book = book(FakeVaultsStorage(), wallets).apply { sync() }
        assertEquals(WalletRemoval.NotErased, wallets.remove(wallets.at(P2), book, "test") { error("not gone") }, "while the guard file cannot take it")
        assertEquals(0, phone.place(P2).erases)

        phone.guards.unreadable = false
        assertEquals(WalletRemoval.Removed, wallets.remove(wallets.at(P2), book, "test") {})
        assertEquals(pending, phone.guards.guardOf(a.fingerprint), "the guard outlives the wallet, so the same words refuse until it settles")
        assertEquals(1, phone.place(P2).erases)
    }

    @Test
    fun aPlaceThatNoLongerHoldsThisWalletIsNotErased(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1).saved(P2, 2)
        val wallets = phone.unlocked()
        val book = book(FakeVaultsStorage(), wallets).apply { sync() }
        phone.place(P2).read = ReadResult.Bytes(v1Bytes(), ANY_VERSION) // another wallet's record now sits at this place
        assertEquals(WalletRemoval.NotErased, wallets.remove(wallets.at(P2), book, "test") { error("not gone") })
        assertEquals(0, phone.place(P2).erases)
    }

    @Test
    fun aWalletThatWasNeverSavedIsNotRemoved(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1)
        val wallets = phone.unlocked()
        val w = assertNotNull(wallets.beginAdd())
        phone.place(P2).write = WriteResult.Unavailable(Why.Other)
        phone.nextWords = words(2)
        w.session.generateThisDevice()
        assertEquals(SaveState.NotSaved, w.session.saved)
        val removal = wallets.remove(w, book(FakeVaultsStorage(), wallets).apply { sync() }, "test") {}
        assertEquals(WalletRemoval.NotErased, removal, "a key held only in memory is not dropped by Remove")
        assertEquals(words(2), w.session.words)
        assertTrue(w in wallets.listed)
    }

    @Test
    fun theBookIsNotHandedToAWalletStillInSetup(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1)
        val wallets = phone.unlocked()
        val storage = FakeVaultsStorage()
        val book = book(storage, wallets).apply { sync() }
        book.edit { it.copy(keys = listOf(HARDWARE), vaults = listOf(vaultUsing(HARDWARE.fingerprint))) }
        val w = assertNotNull(wallets.beginAdd())
        phone.nextWords = words(2)
        w.session.generateThisDevice() // saved, and its key may still change in setup
        assertEquals(WalletRemoval.NotErased, wallets.remove(wallets.at(P1), book, "test") {})
        assertEquals(0, phone.place(P1).erases)
        w.session.changeNewKeyPassphrase("pw")
        book.sync()
        assertEquals(1, book.current.vaults.size, "the vault of hardware keys survives")
        assertEquals(0, storage.deletes, "the stored book is never deleted")
    }

    @Test
    fun theBookIsHandedToTheKeyThePhoneHoldsAfterAPassphraseSaveFailed(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1)
        val wallets = phone.unlocked()
        val storage = FakeVaultsStorage()
        val book = book(storage, wallets).apply { sync() }
        book.edit { it.copy(keys = listOf(HARDWARE)) }
        val w = assertNotNull(wallets.beginAdd())
        phone.nextWords = words(2)
        w.session.generateThisDevice()
        phone.place(P2).write = WriteResult.Unavailable(Why.Other)
        w.session.changeNewKeyPassphrase("pw") // the phone keeps the copy without it
        w.session.confirmAddressType()
        endSetup(w.session) // a later wallet has no App Lock read-back
        assertEquals(WalletRemoval.Removed, wallets.remove(wallets.at(P1), book, "test") {})

        val next = book(storage, phone.unlocked()).apply { sync() }
        assertEquals(listOf(HARDWARE), next.current.keys, "the next launch opens wallet 2 by the key the phone holds, and its book")
    }

    @Test
    fun aRemovalWaitsWhileTheBookCannotBeSavedForAKeyThatChangedAndTheBookOutlivesTheNextLaunch(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P2, 2).saved(P3, 3)
        val wallets = phone.unlocked()
        val w = assertNotNull(wallets.beginAdd())
        phone.nextWords = words(1)
        w.session.generateThisDevice()
        val storage = FakeVaultsStorage()
        val book = book(storage, wallets).apply { sync() }
        book.edit { it.copy(keys = listOf(HARDWARE)) }
        assertEquals(check(words(1)), storedOwner(storage), "the book names the wallet at place 1, still in setup")
        storage.failWrite = true
        w.session.changeNewKeyPassphrase("pw")
        book.sync() // the move to the new key is not saved
        assertEquals(WalletRemoval.NotErased, wallets.remove(wallets.at(P2), book, "test") { error("not gone") })
        assertEquals(0, phone.place(P2).erases, "wallet 2 stays while the book cannot be saved")

        storage.failWrite = false
        val next = book(storage, phone.unlocked()).apply { sync() }
        assertEquals(listOf(HARDWARE), next.current.keys, "the next launch keeps the book its wallet saved before the passphrase")
    }

    @Test
    fun aRemovalWaitsForAnEditTheBookCouldNotSaveAndGoesAheadOnceItSaves(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1).saved(P2, 2)
        val wallets = phone.unlocked()
        val storage = FakeVaultsStorage()
        val book = book(storage, wallets).apply { sync() }
        storage.failWrite = true
        book.edit { it.copy(keys = listOf(HARDWARE)) }
        assertEquals(WalletRemoval.NotErased, wallets.remove(wallets.at(P2), book, "test") { error("not gone") })
        assertEquals(0, phone.place(P2).erases, "nothing is erased while the book holds a change the phone does not")

        storage.failWrite = false
        assertEquals(WalletRemoval.Removed, wallets.remove(wallets.at(P2), book, "test") {})
        assertEquals(listOf(HARDWARE), book(storage, phone.unlocked()).apply { sync() }.current.keys, "the edit was saved before the erase")
    }

    @Test
    fun aCopyThatCanNeverOpenIsNotRemovedBecauseItsWordsAreOnlyInMemory(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1).saved(P2, 2)
        val wallets = phone.unlocked()
        val book = book(FakeVaultsStorage(), wallets).apply { sync() }
        phone.place(P2).read = ReadResult.KeyGone
        assertEquals(WalletRemoval.NotErased, wallets.remove(wallets.at(P2), book, "test") { error("not gone") })
        assertEquals(0, phone.place(P2).erases)
        assertEquals(words(2), wallets.at(P2).session.words)
    }

    @Test
    fun theBookIsSavedForEverySavedWalletAndJudgedOnlyWhenAllIsKnown(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1).saved(P2, 2)
        val wallets = phone.launch()
        assertNull(wallets.vaultOwners(), "before the unlock no wallet is saved")
        wallets.unlockEvery("test")
        assertEquals(VaultOwners(listOf(check(words(1)), check(words(2))), everyPlaceOpened = true), wallets.vaultOwners())
    }

    @Test
    fun aWalletHeldOnlyInMemoryMakesTheBooksOwnerUnknown() {
        val phone = ShelfPhone()
        phone.place(P1).write = WriteResult.Unavailable(Why.Other)
        val wallets = phone.launch()
        wallets.at(P1).session.generateThisDevice()
        assertNull(wallets.vaultOwners(), "a wallet that could not be saved is no owner")
    }

    @Test
    fun aSavedWalletBesideOneThatCouldNotSaveKeepsTheBookFromBeingDeleted(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1)
        val wallets = phone.unlocked()
        val w = wallets.beginAdd()!!
        phone.place(w.place).write = WriteResult.Unavailable(Why.Other)
        w.session.generateThisDevice()
        val owners = wallets.vaultOwners()!!
        assertEquals(listOf(check(words(1))), owners.fingerprints)
        assertFalse(owners.everyPlaceOpened, "a wallet in memory that could not be saved might be the book's owner")
    }

    @Test
    fun theKeyringListsEveryWalletsKeyInPlaceOrderAndCannotDeleteThem(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1).saved(P2, 2)
        val wallets = phone.unlocked()
        val book = book(FakeVaultsStorage(), wallets).apply { sync() }
        val keyring = KeyringStore(book = book, ownKeys = { wallets.ownKeys() })
        book.edit { it.copy(keys = listOf(HARDWARE)) }
        assertEquals(listOf(check(words(1)), check(words(2)), HARDWARE.fingerprint), keyring.keys.map { it.fingerprint })
        val own = keyring.keys.first()
        assertFalse(keyring.tryDelete(own.id), "a wallet's own key is removed with the wallet, not on the keyring")
        assertEquals(3, keyring.keys.size)
        assertTrue(keyring.tryDelete(HARDWARE.id), "a saved key can be deleted")
        assertEquals(listOf(check(words(1)), check(words(2))), keyring.keys.map { it.fingerprint })
    }

    @Test
    fun aSavedKeyThatIsAlsoAWalletsKeyShowsOnce(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1).saved(P2, 2)
        val wallets = phone.unlocked()
        val book = book(FakeVaultsStorage(), wallets).apply { sync() }
        val dup = HARDWARE.copy(fingerprint = check(words(2)))
        book.edit { it.copy(keys = listOf(dup)) }
        val keyring = KeyringStore(book = book, ownKeys = { wallets.ownKeys() })
        assertEquals(listOf(check(words(1)), check(words(2))), keyring.keys.map { it.fingerprint })
    }

    @Test
    fun aWalletMidSetupIsListedOnlyOnceItHoldsARealKey(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1)
        val wallets = phone.unlocked()
        val w = wallets.beginAdd()!!
        assertFalse(w in wallets.listed)
        phone.nextWords = words(5)
        w.session.generateThisDevice()
        assertTrue(w in wallets.listed)
    }

    @Test
    fun aListRefreshReadsEachWalletOnceSkipsARecentOneAndStopsWhenAHostLimits(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1).saved(P2, 2).saved(P3, recordOf(3, addressTypeConfirmed = false))
        val wallets = phone.unlocked()
        wallets.refreshBalances(vaults = emptyList())
        assertEquals(listOf(check(words(1)), check(words(2))), phone.binds, "the unfinished wallet is left alone")
        assertTrue(wallets.at(P1).chain.boundWithin(60))

        phone.binds.clear()
        phone.nowSeconds += 30
        wallets.refreshBalances(vaults = emptyList())
        assertTrue(phone.binds.isEmpty(), "a wallet read in the last minute is not asked again")

        phone.binds.clear()
        phone.nowSeconds += 61
        phone.scanResult = HotScan.CONTINUING
        wallets.refreshBalances(vaults = emptyList())
        assertEquals(listOf(check(words(1))), phone.binds, "a host that limits one wallet would limit the next")
    }

    @Test
    fun aListRefreshReadsVaultsAfterEveryOneKeyWalletOneAtATime(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1).saved(P2, 2)
        val wallets = phone.unlocked()
        val bindsBeforeEachVault = mutableListOf<Int>()
        phone.duringVaultRead = { bindsBeforeEachVault += phone.binds.size }
        wallets.refreshBalances(listOf(vault(1), vault(2)))
        assertEquals(listOf(check(words(1)), check(words(2))), phone.binds)
        assertEquals(listOf(2, 2), bindsBeforeEachVault, "both one-key wallets are read before the first vault")
        assertEquals(listOf(vaultKeys(1) to 2.toUByte(), vaultKeys(2) to 2.toUByte()), phone.vaultReads, "each vault once, in book order, with its own keys and m")
        assertEquals(84_200_000L, wallets.vaultSats(vault(1)))
    }

    @Test
    fun aVaultReadInTheLastMinuteIsNotAskedAgainAndAFailedReadCountsAsRead(): Unit = runBlocking {
        val phone = ShelfPhone()
        val wallets = phone.launch()
        wallets.refreshBalances(listOf(vault(1)))
        phone.nowSeconds += 30
        wallets.refreshBalances(listOf(vault(1)))
        assertEquals(1, phone.vaultReads.size, "a vault read in the last minute is not asked again")

        phone.vaultFails = true
        phone.nowSeconds += 61
        wallets.refreshBalances(listOf(vault(1)))
        assertEquals(2, phone.vaultReads.size)
        assertNull(wallets.vaultSats(vault(1)), "a failed read leaves the figure unknown, never the last one")

        phone.nowSeconds += 30
        wallets.refreshBalances(listOf(vault(1)))
        assertEquals(2, phone.vaultReads.size, "a failed read is fresh for a minute, as a failed bind is")
    }

    @Test
    fun aOneKeyScanThatContinuesLeavesEveryVaultUnread(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1)
        val wallets = phone.unlocked()
        phone.scanResult = HotScan.CONTINUING
        wallets.refreshBalances(listOf(vault(1)))
        assertEquals(listOf(check(words(1))), phone.binds)
        assertTrue(phone.vaultReads.isEmpty(), "a host that limits one wallet would limit the vault next")
        assertNull(wallets.vaultSats(vault(1)))
    }

    @Test
    fun aVaultScanThatContinuesEndsThePassAndItsCardIsADash(): Unit = runBlocking {
        val phone = ShelfPhone()
        val wallets = phone.launch()
        phone.vaultScan = HotScan.CONTINUING
        wallets.refreshBalances(listOf(vault(1), vault(2)))
        assertEquals(listOf(vaultKeys(1) to 2.toUByte()), phone.vaultReads, "a host that limits one vault would limit the next")
        assertNull(wallets.vaultSats(vault(1)), "part of a vault never reads as all of it")

        phone.vaultScan = HotScan.UNVERIFIED
        phone.nowSeconds += 61
        wallets.refreshBalances(listOf(vault(1), vault(2)))
        assertEquals(listOf(1, 1, 2).map { vaultKeys(it) to 2.toUByte() }, phone.vaultReads, "a host that lied ends no pass")
        assertNull(wallets.vaultSats(vault(1)))
        assertNull(wallets.vaultSats(vault(2)), "a figure a host lied about is a dash")
    }

    @Test
    fun aVaultsFigureFollowsItsAddressNotItsId(): Unit = runBlocking {
        val phone = ShelfPhone()
        val wallets = phone.launch()
        wallets.refreshBalances(listOf(vault(1)))
        assertEquals(84_200_000L, wallets.vaultSats(vault(1).copy(id = "vault_9")), "a renumbered vault keeps its figure")
        assertNull(wallets.vaultSats(vault(1).copy(address = "tb1qother")), "another vault never shows this one's figure")
    }

    @Test
    fun eraseDropsEveryVaultFigureAndALateReadWritesNothingBack(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1)
        val wallets = phone.unlocked()
        wallets.refreshBalances(listOf(vault(1)))
        assertEquals(84_200_000L, wallets.vaultSats(vault(1)))
        assertTrue(wallets.forgetEvery())
        assertNull(wallets.vaultSats(vault(1)), "an erase drops every vault figure")

        var erasedDuringRead: Boolean? = null
        phone.duringVaultRead = { erasedDuringRead = wallets.forgetEvery() }
        wallets.refreshBalances(listOf(vault(1)))
        assertEquals(true, erasedDuringRead, "the erase ran while the read was out")
        assertNull(wallets.vaultSats(vault(1)), "a read that ends after the erase leaves nothing")
    }

    // The independent review's probe: an erase that landed after the read's generation check and before its write left the figure behind.
    @Test
    fun anEraseJustBeforeAReadsWriteLeavesNoFigure(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1)
        val wallets = phone.unlocked()
        var erased: Boolean? = null
        phone.onWalletsNow = { if (erased == null) erased = wallets.forgetEvery() }
        wallets.refreshBalances(listOf(vault(1)))
        assertEquals(true, erased, "the erase ran after the network answered")
        assertNull(wallets.vaultSats(vault(1)), "a read that ends after the erase writes nothing back")
    }

    @Test
    fun twoScreensAtOnceReadOneVaultOnce(): Unit = runBlocking {
        val phone = ShelfPhone()
        val wallets = phone.launch()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        phone.duringVaultRead = { entered.complete(Unit); runBlocking { release.await() } }
        val list = launch(Dispatchers.Default) { wallets.refreshBalances(listOf(vault(1))) }
        entered.await()
        phone.duringVaultRead = {}
        val chooser = launch(Dispatchers.Default) { wallets.refreshBalances(listOf(vault(1))) }
        delay(300)
        release.complete(Unit)
        list.join()
        chooser.join()
        assertEquals(1, phone.vaultReads.size, "the second screen waits for the first read and finds it fresh")
        assertEquals(84_200_000L, wallets.vaultSats(vault(1)))
    }

    @Test
    fun aListPassNeverAsksAVaultsHistory(): Unit = runBlocking {
        val phone = ShelfPhone()
        val wallets = phone.launch()
        wallets.refreshBalances(listOf(vault(1), vault(2)))
        assertEquals(2, phone.vaultReads.size)
        assertTrue(phone.historyReads.isEmpty(), "history would multiply page requests across every vault a list shows")
        assertNull(wallets.vaultTxs(vault(1)))
    }

    @Test
    fun aDetailReadAsksTheHistoryOnlyAfterABalanceThatReachedTheEndOfBothChains(): Unit = runBlocking {
        val phone = ShelfPhone()
        val wallets = phone.launch()
        for (scan in listOf(HotScan.CONTINUING, HotScan.UNVERIFIED)) {
            phone.vaultScan = scan
            phone.nowSeconds += 61
            wallets.readVaultDetail(vault(1))
            assertTrue(phone.historyReads.isEmpty(), "$scan asks no history")
            assertNull(wallets.vaultTxs(vault(1)), "$scan")
        }
        phone.vaultFails = true
        phone.nowSeconds += 61
        wallets.readVaultDetail(vault(1))
        assertTrue(phone.historyReads.isEmpty(), "a failed balance asks no history")

        phone.vaultFails = false
        phone.vaultScan = HotScan.COMPLETE
        phone.nowSeconds += 61
        wallets.readVaultDetail(vault(1))
        assertEquals(listOf(vaultKeys(1) to 2.toUByte()), phone.historyReads, "its own keys and m")
        assertEquals(phone.vaultTxsOnChain, wallets.vaultTxs(vault(1)))
        assertEquals(84_200_000L, wallets.vaultSats(vault(1)))
    }

    @Test
    fun aDetailReadInTheLastMinuteIsNotAskedAgainAndAFailedHistoryCountsAsRead(): Unit = runBlocking {
        val phone = ShelfPhone()
        val wallets = phone.launch()
        phone.historyFails = true
        wallets.readVaultDetail(vault(1))
        assertNull(wallets.vaultTxs(vault(1)), "a failed history is unknown, never empty")
        assertEquals(84_200_000L, wallets.vaultSats(vault(1)), "and costs the balance nothing")
        phone.historyFails = false
        phone.nowSeconds += 30
        wallets.readVaultDetail(vault(1))
        assertEquals(1 to 1, phone.vaultReads.size to phone.historyReads.size, "a failed history is fresh for a minute")
        phone.nowSeconds += 31
        wallets.readVaultDetail(vault(1))
        assertEquals(2 to 2, phone.vaultReads.size to phone.historyReads.size)
        assertEquals(phone.vaultTxsOnChain, wallets.vaultTxs(vault(1)))
    }

    @Test
    fun aDetailAfterACompleteListReadAsksTheHistoryOnceAndAfterAnIncompleteOneAsksNothing(): Unit = runBlocking {
        val phone = ShelfPhone()
        val wallets = phone.launch()
        wallets.refreshBalances(listOf(vault(1)))
        phone.nowSeconds += 20
        wallets.readVaultDetail(vault(1))
        assertEquals(2 to 1, phone.vaultReads.size to phone.historyReads.size, "the balance again, whose sync the history shares, then the history")
        wallets.readVaultDetail(vault(1))
        assertEquals(2 to 1, phone.vaultReads.size to phone.historyReads.size, "then nothing for the rest of the minute")

        phone.vaultScan = HotScan.CONTINUING
        wallets.refreshBalances(listOf(vault(2)))
        phone.nowSeconds += 20
        wallets.readVaultDetail(vault(2))
        assertEquals(3 to 1, phone.vaultReads.size to phone.historyReads.size, "a host that limited the list's read is left alone for the minute")
    }

    @Test
    fun aBalanceAndTheRowsBesideItAlwaysComeFromOneRead(): Unit = runBlocking {
        val phone = ShelfPhone()
        val wallets = phone.launch()
        wallets.readVaultDetail(vault(1))
        assertNotNull(wallets.vaultTxs(vault(1)), "the detail's own rows")
        phone.nowSeconds += 61
        phone.vaultSatsOnChain = 90_000_000uL
        wallets.refreshBalances(listOf(vault(1)))
        assertEquals(90_000_000L, wallets.vaultSats(vault(1)))
        assertNull(wallets.vaultTxs(vault(1)), "the older rows go with the older balance")
    }

    @Test
    fun aVaultsRowsFollowItsAddressNotItsId(): Unit = runBlocking {
        val phone = ShelfPhone()
        val wallets = phone.launch()
        wallets.readVaultDetail(vault(1))
        assertEquals(phone.vaultTxsOnChain, wallets.vaultTxs(vault(1).copy(id = "vault_9")), "a renumbered vault keeps its rows")
        assertNull(wallets.vaultTxs(vault(1).copy(address = "tb1qother")), "another vault never shows this one's rows")
    }

    @Test
    fun eraseDropsEveryVaultsRowsAndAHistoryThatEndsAfterItWritesNothingBack(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1)
        val wallets = phone.unlocked()
        wallets.readVaultDetail(vault(1))
        assertNotNull(wallets.vaultTxs(vault(1)), "the rows before the erase")
        assertTrue(wallets.forgetEvery())
        assertNull(wallets.vaultTxs(vault(1)), "an erase drops the rows with the figure")

        var erased: Boolean? = null
        phone.duringHistoryRead = { erased = wallets.forgetEvery() }
        wallets.readVaultDetail(vault(1))
        assertEquals(true, erased, "the erase ran while the history was out")
        assertNull(wallets.vaultSats(vault(1)))
        assertNull(wallets.vaultTxs(vault(1)), "a history that ends after the erase leaves nothing")
    }

    @Test
    fun aDetailReadWaitsForAListReadOfTheSameVault(): Unit = runBlocking {
        val phone = ShelfPhone()
        val wallets = phone.launch()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var overlapped = false
        var out = 0
        phone.duringVaultRead = {
            if (++out > 1) overlapped = true
            entered.complete(Unit)
            runBlocking { release.await() }
            out--
        }
        val list = launch(Dispatchers.Default) { wallets.refreshBalances(listOf(vault(1))) }
        entered.await()
        val detail = launch(Dispatchers.Default) { wallets.readVaultDetail(vault(1)) }
        delay(300)
        assertTrue(phone.historyReads.isEmpty(), "the detail waits while the list's read is out")
        release.complete(Unit)
        list.join()
        detail.join()
        assertFalse(overlapped, "one read at a time")
        assertEquals(1, phone.historyReads.size, "the detail's history, after the list's read")
    }

    @Test
    fun theWalletInUseIsRememberedOnlyWhenItChanges() {
        val phone = ShelfPhone()
        val wallets = phone.launch()
        wallets.noteUsed(P2)
        wallets.noteUsed(P2)
        assertEquals(1, phone.last.writes, "writes only on a change")
        assertEquals("2", phone.last.text)
        wallets.noteUsed(P3)
        assertEquals("3", phone.last.text)
    }

    @Test
    fun aLaterProcessRemembersTheLastWallet(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1).saved(P2, 2)
        val first = phone.unlocked()
        first.noteUsed(P2)
        val every = assertIs<Every.Open>(phone.launch().unlockEvery("test"))
        assertSame(P2, every.resume.place)
    }

    @Test
    fun aFirstRunPhoneStartsWithOneEmptyWalletAtPlaceOne() {
        val wallets = ShelfPhone().launch()
        assertEquals(listOf(P1), wallets.all.map { it.place })
        assertEquals(emptyList(), wallets.listed)
        assertNull(wallets.addRefusal(), "the empty first wallet can always be filled")
        assertSame(wallets.at(P1), wallets.beginAdd(), "the first add fills the empty entry")
    }

    @Test
    fun aPhraseIsNeverImportedIntoTheAddressTypeOfAHardwareWalletOfItsKey(): Unit = runBlocking {
        val phone = ShelfPhone().saved(P1, 1)
        phone.derive = { w, p -> if (w == PUBLIC_WORDS_12 && p.isEmpty()) PUBLIC_FINGERPRINT else check(w, p) }
        phone.bookHardware = listOf(PUBLIC_JADE_WALLET)
        val w = assertNotNull(phone.unlocked().beginAdd())

        w.session.restoreFromWords(PUBLIC_WORDS_12)
        assertEquals(duplicateLine(PUBLIC_JADE_WALLET.name), w.session.lastError, "Native SegWit, where an import starts, has the Jade wallet's addresses")
        assertTrue(w.session.words.isEmpty(), "nothing of the import is kept")
        assertTrue(phone.place(w.place).writes.isEmpty(), "and nothing is saved, so no second guard can cover those addresses")

        val taproot = PUBLIC_ACCOUNTS.getValue(AddressType.TAPROOT)
        phone.bookHardware = listOf(PUBLIC_JADE_WALLET.copy(addressType = AddressType.TAPROOT, xpub = taproot.tpub, address = taproot.receive0))
        w.session.restoreFromWords(PUBLIC_WORDS_12)
        assertNull(w.session.lastError, "with the Jade wallet in Taproot, Native SegWit has other addresses")
        assertEquals(PUBLIC_JADE_WALLET.name, w.session.takenBy(AddressType.TAPROOT))
        w.session.chooseAddressType(AddressType.TAPROOT)
        assertEquals(AddressType.NATIVE_SEGWIT, w.session.addressType, "the Jade wallet's type cannot be picked")
        w.session.chooseAddressType(AddressType.LEGACY)
        assertEquals(AddressType.LEGACY, w.session.addressType)
    }

    private fun book(storage: FakeVaultsStorage, wallets: Wallets) =
        SavedVaults(storage, owners = { wallets.vaultOwners() }, deriveAddress = { _, _ -> "addr" })

    private fun vaultUsing(fingerprint: String) =
        CreatedVault("v1", "Vault 1", 1, 1, CustodyPolicy.PersonalCustody, "addr", "x\nwsh(sortedmulti(1,tpub))", listOf(fingerprint), hasMockKey = true)
}

/** The bytes of [record] as a build before the guard file saved it, with [pending] as its guard (flag 8). */
private fun oldRecordBytesFor(record: WalletRecord, pending: UnconfirmedSend): ByteArray =
    v1Bytes(words = record.words.joinToString(" ").encodeToByteArray(), flags = 8 or 1, fingerprint = record.fingerprint.encodeToByteArray()) +
        sendSection(pending.txid.encodeToByteArray(), pending.sinceSeconds)

private val HARDWARE = SavedKey("key_1_aaaa0000", "Jade", "aaaa0000", "tpub-a", "m/48'/1'/0'/2'", KeySourceLabel.Jade, verified = true)
