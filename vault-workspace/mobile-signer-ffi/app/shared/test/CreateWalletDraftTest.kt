package com.mebit.app.data

import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.SoftwareAccountKey
import com.mebit.ffi.signer.computeVaultAddressMultisig
import com.mebit.ffi.signer.generateSoftwareMultisigKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CreateWalletDraftTest {
    private val calls = mutableListOf<Pair<List<String>, UByte>>()
    private val fake: (List<String>, UByte) -> String = { xpubs, m ->
        calls += xpubs to m
        "addr-$m-" + xpubs.joinToString("+")
    }

    private fun draft(book: SavedVaults = SavedVaults.memoryOnly(), derive: (List<String>, UByte) -> String = fake) = CreateWalletDraft(book, derive)

    private fun key(n: Int, verified: Boolean = true, mock: Boolean = false) =
        SavedKey("key_$n", "Key $n", "aaaa000$n", "xpub-$n", "m/48'/1'/0'/2'", KeySourceLabel.Jade, verified, isMock = mock)

    private val keys = (1..5).map { key(it) }

    private fun CreateWalletDraft.pick(vararg ids: Int) = ids.forEach { toggle("key_$it", keys) }

    /** The path every hardware or pasted add ends in: stage the key, then Confirm Key's match. */
    private fun KeyringStore.addConfirmed(key: SoftwareAccountKey) {
        assertTrue(startMebitOtherFromPaste("${key.masterFingerprint}\n${key.xpub}\n${key.derivationPath}"))
        assertTrue(confirmFingerprint(key.masterFingerprint))
    }

    @Test
    fun aNewDraftOpensAsPenpotDrawsIt() {
        val draft = draft()
        assertEquals(Signing.Multisig, draft.signing)
        assertEquals(Preset.TwoOfThree, draft.preset)
        assertEquals(emptyList(), draft.picks)
        assertNull(draft.ready(keys), "nothing is picked yet")
    }

    @Test
    fun theOnlyPresetsAre2Of3And3Of5() {
        assertEquals(listOf("2-of-3", "3-of-5"), Preset.entries.map { it.label })
        assertEquals(listOf(2 to 3, 3 to 5), Preset.entries.map { it.m to it.n })
    }

    @Test
    fun aTickPastNIsIgnoredASecondTapUnticksAndReadyNeedsExactlyN() {
        val draft = draft()
        draft.pick(1, 2)
        assertNull(draft.ready(keys), "two of three")
        draft.pick(3, 4)
        assertEquals(listOf("key_1", "key_2", "key_3"), draft.picks, "the fourth is ignored")
        assertNotNull(draft.ready(keys))
        draft.toggle("key_2", keys)
        assertEquals(listOf("key_1", "key_3"), draft.picks)
        assertNull(draft.ready(keys))
    }

    @Test
    fun choosingASmallerPresetDropsTheLatestPicksAndALargerOneKeepsAll() {
        val draft = draft()
        draft.choose(Preset.ThreeOfFive)
        draft.pick(1, 2, 3, 4, 5)
        assertNotNull(draft.ready(keys))
        draft.choose(Preset.TwoOfThree)
        assertEquals(listOf("key_1", "key_2", "key_3"), draft.picks)
        draft.choose(Preset.ThreeOfFive)
        assertEquals(listOf("key_1", "key_2", "key_3"), draft.picks, "a larger preset adds none")
        assertNull(draft.ready(keys), "3-of-5 needs five")
    }

    @Test
    fun switchingToSingleSigAndBackLosesNothing() {
        val draft = draft()
        draft.choose(Preset.ThreeOfFive)
        draft.pick(1, 2)
        draft.signing = Signing.SingleSig
        draft.signing = Signing.Multisig
        assertEquals(Preset.ThreeOfFive, draft.preset)
        assertEquals(listOf("key_1", "key_2"), draft.picks)
    }

    @Test
    fun aPlanHoldsTheKeysInKeyringOrderWhateverTheTapOrder() {
        val draft = draft()
        draft.pick(3, 1, 2)
        val plan = assertNotNull(draft.ready(keys))
        assertEquals(listOf("key_1", "key_2", "key_3"), plan.keys.map { it.id })
        val reversed = draft().apply { pick(2, 3, 1) }.ready(keys)!!
        assertEquals(plan.descriptor, reversed.descriptor, "the same keys give the same descriptor")
    }

    @Test
    fun aPickThatIsUnconfirmedOrGoneIsNoPlan() {
        val draft = draft()
        draft.pick(1, 2, 3)
        assertNull(draft.ready(keys.map { if (it.id == "key_2") it.copy(verified = false) else it }), "unconfirmed now")
        assertNull(draft.ready(keys.filter { it.id != "key_3" }), "gone from the keyring")
        assertNotNull(draft.ready(keys + key(9)), "other keys do no harm")
    }

    @Test
    fun aPlanOnlyExistsForExactlyNConfirmedKeys() {
        assertNull(ReadyPlan.of(Preset.TwoOfThree, keys.take(2)))
        assertNull(ReadyPlan.of(Preset.TwoOfThree, keys.take(4)))
        assertNull(ReadyPlan.of(Preset.TwoOfThree, listOf(key(1), key(2, verified = false), key(3))))
        assertNotNull(ReadyPlan.of(Preset.TwoOfThree, keys.take(3)))
        assertNotNull(ReadyPlan.of(Preset.ThreeOfFive, keys))
    }

    private fun saved(name: String, m: Int, n: Int, vararg fingerprints: String) =
        CreatedVault("id_$name", name, m, n, CustodyPolicy.PersonalCustody, "addr", "bsms", fingerprints.toList())

    @Test
    fun theSameKeysInAnotherTapOrderAreTheSavedWallet() {
        val wallet = saved("Vault 1", 2, 3, "aaaa0003", "aaaa0002", "aaaa0001")
        val other = saved("other", 2, 3, "aaaa0001", "aaaa0002", "aaaa0004")
        assertSame(wallet, draft().apply { pick(1, 2, 3) }.ready(keys)!!.duplicateOf(listOf(other, wallet)))
        assertSame(wallet, draft().apply { pick(3, 1, 2) }.ready(keys)!!.duplicateOf(listOf(other, wallet)))
        assertSame(wallet, draft().apply { pick(2, 3, 1) }.ready(keys)!!.duplicateOf(listOf(other, wallet)))
    }

    @Test
    fun oneDifferentKeyOrAnotherMOrAnotherNIsAnotherWallet() {
        val plan = ReadyPlan.of(Preset.TwoOfThree, keys.take(3))!!
        assertNull(plan.duplicateOf(listOf(saved("a", 2, 3, "aaaa0001", "aaaa0002", "aaaa0004"))), "one key differs")
        assertNull(plan.duplicateOf(listOf(saved("b", 3, 3, "aaaa0001", "aaaa0002", "aaaa0003"))), "the same keys at another M")
        assertNull(plan.duplicateOf(listOf(saved("c", 2, 5, "aaaa0001", "aaaa0002", "aaaa0003"))), "the same keys at another N")
        assertNull(plan.duplicateOf(listOf(saved("d", 2, 3))), "a wallet with no known keys")
        assertNull(ReadyPlan.of(Preset.ThreeOfFive, keys)!!.duplicateOf(listOf(saved("e", 2, 3, "aaaa0001", "aaaa0002", "aaaa0003"))))
    }

    @Test
    fun fingerprintsMatchWithoutCaseAndAnEmptyBookMatchesNothing() {
        val plan = ReadyPlan.of(Preset.TwoOfThree, listOf(key(1), key(2), key(3)).map { it.copy(fingerprint = it.fingerprint.uppercase()) })!!
        val lower = saved("Vault 1", 2, 3, "aaaa0003", "aaaa0001", "aaaa0002")
        assertSame(lower, plan.duplicateOf(listOf(lower)))
        assertNull(plan.duplicateOf(emptyList()))
    }

    @Test
    fun theDescriptorShownIsTheLineTheFileCarries() {
        val plan = ReadyPlan.of(Preset.TwoOfThree, keys.take(3))!!
        val bsms = plan.bsms("tb1qaddress")
        assertEquals(plan.descriptor, bsms.lines()[1])
        assertEquals("wsh(sortedmulti(2,[aaaa0001/48'/1'/0'/2']xpub-1,[aaaa0002/48'/1'/0'/2']xpub-2,[aaaa0003/48'/1'/0'/2']xpub-3))", plan.descriptor)
        assertTrue(bsms.contains("receive[0]=tb1qaddress"))
    }

    @Test
    fun createSavesOneWalletNamedByTheBookAndADoubleTapSavesNoSecond() {
        val book = SavedVaults.memoryOnly()
        val draft = draft(book)
        draft.pick(1, 2, 3)
        val plan = draft.ready(keys)!!
        val made = assertNotNull(draft.create(plan))
        assertEquals("vault_1", made.id)
        assertEquals("Wallet 1", made.name)
        assertEquals(2 to 3, made.m to made.n)
        assertEquals("addr-2-xpub-1+xpub-2+xpub-3", made.address)
        assertEquals(listOf("aaaa0001", "aaaa0002", "aaaa0003"), made.keyFingerprints)
        assertEquals(plan.bsms(made.address), made.bsms)
        assertSame(made, draft.create(plan), "the second tap returns the first wallet")
        assertEquals(listOf(made), book.current.vaults)
        assertEquals(1, calls.size, "and asks the core nothing more")
    }

    @Test
    fun aSavedVaultIsFollowedByWallet2AndSavedNamesNeverChange() {
        val book = SavedVaults.memoryOnly()
        val old = CreatedVault("vault_1", "Vault 1", 2, 3, CustodyPolicy.PersonalCustody, "old", "old", emptyList())
        book.edit { it.copy(vaults = listOf(old)) }
        val draft = draft(book)
        draft.pick(1, 2, 3)
        val made = draft.create(draft.ready(keys)!!)!!
        assertEquals("vault_2", made.id)
        assertEquals("Wallet 2", made.name)
        assertEquals(listOf(old, made), book.current.vaults)
    }

    @Test
    fun aThreeOfFiveWalletCarriesItsThresholdAndAMockKeyIsFlagged() {
        val book = SavedVaults.memoryOnly()
        val draft = draft(book)
        draft.choose(Preset.ThreeOfFive)
        draft.pick(1, 2, 3, 4, 5)
        val made = draft.create(draft.ready(keys.map { if (it.id == "key_4") key(4, mock = true) else it })!!)!!
        assertEquals(3 to 5, made.m to made.n)
        assertTrue(made.hasMockKey)
        assertEquals(3.toUByte(), calls.single().second)
    }

    @Test
    fun aCoreThatRefusesTheKeysSavesNothingAndReturnsNull() {
        val book = SavedVaults.memoryOnly()
        val draft = draft(book) { _, _ -> error("DuplicateXpub") }
        draft.pick(1, 2, 3)
        assertNull(draft.create(draft.ready(keys)!!))
        assertEquals(emptyList(), book.current.vaults)
        assertNull(draft.create(draft.ready(keys)!!), "a retry asks the core again")
    }

    @Test
    fun aPickHiddenByAKeyWithTheSameFingerprintStopsCountingAndTheVisibleRowsStillFillThePlan() {
        val phone = generateSoftwareMultisigKey("", KeyNetwork.TEST, 0u)
        val own = SavedKey("onboard_${phone.masterFingerprint}", "own", phone.masterFingerprint, phone.xpub, phone.derivationPath, KeySourceLabel.ThisDevice, verified = true)
        val store = KeyringStore(book = SavedVaults.memoryOnly(), ownKeys = { listOf(own) })
        store.addConfirmed(generateSoftwareMultisigKey("", KeyNetwork.TEST, 1u))
        store.addConfirmed(generateSoftwareMultisigKey("", KeyNetwork.TEST, 2u))
        val draft = draft()
        draft.toggle(own.id, store.keys)
        assertEquals(listOf(own.id), draft.livePicks(store.keys))

        store.addConfirmed(phone) // the Add Key detour: the same key from another source hides the own row
        assertEquals(emptyList(), draft.livePicks(store.keys), "the hidden key's pick is not counted")
        assertNull(draft.ready(store.keys))
        store.keys.filter { it.verified }.forEach { draft.toggle(it.id, store.keys) } // every row on screen, once

        assertEquals(store.keys.map { it.id }, draft.picks, "no pick is left that no row can untick")
        assertEquals(3, draft.livePicks(store.keys).size)
        assertNotNull(draft.ready(store.keys))
    }

    @Test
    fun aBookThatRepeatsAnIdStillNeedsNDistinctPicks() {
        val repeated = listOf(key(1), key(3), key(3).copy(xpub = "xpub-33"))
        val draft = draft()
        draft.toggle("key_1", repeated)
        draft.toggle("key_3", repeated)
        assertNull(draft.ready(repeated), "two picks of a 2-of-3 are no plan, however many rows share an id")
        val withSecond = repeated + key(2)
        draft.toggle("key_2", withSecond)
        assertEquals(setOf("key_1", "key_2", "key_3"), assertNotNull(draft.ready(withSecond)).keys.map { it.id }.toSet())
        assertEquals(3, draft.ready(withSecond)!!.keys.size)
    }

    @Test
    fun theRealCoreGivesATestnetAddressForThreeRealKeys() {
        val minted = (1u..3u).map { generateSoftwareMultisigKey("", KeyNetwork.TEST, it) }
        val real = minted.mapIndexed { i, k ->
            SavedKey("key_$i", "Key $i", k.masterFingerprint, k.xpub, k.derivationPath, KeySourceLabel.ThisDevice, verified = true)
        }
        val book = SavedVaults.memoryOnly()
        val draft = CreateWalletDraft(book)
        real.forEach { draft.toggle(it.id, real) }
        val made = assertNotNull(draft.create(draft.ready(real)!!))
        assertEquals(computeVaultAddressMultisig(real.map { it.xpub }, 2u), made.address)
        assertTrue(made.address.startsWith("tb1q"), made.address)
        assertTrue(made.bsms.contains("receive[0]=${made.address}"))
    }
}
