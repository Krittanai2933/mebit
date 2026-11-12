package com.mebit.app.data

import com.mebit.app.data.SingleSigWallet.DamagedGuard
import com.mebit.app.data.SingleSigWallet.UnconfirmedSend
import com.mebit.ffi.signer.FeeTiers
import com.mebit.ffi.signer.HotScan
import com.mebit.ffi.signer.HotSendResult
import com.mebit.ffi.signer.MobileSignerException
import com.mebit.ffi.signer.PreparedSend
import com.mebit.ffi.signer.SoftwareAccountKey
import com.mebit.ffi.signer.VaultPsbt
import com.mebit.ffi.signer.VaultSpendable
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A several-key wallet's send on fake Rust calls: what each step asks Rust, what it keeps, and the pay-twice guard around the post. */
class VaultSendTest {
    private val vault = CreatedVault(
        id = "vault_1",
        name = "Wallet 1",
        m = 2,
        n = 3,
        policy = CustodyPolicy.PersonalCustody,
        address = "tb1qvaultreceive0",
        bsms = "BSMS 1.0\nwsh(sortedmulti(2,[aaaa0000/48'/1'/0'/2']tpubA,[bbbb0000/48'/1'/0'/2']tpubB,[cccc0000/48'/1'/0'/2']tpubC))\n/0/*,/1/*\ntb1qvaultreceive0",
        keyFingerprints = listOf("AAAA0000", "BBBB0000", "CCCC0000"),
    )

    /** One input, one output and no witness: the smallest transaction whose bytes the guard keeps, because [txidOf] reads them. */
    private val hex = "02000000" + "01" + "00".repeat(32) + "00000000" + "00" + "ffffffff" + "01" + "00".repeat(8) + "00" + "00000000"
    private val txid = txidOf(hex)!!

    private fun record(txid: String = this.txid, signed: List<UInt> = emptyList()) =
        VaultPsbt("cHNidP8=", txid, "tb1qdestination", 600_000uL, 402uL, 399_598uL, 1u, 201uL, signed)

    /** Rust as the draft sees it: [sign] adds the asked key unless set otherwise, and every call is counted. */
    private inner class Rust {
        var prepares = mutableListOf<Pair<String, ULong?>>()
        var signs = 0
        var finalizes = 0
        val posts = mutableListOf<String>()
        var sign: (VaultPsbt, UInt) -> VaultPsbt = { held, key -> held.copy(signedKeys = (held.signedKeys + key).distinct().sorted()) }
        var post: (String) -> HotSendResult = { HotSendResult(it) }
        var spendable = VaultSpendable(84_200_000uL, HotScan.COMPLETE)

        fun send(guards: PendingPayments = PendingPayments(FakePlainFile()), now: Long = 1_000L) = VaultSend(
            vault,
            guards,
            nowSeconds = { now },
            worker = EmptyCoroutineContext,
            typicalVsize = { xpubs, m ->
                assertEquals(listOf("tpubA", "tpubB", "tpubC"), xpubs, "the keys in BSMS order")
                assertEquals(2u.toUByte(), m)
                201uL
            },
            spendableOf = { _, _ -> spendable },
            fetchFeeTiers = { FeeTiers(3uL, 7uL, 12uL, true) },
            prepareSend = { _, _, destination, amount, _ ->
                prepares += destination to amount
                record()
            },
            signPsbt = { _, _, held, key, _, _ ->
                signs++
                sign(held, key)
            },
            finalizePsbt = { _, _, held ->
                finalizes++
                PreparedSend(held.txid, hex, held.feeSats, held.amountSats)
            },
            postHex = { _, _, txid ->
                posts += txid
                post(txid)
            },
        )
    }

    private fun phone(key: Int) = KeySigner.Phone(key, listOf("abandon"), "")

    private fun signed(rust: Rust, guards: PendingPayments = PendingPayments(FakePlainFile())): VaultSend = runBlocking {
        val send = rust.send(guards)
        send.draft.destination = "tb1qdestination"
        send.draft.setAmountInSats(600_000)
        assertTrue(send.prepare())
        assertTrue(send.sign(phone(0)) { true })
        assertTrue(send.sign(phone(2)) { true })
        send
    }

    @Test
    fun aNewSendIsBlankAtTheNormalTierPricedAtTheWalletsOwnSize() {
        val send = Rust().send()
        assertEquals(201L, send.draft.typicalVsize)
        assertEquals(8L * 201, send.draft.feeSats, "the board's normal tier until the live tiers come")
        assertEquals(VaultSendState.Form, send.state)
        assertEquals(LiveFeeTiers(3, 7, 12, live = true), send.load())
        assertEquals(84_200_000L, send.spendableSats)
    }

    @Test
    fun whatAWalletCanSpendIsUnknownUntilItsScanReachedTheEnd() {
        val rust = Rust().apply { spendable = VaultSpendable(0uL, HotScan.CONTINUING) }
        val send = rust.send()
        send.load()
        assertNull(send.spendableSats)
    }

    @Test
    fun prepareAsksForTheWholeBalanceWithANullAmountOnSendAll() {
        val rust = Rust()
        val send = rust.send()
        send.draft.destination = "tb1qdestination"
        send.draft.setSendAll(80_000)
        assertTrue(send.prepare())
        send.draft.setAmountInSats(600_000)
        assertTrue(send.prepare())
        assertEquals(listOf("tb1qdestination" to null, "tb1qdestination" to 600_000uL), rust.prepares)
    }

    @Test
    fun prepareRefusesWhileTheWalletsGuardIsSetAndAsksRustNothing() {
        val file = FakePlainFile()
        val guards = PendingPayments(file)
        val rust = Rust()
        assertEquals(Claim.Written, guards.claim(vaultGuardKey(vault), UnconfirmedSend("cd".repeat(32), 10L)))
        val send = rust.send(guards)
        assertFalse(send.prepare())
        assertEquals(EARLIER_UNSETTLED, send.lastError)
        assertTrue(rust.prepares.isEmpty())
        assertIs<UnconfirmedSend>(send.guard)
    }

    @Test
    fun aSecondPrepareDropsTheFirstPaymentsSignatures() {
        val rust = Rust()
        val send = signed(rust)
        assertEquals(setOf("AAAA0000", "CCCC0000"), send.signedFingerprints)
        assertTrue(send.prepare())
        assertEquals(emptySet(), send.signedFingerprints)
        assertFalse(send.readyToBroadcast())
    }

    @Test
    fun signAsksTheOwnerBeforeRustAndAFailedCheckAsksRustNothing(): Unit = runBlocking {
        val rust = Rust()
        val send = rust.send()
        assertTrue(send.prepare())
        var asked = 0
        assertFalse(send.sign(phone(0)) { asked++; false })
        assertEquals(1 to 0, asked to rust.signs)
        assertTrue(send.sign(phone(0)) { asked++; true })
        assertEquals(2 to 1, asked to rust.signs)
    }

    @Test
    fun signKeepsRustsAnswerOnlyWhenItNamesTheKeyOnTheSamePayment(): Unit = runBlocking {
        val rust = Rust()
        val send = rust.send()
        assertTrue(send.prepare())
        rust.sign = { held, _ -> held }
        assertFalse(send.sign(phone(0)) { true }, "an answer without the key")
        rust.sign = { held, key -> held.copy(txid = "ef".repeat(32), signedKeys = listOf(key)) }
        assertFalse(send.sign(phone(0)) { true }, "an answer of another payment")
        assertEquals(emptySet(), send.signedFingerprints)
        rust.sign = { _, _ -> throw MobileSignerException.InvalidPsbtJson("invalid PSBT json: vault key: the words are not key 0 of the wallet") }
        assertFalse(send.sign(phone(0)) { true })
        assertEquals("กุญแจนี้ไม่ใช่กุญแจของ Wallet นี้", send.lastError)
    }

    @Test
    fun broadcastBelowMDistinctFingerprintsAsksRustNothing(): Unit = runBlocking {
        val rust = Rust()
        val send = rust.send()
        assertTrue(send.prepare())
        assertTrue(send.sign(phone(1)) { true })
        assertFalse(send.broadcast())
        assertEquals(0 to emptyList<String>(), rust.finalizes to rust.posts)
    }

    @Test
    fun broadcastWritesTheGuardBeforeThePostAndDropsItOnceTheNodeTookIt() {
        val file = FakePlainFile()
        val rust = Rust()
        rust.post = { posted ->
            assertTrue(file.text.orEmpty().startsWith("${vaultGuardKey(vault)} $posted 1000"), "the guard is on the phone before the post: ${file.text}")
            HotSendResult(posted)
        }
        val send = signed(rust, PendingPayments(file))
        assertTrue(send.broadcast())
        assertEquals(txid, assertIs<VaultSendState.Sent>(send.state).psbt.txid)
        assertEquals(setOf("AAAA0000", "CCCC0000"), send.signedFingerprints, "5.2 still shows who signed while it leaves")
        assertFalse(send.readyToBroadcast())
        assertEquals(listOf(txid), rust.posts)
        assertEquals("", file.text.orEmpty(), "the guard went with the payment out")
        assertFalse(send.broadcast(), "a payment out is not posted again")
        assertEquals(1, rust.posts.size)
    }

    @Test
    fun aPaymentOutKeepsWhatItsSlipShowsTheTimeItWentOutAmongThem(): Unit = runBlocking {
        val rust = Rust()
        val send = rust.send(now = 1_762_934_400L)
        send.draft.destination = "tb1qdestination"
        send.draft.setAmountInSats(600_000)
        assertTrue(send.prepare())
        assertTrue(send.sign(phone(0)) { true })
        assertTrue(send.sign(phone(2)) { true })
        assertTrue(send.broadcast())
        val sent = assertIs<VaultSendState.Sent>(send.state)
        assertEquals(1_762_934_400L, sent.atSeconds, "5.9's date line")
        assertEquals(listOf(txid, "tb1qdestination"), listOf(sent.psbt.txid, sent.psbt.destination))
        assertEquals(600_000uL to 402uL, sent.psbt.amountSats to sent.psbt.feeSats)
    }

    @Test
    fun anUnknownOutcomeKeepsTheGuardAndASecondTapPostsTheSameBytes() {
        val file = FakePlainFile()
        val rust = Rust()
        rust.post = { throw MobileSignerException.BroadcastUnknown(it) }
        val send = signed(rust, PendingPayments(file))
        assertFalse(send.broadcast())
        assertEquals("ยังไม่ทราบว่าการส่งออกไปแล้วหรือยัง อย่าส่งซ้ำ", send.lastError)
        assertIs<UnconfirmedSend>(send.guard)
        rust.post = { HotSendResult(it) }
        assertTrue(send.broadcast(), "the guard names this txid, so the same bytes go again")
        assertEquals(listOf(txid, txid), rust.posts)
        assertNull(send.guard)
        assertEquals("", file.text.orEmpty())
    }

    @Test
    fun aGuardOfAnotherPaymentRefusesThePostAndStays() {
        val file = FakePlainFile()
        val guards = PendingPayments(file)
        val rust = Rust()
        val send = signed(rust, guards)
        assertEquals(Claim.Written, guards.claim(vaultGuardKey(vault), UnconfirmedSend("cd".repeat(32), 10L)))
        assertFalse(send.broadcast())
        assertEquals(EARLIER_UNSETTLED, send.lastError)
        assertEquals(emptyList(), rust.posts)
        assertEquals("cd".repeat(32), (send.guard as UnconfirmedSend).txid)
    }

    @Test
    fun theNodesRefusalDropsTheGuardAndLeavesAPaymentThatCannotGoOut() {
        val file = FakePlainFile()
        val rust = Rust()
        rust.post = { throw MobileSignerException.InvalidPsbtJson("invalid PSBT json: esplora post: bad-txns-inputs-missingorspent") }
        val send = signed(rust, PendingPayments(file))
        assertFalse(send.broadcast())
        assertEquals("", file.text.orEmpty(), "nothing is out, so the guard goes")
        assertTrue((send.state as VaultSendState.Collecting).stale)
        assertFalse(send.readyToBroadcast(), "5.1 prepares another")
        assertFalse(send.broadcast())
        assertEquals(1, rust.posts.size)
    }

    @Test
    fun aGuardTheFileCannotTakePostsNothing() {
        val file = FakePlainFile().apply { failWrite = true }
        val rust = Rust()
        val send = signed(rust, PendingPayments(file))
        assertFalse(send.broadcast())
        assertEquals(GUARD_NOT_SAVED, send.lastError)
        assertEquals(emptyList(), rust.posts)
    }

    @Test
    fun aWalletsGuardLeavesTheOneKeyGuardFileAsItWas() {
        val oneKey = FakePlainFile()
        val oneKeyGuards = PendingPayments(oneKey)
        assertEquals(Claim.Written, oneKeyGuards.claim("73c5da0a", UnconfirmedSend("12".repeat(32), 5L)))
        val before = oneKey.text
        val vaultFile = FakePlainFile()
        val rust = Rust()
        rust.post = { throw MobileSignerException.BroadcastUnknown(it) }
        assertFalse(signed(rust, PendingPayments(vaultFile)).broadcast())
        assertEquals(before, oneKey.text)
        assertIs<UnconfirmedSend>(oneKeyGuards.of("73c5da0a"), "a one-key wallet still reads its own guard")
        assertTrue(vaultFile.text.orEmpty().startsWith(vaultGuardKey(vault)))
    }

    @Test
    fun aRepostOfTheGuardsOwnBytesThatGoesOutDropsTheGuard() {
        val file = FakePlainFile()
        val guards = PendingPayments(file)
        assertEquals(Claim.Written, guards.claim(vaultGuardKey(vault), UnconfirmedSend(txid, 10L, hex)))
        val rust = Rust()
        val send = rust.send(guards)
        send.load()
        assertEquals(Repost.Out, send.repostGuarded())
        assertEquals(listOf(txid), rust.posts)
        assertNull(send.guard)
        assertEquals("", file.text.orEmpty())
    }

    @Test
    fun aGuardWithoutItsBytesHasNothingToPostAgainAndStays() {
        val guards = PendingPayments(FakePlainFile())
        assertEquals(Claim.Written, guards.claim(vaultGuardKey(vault), UnconfirmedSend(txid, 10L, null)))
        val rust = Rust()
        val send = rust.send(guards)
        send.load()
        assertEquals(Repost.Failed, send.repostGuarded())
        assertEquals(emptyList(), rust.posts)
        assertIs<UnconfirmedSend>(send.guard)
    }

    @Test
    fun aDamagedGuardFileOffersTheClearAtOnceAndAClearLeavesADamagedLineForTheOtherWallets() {
        val file = FakePlainFile().apply { text = "not a guard file" }
        val send = Rust().send(PendingPayments(file))
        send.load()
        assertEquals(DamagedGuard, send.guard)
        assertTrue(send.clearOffered())
        assertTrue(send.clearGuard(listOf("0badc0de")))
        assertNull(send.guard)
        assertEquals("0badc0de damaged\n", file.text)
    }

    @Test
    fun theClearOfAPaymentsGuardIsOfferedOnlyOnceItIsOldEnough() {
        val guards = PendingPayments(FakePlainFile())
        assertEquals(Claim.Written, guards.claim(vaultGuardKey(vault), UnconfirmedSend("cd".repeat(32), 1_000L)))
        val early = Rust().send(guards, now = 1_000L + UNCONFIRMED_CLEAR_OFFER_SECONDS - 1)
        early.load()
        assertFalse(early.clearOffered())
        val late = Rust().send(guards, now = 1_000L + UNCONFIRMED_CLEAR_OFFER_SECONDS)
        late.load()
        assertTrue(late.clearOffered())
    }

    @Test
    fun aRefusalOfTheWalletSendCheckReadsInThai() {
        assertEquals("ธุรกรรมไม่ตรงกับที่ตรวจไว้ จึงไม่เซ็น", sendErrorThai("invalid PSBT json: vault psbt: fee: inputs less outputs is Some(1001), not the 1000 sats confirmed"))
        assertEquals("กุญแจนี้ไม่ใช่กุญแจของ Wallet นี้", sendErrorThai("invalid PSBT json: vault key: the words are not key 0 of the wallet"))
        assertEquals("ลายเซ็นยังไม่ครบ", sendErrorThai("invalid PSBT json: vault signatures: 1 of 2"))
    }

    @Test
    fun aWalletsGuardKeyIsEightHexOfItsAddressAlone() {
        val key = vaultGuardKey(vault)
        assertTrue(Regex("[0-9a-f]{8}").matches(key), key)
        assertEquals(key, vaultGuardKey(vault.copy(id = "vault_9", name = "Renamed")))
        assertNotEquals(key, vaultGuardKey(vault.copy(address = "tb1qanotherwallet")))
    }

    private fun session(fingerprint: String, xpub: String, words: List<String>) = OnboardingSession(
        restoreKey = { w, _, _, _ -> SoftwareAccountKey(w, fingerprint, xpub, "m/48'/1'/0'/2'") },
        forgetScans = {},
    ).apply { restoreFromWords(words) }

    private fun key(fingerprint: String, xpub: String, source: KeySourceLabel, isMock: Boolean = false) =
        SavedKey("key_$fingerprint", "Key", fingerprint, xpub, "m/48'/1'/0'/2'", source, verified = true, isMock = isMock)

    private val words = List(12) { "abandon" }

    @Test
    fun aListedWalletWithTheFingerprintInAnyCaseAndTheXpubSigns() {
        val signers = keySigners(vault, listOf(session("bbbb0000", "tpubB", words)), emptyList())
        val phone = assertIs<KeySigner.Phone>(signers[1])
        assertEquals(1 to words, phone.index to phone.words)
        assertIs<KeySigner.Elsewhere>(signers[0])
        assertIs<KeySigner.Elsewhere>(signers[2])
    }

    @Test
    fun theSameFingerprintWithAnotherXpubDoesNotSign() {
        val signers = keySigners(vault, listOf(session("bbbb0000", "tpubOtherAccount", words)), emptyList())
        assertEquals(KEY_NOT_HERE, assertIs<KeySigner.Elsewhere>(signers[1]).reason)
    }

    @Test
    fun aPhoneHoldingTwoKeysOfTheWalletSignsBoth() {
        val signers = keySigners(vault, listOf(session("aaaa0000", "tpubA", words), session("cccc0000", "tpubC", words)), emptyList())
        assertEquals(listOf(0, 2), signers.filterIsInstance<KeySigner.Phone>().map { it.index })
    }

    @Test
    fun hardwareAnotherPhoneAStandInAndAnUnknownKeyEachSayWhy() {
        val keyring = listOf(
            key("aaaa0000", "tpubA", KeySourceLabel.Jade),
            key("bbbb0000", "tpubB", KeySourceLabel.MebitOther),
            key("cccc0000", "tpubC", KeySourceLabel.ThisDevice, isMock = true),
        )
        val reasons = keySigners(vault, emptyList(), keyring).map { (it as KeySigner.Elsewhere).reason }
        assertEquals(listOf(SIGN_ON_HARDWARE_LATER, KEY_ON_ANOTHER_PHONE, STAND_IN_KEY), reasons)
        assertEquals(KEY_NOT_HERE, (keySigners(vault, emptyList(), emptyList())[0] as KeySigner.Elsewhere).reason)
    }
}
