package com.mebit.app.data

import com.mebit.ffi.signer.AddressType
import com.mebit.ffi.signer.BleException
import com.mebit.ffi.signer.FoundWallet
import com.mebit.ffi.signer.PairingCodes
import com.mebit.ffi.signer.WalletKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal val SAFE7 = FoundWallet(WalletKind.TREZOR_SAFE7, "ble-safe7", "Safe 7")
internal val JADE_FOUND = FoundWallet(WalletKind.JADE, "ble-jade", "Jade 1a2b3c")
internal val NO_CODES = object : PairingCodes {
    override suspend fun pairingCode(): String? = null
}

private const val PATH_84 = "m/84'/1'/0'"

class HardwareLinkTest {
    @Test
    fun theLinkIsDisconnectedAndClosedOnEveryPath(): Unit = runBlocking {
        for (end in listOf("done", "connect", "unlock", "accountXpub", "another key", "the job")) {
            val link = FakeDeviceLink(failAt = end.takeIf { it in setOf("connect", "unlock", "accountXpub") })
            val outcome = runCatching {
                HardwareLink(FakePairings()) { link }.use(JADE_FOUND, PATH_84, expect = if (end == "another key") "11112222" else null, NO_CODES) {
                    if (end == "the job") throw IllegalStateException("the job failed")
                    "signed"
                }
            }
            assertEquals(end == "done", outcome.isSuccess, end)
            assertEquals(listOf("disconnect", "close"), link.calls.takeLast(2), end)
            assertEquals(1, link.calls.count { it == "close" }, end)
        }

        val link = FakeDeviceLink()
        val started = CompletableDeferred<Unit>()
        val job = launch { HardwareLink(null) { link }.use(JADE_FOUND, PATH_84, null, NO_CODES) { started.complete(Unit); awaitCancellation() } }
        started.await()
        job.cancel()
        job.join()
        assertEquals(listOf("disconnect", "close"), link.calls.takeLast(2), "a cancelled job")
    }

    @Test
    fun aDeviceHoldingAnotherKeyIsRefusedBeforeTheJob(): Unit = runBlocking {
        val link = FakeDeviceLink(fingerprint = PUBLIC_FINGERPRINT)
        var ran = false
        assertFailsWith<BleException.WrongKey> {
            HardwareLink(null) { link }.use(JADE_FOUND, PATH_84, expect = "11112222", NO_CODES) { ran = true }
        }
        assertFalse(ran)
        assertEquals(listOf("connect", "unlock", "accountXpub $PATH_84", "disconnect", "close"), link.calls, "only the key was read")

        val same = FakeDeviceLink(fingerprint = PUBLIC_FINGERPRINT)
        assertEquals(PUBLIC_FINGERPRINT, HardwareLink(null) { same }.use(JADE_FOUND, PATH_84, expect = PUBLIC_FINGERPRINT.uppercase(), NO_CODES) { it.key.masterFingerprint })
    }

    @Test
    fun aCodePairingIsKeptUnderTheExpectedKeyAndANewKeysGoesToTheJob(): Unit = runBlocking {
        val pairings = FakePairings()
        val fresh = byteArrayOf(1, 2, 3)
        HardwareLink(pairings) { FakeDeviceLink(fresh = fresh) }.use(SAFE7, PATH_84, expect = PUBLIC_FINGERPRINT, NO_CODES) {}
        assertContentEquals(byteArrayOf(1, 2, 3), pairings.of(PUBLIC_FINGERPRINT))
        assertTrue(fresh.all { it == 0.toByte() }, "the credential the link returned is zeroed")

        val unknown = byteArrayOf(4)
        val link = FakeDeviceLink(fingerprint = "11112222", fresh = unknown)
        val handed = HardwareLink(pairings) { link }.use(SAFE7, TESTNET_MULTISIG_ACCOUNT, expect = null, NO_CODES) { it.pairing?.copyOf() }
        assertNull(link.offeredAtConnect, "no credential is offered for a key not known yet")
        assertContentEquals(byteArrayOf(4), handed, "a key no one expected is not saved yet, so the job gets its pairing")
        assertNull(pairings.of("11112222"), "and the link keeps it nowhere")
        assertTrue(unknown.all { it == 0.toByte() }, "zeroed once the job returned")

        val other = FakeDeviceLink(fingerprint = "11112222", fresh = byteArrayOf(5))
        assertFailsWith<BleException.WrongKey> { HardwareLink(pairings) { other }.use(SAFE7, PATH_84, expect = PUBLIC_FINGERPRINT, NO_CODES) {} }
        assertNull(pairings.of("11112222"), "a device of another key is refused, and its pairing kept nowhere")
        assertContentEquals(byteArrayOf(1, 2, 3), pairings.of(PUBLIC_FINGERPRINT))
    }

    @Test
    fun aSafe7PairingIsKeptOnlyForAKeyTheBookHolds(): Unit = runBlocking {
        val pairings = FakePairings()
        val book = SavedVaults.memoryOnly()
        val store = KeyringStore(book = book, pairings = pairings, openLink = { FakeDeviceLink(fresh = byteArrayOf(5, 5, 5)) })

        assertEquals(EnrollResult.Enrolled, store.enrollHardware(SAFE7))
        assertNull(pairings.of(PUBLIC_FINGERPRINT), "Add Key keeps nothing before Confirm Key saves the key")
        store.discardPending()
        assertNull(pairings.of(PUBLIC_FINGERPRINT), "Add Key left at Confirm Key leaves no pairing")

        assertEquals(EnrollResult.Enrolled, store.enrollHardware(SAFE7))
        assertTrue(store.confirmFingerprint(PUBLIC_FINGERPRINT))
        assertContentEquals(byteArrayOf(5, 5, 5), pairings.of(PUBLIC_FINGERPRINT), "kept once the key is saved")
        assertTrue(pairings.given.last().all { it == 0.toByte() }, "the copy held until then is zeroed")

        val another = KeyringStore(book = book, pairings = pairings, openLink = { FakeDeviceLink(fingerprint = "11112222", fresh = byteArrayOf(6)) })
        assertEquals(EnrollResult.Failed, another.enrollHardware(SAFE7, oneKeyPath(AddressType.NATIVE_SEGWIT), expect = store.keys.single()))
        assertNull(pairings.of("11112222"), "a one-key create refused for a device of another key keeps that device's pairing nowhere")
        assertContentEquals(byteArrayOf(5, 5, 5), pairings.of(PUBLIC_FINGERPRINT))
    }

    @Test
    fun confirmKeyKeepsNoPairingForAKeyTheBookCouldNotSave(): Unit = runBlocking {
        val me = { VaultOwners(listOf(TEST_CHECK), everyPlaceOpened = true) }
        val cases = listOf(
            Triple("a write that fails", FakeVaultsStorage().apply { failWrite = true }, VaultsHealth.SaveFailed),
            Triple("a book that cannot be read now", FakeVaultsStorage().apply { unreadable = true }, VaultsHealth.SaveFailed),
            Triple("a book a newer build wrote", FakeVaultsStorage("MV3\n"), VaultsHealth.Newer),
            Triple("a damaged book", FakeVaultsStorage("not a book"), VaultsHealth.Damaged),
        )
        for ((case, storage, why) in cases) {
            val pairings = FakePairings()
            val store = KeyringStore(book = SavedVaults(storage, owners = me).apply { sync() }, pairings = pairings, openLink = { FakeDeviceLink(fresh = byteArrayOf(5, 5, 5)) })
            assertEquals(EnrollResult.Enrolled, store.enrollHardware(SAFE7))

            val saved = store.confirmFingerprint(PUBLIC_FINGERPRINT)
            assertNull(pairings.of(PUBLIC_FINGERPRINT)?.toList(), "$case: a credential for a key the book did not save")
            assertFalse(saved, case)
            assertTrue(store.keys.none { it.fingerprint == PUBLIC_FINGERPRINT }, "$case: the Keyring lists no key it did not save")
            assertEquals(vaultsNoticeText(why), store.lastError, "$case: Confirm Key shows the book's line")
            assertFalse(store.lastMismatch, "$case: not a mismatch, so Confirm Key stays")
            assertNotNull(store.pending, "$case: the tap can be tried again")
        }

        val storage = FakeVaultsStorage().apply { failWrite = true }
        val pairings = FakePairings()
        val store = KeyringStore(book = SavedVaults(storage, owners = me).apply { sync() }, pairings = pairings, openLink = { FakeDeviceLink(fresh = byteArrayOf(5, 5, 5)) })
        assertEquals(EnrollResult.Enrolled, store.enrollHardware(SAFE7))
        assertFalse(store.confirmFingerprint(PUBLIC_FINGERPRINT))
        storage.failWrite = false
        assertTrue(store.confirmFingerprint(PUBLIC_FINGERPRINT), "the same tap once the book can be written")
        assertContentEquals(byteArrayOf(5, 5, 5), pairings.of(PUBLIC_FINGERPRINT), "kept with the key saved for good")
        assertEquals(listOf(PUBLIC_FINGERPRINT), SavedVaults(storage, owners = me).apply { sync() }.current.keys.map { it.fingerprint })
    }

    @Test
    fun theKeptCredentialGoesOnlyToTheSafe7OfTheExpectedKeyAndIsZeroed(): Unit = runBlocking {
        val pairings = FakePairings().apply { write(PUBLIC_FINGERPRINT, byteArrayOf(9, 9)) }
        val safe7 = FakeDeviceLink()
        HardwareLink(pairings) { safe7 }.use(SAFE7, PATH_84, expect = PUBLIC_FINGERPRINT, NO_CODES) {}
        assertContentEquals(byteArrayOf(9, 9), safe7.offeredAtConnect)
        assertTrue(assertNotNull(safe7.offered).all { it == 0.toByte() }, "zeroed once connect returned")
        assertContentEquals(byteArrayOf(9, 9), pairings.of(PUBLIC_FINGERPRINT), "a reconnect writes nothing")

        val jade = FakeDeviceLink()
        HardwareLink(pairings) { jade }.use(JADE_FOUND, PATH_84, expect = PUBLIC_FINGERPRINT, NO_CODES) {}
        assertNull(jade.offeredAtConnect, "a Jade never gets a Safe 7's secret")

        assertTrue(HardwareLink(pairings).keeps(PUBLIC_FINGERPRINT))
        assertFalse(HardwareLink(pairings).keeps("11112222"))
        assertTrue(pairings.served.all { array -> array.all { it == 0.toByte() } }, "every credential read was zeroed")
    }

    @Test
    fun aPairingThatFailsNeverDropsTheKeptCredential(): Unit = runBlocking {
        val pairings = FakePairings().apply { write(PUBLIC_FINGERPRINT, byteArrayOf(9, 9)) }
        for (step in listOf("connect", "unlock", "accountXpub")) {
            val link = FakeDeviceLink(fresh = byteArrayOf(1), failAt = step)
            assertFailsWith<BleException.Disconnected> { HardwareLink(pairings) { link }.use(SAFE7, PATH_84, PUBLIC_FINGERPRINT, NO_CODES) {} }
            assertContentEquals(byteArrayOf(9, 9), pairings.of(PUBLIC_FINGERPRINT), step)
        }
    }

    @Test
    fun addKeyReadsTheMultisigAccountShowsTheCodeFieldOnlyWhenAskedAndKeepsTheSafe7Pairing(): Unit = runBlocking {
        val pairings = FakePairings()
        val link = FakeDeviceLink(fresh = byteArrayOf(5), asksCode = true)
        val store = KeyringStore(book = SavedVaults.memoryOnly(), pairings = pairings, openLink = { link })
        val typing = launch {
            while (!store.pairingAsked) delay(10)
            store.pairingCode = "123456"
        }
        assertEquals(EnrollResult.Enrolled, withTimeout(5_000) { store.enrollHardware(SAFE7) })
        typing.join()
        assertEquals("123456", link.code, "the code typed after the Safe 7 asked")
        assertEquals(listOf("connect", "unlock", "accountXpub $TESTNET_MULTISIG_ACCOUNT", "disconnect", "close"), link.calls)
        val staged = assertNotNull(store.pending)
        assertEquals(listOf(PUBLIC_FINGERPRINT, "tpub-$PUBLIC_FINGERPRINT", TESTNET_MULTISIG_ACCOUNT), listOf(staged.fingerprint, staged.xpub, staged.derivationPath))
        assertEquals(KeySourceLabel.TrezorSafe7, staged.source)
        assertTrue(store.confirmFingerprint(PUBLIC_FINGERPRINT))
        assertContentEquals(byteArrayOf(5), pairings.of(PUBLIC_FINGERPRINT), "kept with the confirmed key")

        val jadeStore = KeyringStore(book = SavedVaults.memoryOnly(), pairings = pairings, openLink = { FakeDeviceLink(asksCode = true) })
        assertEquals(EnrollResult.Enrolled, jadeStore.enrollHardware(JADE_FOUND))
        assertFalse(jadeStore.pairingAsked, "a Jade never asks for a code")
    }

    @Test
    fun aFailedAddKeySetsTheErrorAndClosesTheLink(): Unit = runBlocking {
        val link = FakeDeviceLink(failAt = "unlock")
        val store = KeyringStore(book = SavedVaults.memoryOnly(), openLink = { link })
        assertEquals(EnrollResult.Failed, store.enrollHardware(JADE_FOUND))
        assertEquals("lost at unlock", store.lastError)
        assertNull(store.pending)
        assertEquals(listOf("disconnect", "close"), link.calls.takeLast(2))
        assertFalse(store.hardwareBusy)

        val unsupported = KeyringStore(book = SavedVaults.memoryOnly(), openLink = { throw BleException.WrongDevice("a Safe 3") })
        assertEquals(EnrollResult.UnsupportedModel, unsupported.enrollHardware(SAFE7))
        assertNull(unsupported.lastError)
    }

    @Test
    fun aSafe7PairingGoesWithTheLastKeyOrHardwareWalletOfItsFingerprint() {
        val pairings = FakePairings().apply { write(PUBLIC_FINGERPRINT, byteArrayOf(7)) }
        val jadeKey = SavedKey("key_1_$PUBLIC_FINGERPRINT", "Jade", PUBLIC_FINGERPRINT, "tpub-j", TESTNET_MULTISIG_ACCOUNT, KeySourceLabel.Jade, verified = true)
        val safe7Key = jadeKey.copy(id = "key_2_$PUBLIC_FINGERPRINT", label = "Safe 7", source = KeySourceLabel.TrezorSafe7)
        val book = SavedVaults.memoryOnly().apply { edit { it.copy(keys = listOf(jadeKey, safe7Key)) } }
        val store = KeyringStore(book = book, pairings = pairings)

        assertTrue(store.tryDelete(safe7Key.id))
        assertNotNull(pairings.of(PUBLIC_FINGERPRINT), "the Jade key of the same phrase still has that fingerprint")
        assertTrue(store.tryDelete(jadeKey.id))
        assertNull(pairings.of(PUBLIC_FINGERPRINT), "the last key of the fingerprint took the pairing with it")

        pairings.write(PUBLIC_FINGERPRINT, byteArrayOf(7))
        book.edit { it.copy(keys = listOf(safe7Key), hardware = listOf(PUBLIC_JADE_WALLET)) }
        assertTrue(store.tryDelete(safe7Key.id))
        assertNotNull(pairings.of(PUBLIC_FINGERPRINT), "a hardware wallet of the fingerprint keeps it")
    }
}
