package com.mebit.app.data

import com.mebit.app.data.SingleSigWallet.DamagedGuard
import com.mebit.app.data.SingleSigWallet.UnconfirmedSend
import com.mebit.ffi.signer.BleException
import com.mebit.ffi.signer.FeeTiers
import com.mebit.ffi.signer.FoundWallet
import com.mebit.ffi.signer.HotScan
import com.mebit.ffi.signer.HotSendResult
import com.mebit.ffi.signer.HotWalletSnapshot
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.MobileSignerException
import com.mebit.ffi.signer.PreparedSend
import com.mebit.ffi.signer.SoftwareAccountKey
import com.mebit.ffi.signer.SpendOutput
import com.mebit.ffi.signer.SpendReview
import com.mebit.ffi.signer.WalletKey
import com.mebit.ffi.signer.WalletKind
import com.mebit.ffi.signer.WalletShape
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A several-key wallet's send on fake Rust calls: what each step asks Rust, what it keeps, and the pay-twice guard around the post. */
class VaultSendTest {
    /** The descriptor the wallet's BSMS holds on its line 2, which every Rust call must name the wallet by. */
    private val line2 = "wsh(sortedmulti(2,[aaaa0000/48'/1'/0'/2']tpubA,[bbbb0000/48'/1'/0'/2']tpubB,[cccc0000/48'/1'/0'/2']tpubC))"

    private val vault = CreatedVault(
        id = "vault_1",
        name = "Wallet 1",
        m = 2,
        n = 3,
        policy = CustodyPolicy.PersonalCustody,
        address = "tb1qvaultreceive0",
        bsms = "BSMS 1.0\n$line2\n/0/*,/1/*\ntb1qvaultreceive0",
        keyFingerprints = listOf("AAAA0000", "BBBB0000", "CCCC0000"),
    )

    /** One input, one output and no witness: the smallest transaction whose bytes the guard keeps, because [txidOf] reads them. */
    private val hex = "02000000" + "01" + "00".repeat(32) + "00000000" + "00" + "ffffffff" + "01" + "00".repeat(8) + "00" + "00000000"
    private val txid = txidOf(hex)!!

    /**
     * Rust's review of a payment of this wallet, its fingerprints 8 lowercase hex as Rust names them. The PSBT text carries every field,
     * so an answer the fake reads back from it is the payment the send passed.
     */
    private fun review(txid: String = this.txid, signed: List<String> = emptyList(), payments: List<SpendOutput> = listOf(SpendOutput("tb1qdestination", 600_000uL))) =
        SpendReview("psbt $txid $payments $signed", txid, payments, 399_598uL, 402uL, signed, 2u)

    private fun SpendReview.signedBy(fingerprint: String) = review(unsignedTxid, (signed_ + fingerprint).distinct(), payments)

    /** Rust as the send sees it: the book's own wallet, a [sign] that adds the key the words make unless set otherwise, and every call counted. */
    private inner class Rust {
        val prepares = mutableListOf<Pair<String, ULong?>>()
        var signs = 0
        var finishes = 0
        val posts = mutableListOf<String>()
        var shape: (CreatedVault) -> WalletShape = { v -> WalletShape(v.m.toUByte(), v.keyFingerprints.map { WalletKey(it.lowercase(), "m/48'/1'/0'/2'", "tpub") }, v.address, 201uL) }
        var draft: (destination: String) -> SpendReview = { review(payments = listOf(SpendOutput(it.lowercase(), 600_000uL))) }
        var sign: (held: SpendReview, fingerprint: String) -> SpendReview = { held, fingerprint -> held.signedBy(fingerprint) }
        var finish: (SpendReview) -> PreparedSend = { PreparedSend(it.unsignedTxid, hex, it.feeSats, 600_000uL) }
        var post: (String) -> HotSendResult = { HotSendResult(it) }
        var snapshot = HotWalletSnapshot("tb1qnextunused", 84_300_000uL, 84_200_000uL, KeyNetwork.TEST, HotScan.COMPLETE)

        /** Every review this fake answered, by its PSBT text. */
        private val answered = mutableMapOf<String, SpendReview>()

        private fun answer(review: SpendReview) = review.also { answered[it.psbt] = it }

        private fun named(descriptor: String) = assertEquals(line2, descriptor, "the wallet is named by its BSMS line 2")

        /** What a scan finds in range: the link to it, for each device the send reaches. */
        var inRange: (FoundWallet) -> DeviceLink = { error("no device in range") }

        /** A device holding vault key [key]: Rust's signWalletPsbt answers through [sign], as wallet_sign_with_words does for words. */
        fun device(key: Int): FakeDeviceLink = FakeDeviceLink(fingerprint = vault.keyFingerprints[key].lowercase()) { d, psbt, shownTxid ->
            named(d)
            signs++
            val held = answered.getValue(psbt)
            assertEquals(held.unsignedTxid, shownTxid, "the txid 5.2 showed")
            answer(sign(held, vault.keyFingerprints[key].lowercase()))
        }

        fun send(guards: PendingPayments = PendingPayments(FakePlainFile()), now: Long = 1_000L, vault: CreatedVault = this@VaultSendTest.vault) = VaultSend(
            vault,
            guards,
            nowSeconds = { now },
            worker = EmptyCoroutineContext,
            core = DescribedCore(
                describe = { d, _ ->
                    named(d)
                    shape(vault)
                },
                snapshot = { d, _ ->
                    named(d)
                    snapshot
                },
                feeTiers = { FeeTiers(3uL, 7uL, 12uL, true) },
                draft = { d, _, destination, amount, _ ->
                    named(d)
                    prepares += destination to amount
                    answer(draft(destination))
                },
                signWithWords = { d, _, psbt, shownTxid, words, _ ->
                    named(d)
                    signs++
                    val held = answered.getValue(psbt)
                    assertEquals(held.unsignedTxid, shownTxid, "the txid 5.2 showed")
                    answer(sign(held, vault.keyFingerprints[words.single().removePrefix("key").toInt()].lowercase()))
                },
                finish = { d, _, psbt, shownTxid ->
                    named(d)
                    finishes++
                    val held = answered.getValue(psbt)
                    assertEquals(held.unsignedTxid, shownTxid, "the txid 5.2 showed")
                    finish(held)
                },
                postHex = { _, _, txid ->
                    posts += txid
                    post(txid)
                },
            ),
            link = HardwareLink(pairings = null) { inRange(it) },
        )
    }

    /** Row [key]'s signer. Its words name the row, so the fake signs as that key, as Rust signs as the key the words make. */
    private fun phone(key: Int) = KeySigner.Phone(key, listOf("key$key"), "")

    /** Row [key]'s signer when a Jade holds that key. */
    private fun jade(key: Int) = KeySigner.Device(key, WalletKind.JADE)

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
        val rust = Rust().apply { snapshot = HotWalletSnapshot("tb1qnextunused", 50_000uL, 50_000uL, KeyNetwork.TEST, HotScan.CONTINUING) }
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
    fun signAsksNeitherTheOwnerNorRustOnceMDistinctFingerprintsSigned(): Unit = runBlocking {
        val rust = Rust()
        val send = signed(rust)
        var asked = 0
        assertFalse(send.sign(phone(1)) { asked++; true })
        assertEquals(0 to 2, asked to rust.signs)
        assertEquals(setOf("AAAA0000", "CCCC0000"), send.signedFingerprints)
    }

    @Test
    fun aSecondKeyOfTheSameSeedAsksNeitherTheOwnerNorRust(): Unit = runBlocking {
        val rust = Rust()
        val send = rust.send(vault = vault.copy(keyFingerprints = listOf("AAAA0000", "aaaa0000", "CCCC0000")))
        assertTrue(send.prepare())
        assertTrue(send.sign(phone(0)) { true })
        var asked = 0
        assertFalse(send.sign(phone(1)) { asked++; true })
        assertEquals(0 to 1, asked to rust.signs)
    }

    @Test
    fun aKeyIsNeededUntilItsFingerprintSignedOrTheWalletHasM(): Unit = runBlocking {
        val send = Rust().send()
        assertTrue(send.prepare())
        assertEquals(listOf(true, true, true), (0..2).map(send::needsKey))
        assertTrue(send.sign(phone(0)) { true })
        assertEquals(listOf(false, true, true), (0..2).map(send::needsKey))
        assertTrue(send.sign(phone(2)) { true })
        assertEquals(listOf(false, false, false), (0..2).map(send::needsKey), "5.8: every row left reads ไม่ต้องใช้รอบนี้")
    }

    @Test
    fun aSignatureKeepsThePhoneClockOfItsSignUnderItsFingerprintUntilANewPayment(): Unit = runBlocking {
        val send = Rust().send(now = 1_762_934_400L, vault = vault.copy(keyFingerprints = listOf("AAAA0000", "BBBB0000", "cccc0000")))
        assertTrue(send.prepare())
        assertTrue(send.sign(phone(2)) { true })
        assertEquals(mapOf("CCCC0000" to 1_762_934_400L), send.signedAt, "the time on key 3's row")
        assertTrue(send.prepare())
        assertEquals(emptyMap(), send.signedAt, "a new payment has no signature yet")
    }

    @Test
    fun aSignWhoseOwnerCheckEndsAfterTheLastNeededSignatureAsksRustNothing(): Unit = runBlocking {
        val rust = Rust()
        val send = rust.send()
        assertTrue(send.prepare())
        assertTrue(send.sign(phone(0)) { true })
        assertFalse(send.sign(phone(2)) { send.sign(phone(1)) { true } }, "key 1 made the payment ready while key 2's owner check was open")
        assertEquals(2, rust.signs)
        assertEquals(setOf("AAAA0000", "BBBB0000"), send.signedFingerprints)
    }

    @Test
    fun signKeepsRustsAnswerOnlyWhenItNamesTheRowsKeyOnTheSamePayment(): Unit = runBlocking {
        val rust = Rust()
        val send = rust.send()
        assertTrue(send.prepare())
        val held = send.payment
        rust.sign = { held, _ -> held }
        assertFalse(send.sign(phone(0)) { true }, "an answer without the key")
        assertEquals("เซ็นไม่สำเร็จ ลองใหม่อีกครั้ง", send.lastError)
        rust.sign = { held, key -> review("ef".repeat(32), listOf(key), held.payments) }
        assertFalse(send.sign(phone(0)) { true }, "an answer of another payment")
        rust.sign = { held, key -> held.signedBy(key) }
        assertFalse(send.sign(KeySigner.Phone(0, listOf("key1"), "")) { true }, "words of key 2 on key 1's row sign as key 2")
        assertEquals(emptySet(), send.signedFingerprints)
        assertEquals(emptyMap(), send.signedAt, "a refused answer has no time")
        assertSame(held, send.payment, "the payment held is still the one prepared")

        rust.sign = { held, key -> held.signedBy(key).signedBy("5eed0000") }
        assertFalse(send.sign(phone(0)) { true }, "an answer that names a fingerprint of no key of the wallet beside the row's")
        assertEquals(NOT_SIGNED, send.lastError)
        assertEquals(emptySet(), send.signedFingerprints)
        assertEquals(2, send.signaturesNeeded)
        assertSame(held, send.payment)
        rust.sign = { _, _ -> throw MobileSignerException.InvalidPsbtJson("invalid PSBT json: not a key of this wallet: the phrase's master fingerprint 73c5da0a holds none of its keys") }
        assertFalse(send.sign(phone(2)) { true })
        assertEquals("กุญแจนี้ไม่ใช่กุญแจของ Wallet นี้", send.lastError)
    }

    @Test
    fun broadcastBelowMDistinctFingerprintsAsksRustNothing(): Unit = runBlocking {
        val rust = Rust()
        val send = rust.send()
        assertTrue(send.prepare())
        assertTrue(send.sign(phone(1)) { true })
        assertFalse(send.broadcast())
        assertEquals(0 to emptyList<String>(), rust.finishes to rust.posts)
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
        assertEquals(txid, assertIs<VaultSendState.Sent>(send.state).payment.txid)
        assertEquals(setOf("AAAA0000", "CCCC0000"), send.signedFingerprints, "5.2 still shows who signed while it leaves")
        assertFalse(send.readyToBroadcast())
        assertEquals(listOf(txid), rust.posts)
        assertEquals("", file.text.orEmpty(), "the guard went with the payment out")
        assertFalse(send.broadcast(), "a payment out is not posted again")
        assertEquals(1, rust.posts.size)
    }

    @Test
    fun aPaymentOutKeepsThePhoneClockOfItsPostForTheSlipsDate(): Unit = runBlocking {
        val rust = Rust()
        val send = rust.send(now = 1_762_934_400L)
        send.draft.destination = "tb1qdestination"
        send.draft.setAmountInSats(600_000)
        assertTrue(send.prepare())
        assertTrue(send.sign(phone(0)) { true })
        assertTrue(send.sign(phone(2)) { true })
        assertTrue(send.broadcast())
        assertEquals(1_762_934_400L, assertIs<VaultSendState.Sent>(send.state).atSeconds, "5.9's date line")
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
    fun aRefusalOfTheWalletSendCheckReadsInThaiOnTheStepThatMetIt(): Unit = runBlocking {
        val rust = Rust()
        val send = rust.send()
        val drafts = rust.draft
        rust.draft = { throw MobileSignerException.InvalidPsbtJson("invalid PSBT json: not this wallet's payment: output 1 pays 329 sats, under its 330 sat dust floor") }
        assertFalse(send.prepare())
        assertEquals("ธุรกรรมไม่ตรงกับที่ตรวจไว้ จึงไม่เซ็น", send.lastError, "5.1")
        rust.draft = drafts
        assertTrue(send.prepare())
        rust.sign = { _, _ -> throw MobileSignerException.InvalidPsbtJson("invalid PSBT json: changed since shown: this payment is ${"ab".repeat(32)}, not $txid") }
        assertFalse(send.sign(phone(0)) { true })
        assertEquals("ธุรกรรมไม่ตรงกับที่ตรวจไว้ จึงไม่เซ็น", send.lastError, "5.2")
        rust.sign = { _, _ -> throw MobileSignerException.InvalidPsbtJson("invalid PSBT json: signatures: key aaaa0000 has not signed every input") }
        assertFalse(send.sign(phone(0)) { true })
        assertEquals("เซ็นไม่สำเร็จ ลองใหม่อีกครั้ง", send.lastError, "5.2")
        rust.sign = { held, fingerprint -> held.signedBy(fingerprint) }
        assertTrue(send.sign(phone(0)) { true } && send.sign(phone(2)) { true })
        rust.finish = { throw MobileSignerException.InvalidPsbtJson("invalid PSBT json: signatures: 1 of the 2 keys needed signed every input") }
        assertFalse(send.broadcast())
        assertEquals("ลายเซ็นยังไม่ครบ", send.lastError, "the review after 5.2")
        rust.finish = { throw MobileSignerException.InvalidPsbtJson("invalid PSBT json: finish: signed weight 1000 is above the 900 its fee was sized for") }
        assertFalse(send.broadcast())
        assertEquals("ส่งไม่สำเร็จ ลองใหม่ภายหลัง", send.lastError, "the line the vault engine's weight refusal showed")
        assertTrue(rust.posts.isEmpty())
    }

    @Test
    fun aDraftShowsTheDestinationAsTyped(): Unit = runBlocking {
        val rust = Rust()
        val send = rust.send()
        send.draft.destination = "TB1QDESTINATION"
        send.draft.setAmountInSats(600_000)
        assertTrue(send.prepare())
        assertEquals("TB1QDESTINATION" to 600_000uL, send.payment?.destination to send.payment?.amountSats, "5.2, though Rust names the address in lowercase")
        assertTrue(send.sign(phone(0)) { true } && send.sign(phone(2)) { true })
        assertEquals("TB1QDESTINATION", send.payment?.destination, "the review")
        assertTrue(send.broadcast())
        assertEquals("TB1QDESTINATION", assertIs<VaultSendState.Sent>(send.state).payment.destination, "5.9's slip")
    }

    @Test
    fun aRustAnswerWithTwoPaymentsOrAnotherAddressKeepsNothing(): Unit = runBlocking {
        val rust = Rust()
        val send = rust.send()
        val stranger = SpendOutput("tb1qstranger", 1_000uL)
        rust.draft = { review(payments = listOf(SpendOutput(it, 600_000uL), stranger)) }
        assertFalse(send.prepare(), "a draft of two payments")
        rust.draft = { review(payments = listOf(stranger)) }
        assertFalse(send.prepare(), "a draft to another address")
        assertEquals(VaultSendState.Form, send.state)
        assertEquals("ส่งไม่สำเร็จ ลองใหม่ภายหลัง", send.lastError)

        rust.draft = { review(payments = listOf(SpendOutput(it, 600_000uL))) }
        assertTrue(send.prepare())
        val held = send.payment
        rust.sign = { held, key -> review(held.unsignedTxid, listOf(key), held.payments + stranger) }
        assertFalse(send.sign(phone(0)) { true }, "an answer of two payments")
        rust.sign = { held, key -> review(held.unsignedTxid, listOf(key), listOf(stranger)) }
        assertFalse(send.sign(phone(0)) { true }, "an answer to another address")
        assertSame(held, send.payment)
        assertEquals(emptySet(), send.signedFingerprints)
        assertEquals(emptyMap(), send.signedAt)
    }

    @Test
    fun aFinishOfAnotherTxidWritesNoGuardAndPostsNothing() {
        val file = FakePlainFile()
        val rust = Rust()
        val send = signed(rust, PendingPayments(file))
        rust.finish = { PreparedSend("ef".repeat(32), hex, it.feeSats, 600_000uL) }
        assertFalse(send.broadcast())
        assertEquals("ธุรกรรมไม่ตรงกับที่ตรวจไว้ จึงไม่เซ็น", send.lastError)
        assertEquals("", file.text.orEmpty(), "no guard")
        assertEquals(emptyList(), rust.posts)
        assertNull(send.guard)
        assertTrue(send.readyToBroadcast(), "the payment 5.2 showed is still held")
    }

    @Test
    fun aWalletTheCoreReadsAsAnotherIsNoSend() {
        assertEquals(201L, Rust().send().draft.typicalVsize, "the core that reads the book's own wallet")
        val other = listOf<(CreatedVault) -> WalletShape>(
            { v -> WalletShape(3u, v.keyFingerprints.map { WalletKey(it.lowercase(), "m/48'/1'/0'/2'", "tpub") }, v.address, 237uL) },
            { v -> WalletShape(2u, v.keyFingerprints.reversed().map { WalletKey(it.lowercase(), "m/48'/1'/0'/2'", "tpub") }, v.address, 201uL) },
            { v -> WalletShape(2u, v.keyFingerprints.map { WalletKey(it.lowercase(), "m/48'/1'/0'/2'", "tpub") }, "tb1qanotherwallet", 201uL) },
        )
        for ((i, shape) in other.withIndex()) {
            assertFailsWith<IllegalArgumentException>("another m, key order or first address: $i") { Rust().apply { this.shape = shape }.send() }
        }
        assertFailsWith<MobileSignerException> { Rust().apply { shape = { throw MobileSignerException.InvalidPsbtJson("not a wallet") } }.send() }
    }

    @Test
    fun aWalletsGuardKeyIsEightHexOfItsAddressAlone() {
        val key = vaultGuardKey(vault)
        assertEquals("a973e2e6", key, "the line Wallet 1's pending payment is kept under")
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
    fun aJadeOrSafe7KeyIsADeviceRowAndAnotherPhoneAStandInAndAnUnknownKeyEachSayWhy() {
        val keyring = listOf(
            key("aaaa0000", "tpubA", KeySourceLabel.Jade),
            key("bbbb0000", "tpubB", KeySourceLabel.TrezorSafe7),
            key("cccc0000", "tpubC", KeySourceLabel.Jade, isMock = true),
        )
        val signers = keySigners(vault, emptyList(), keyring)
        assertEquals(listOf(0 to WalletKind.JADE, 1 to WalletKind.TREZOR_SAFE7), signers.filterIsInstance<KeySigner.Device>().map { it.index to it.kind })
        assertEquals(STAND_IN_KEY, assertIs<KeySigner.Elsewhere>(signers[2]).reason, "a stand-in Jade key")
        val another = keySigners(vault, emptyList(), listOf(key("bbbb0000", "tpubB", KeySourceLabel.MebitOther)))
        assertEquals(KEY_ON_ANOTHER_PHONE, assertIs<KeySigner.Elsewhere>(another[1]).reason)
        val otherAccount = keySigners(vault, emptyList(), listOf(key("aaaa0000", "tpubOtherAccount", KeySourceLabel.Jade)))
        assertEquals(KEY_NOT_HERE, assertIs<KeySigner.Elsewhere>(otherAccount[0]).reason, "a Jade key that is not the wallet's")
        assertIs<KeySigner.Phone>(keySigners(vault, listOf(session("aaaa0000", "tpubA", words)), keyring)[0], "words in memory sign on the phone")
    }

    @Test
    fun aJadeAndASafe7OfOnePhraseEachSignTheWalletTheyWerePickedFor() {
        val jade = SavedKey("key_4_aaaa0000", "Jade", "aaaa0000", "tpubA", "m/48'/1'/0'/2'", KeySourceLabel.Jade, verified = true)
        val safe7 = SavedKey("key_5_aaaa0000", "Safe 7", "aaaa0000", "tpubA", "m/48'/1'/0'/2'", KeySourceLabel.TrezorSafe7, verified = true)
        val keyring = listOf(jade, safe7)
        fun picked(id: String) = vault.copy(keyIds = listOf(id, "key_bbbb0000", "key_cccc0000"))
        assertEquals(WalletKind.TREZOR_SAFE7, assertIs<KeySigner.Device>(keySigners(picked(safe7.id), emptyList(), keyring)[0]).kind, "the Safe 7 picked at creation, though the Jade is listed first")
        assertEquals("Safe 7", com.mebit.app.feature.vault.vaultKeyRows(picked(safe7.id), keyring)[0].name, "5.2 and the keys page name the picked device")
        assertEquals(WalletKind.JADE, assertIs<KeySigner.Device>(keySigners(picked(jade.id), emptyList(), keyring)[0]).kind)
        assertEquals(WalletKind.JADE, assertIs<KeySigner.Device>(keySigners(vault, emptyList(), keyring)[0]).kind, "a wallet saved before MV2 has no ids and takes the first entry holding the key")
        assertEquals(WalletKind.JADE, assertIs<KeySigner.Device>(keySigners(picked("key_gone"), emptyList(), keyring)[0]).kind, "a picked key since deleted falls back to the key itself")
        val other = SavedKey("key_6_aaaa0000", "Other", "aaaa0000", "tpubOtherAccount", "m/48'/1'/1'/2'", KeySourceLabel.TrezorSafe7, verified = true)
        assertEquals(WalletKind.JADE, assertIs<KeySigner.Device>(keySigners(picked(other.id), emptyList(), keyring + other)[0]).kind, "an id whose key is not this wallet's never decides")
    }

    @Test
    fun aDeviceSignatureCountsTowardMAsAPhoneKeysDoesAndOnlyBroadcastFinishes(): Unit = runBlocking {
        val rust = Rust()
        val device = rust.device(1)
        rust.inRange = { device }
        val send = rust.send()
        assertTrue(send.prepare())
        assertTrue(send.sign(phone(0)) { true })
        val heard = HeardSteps()
        assertTrue(send.signOnDevice(jade(1), JADE_FOUND, NO_CODES, heard))
        assertEquals(setOf("AAAA0000", "BBBB0000"), send.signedFingerprints)
        assertEquals(mapOf("AAAA0000" to 1_000L, "BBBB0000" to 1_000L), send.signedAt, "the time on key 2's row")
        assertTrue(send.readyToBroadcast(), "the m-th signature, a device's, makes the payment ready")
        assertEquals(0, rust.finishes, "a device's signature never finishes the payment")
        assertEquals(listOf("connect", "unlock", "accountXpub m/48'/1'/0'/2'", "signWalletPsbt $txid", "disconnect", "close"), device.calls)
        assertEquals(listOf("showing"), heard.steps, "the screen hears the steps the link tells")
        assertTrue(send.broadcast())
        assertEquals(1, rust.finishes)
    }

    @Test
    fun aDeviceIsReadAtItsKeysOwnAccountPathAndOneOfAnotherKeyIsShownNothing(): Unit = runBlocking {
        val rust = Rust()
        rust.shape = { v -> WalletShape(v.m.toUByte(), v.keyFingerprints.mapIndexed { i, f -> WalletKey(f.lowercase(), "m/48'/1'/$i'/2'", "tpub") }, v.address, 201uL) }
        val right = rust.device(1)
        rust.inRange = { right }
        val send = rust.send()
        assertTrue(send.prepare())
        assertTrue(send.signOnDevice(jade(1), JADE_FOUND, NO_CODES, NO_STEPS))
        assertEquals("accountXpub m/48'/1'/1'/2'", right.calls[2], "the path BSMS line 2 gives key 2, not a one-key path")
        val other = rust.device(0)
        rust.inRange = { other }
        assertFailsWith<BleException.WrongKey> { send.signOnDevice(jade(2), JADE_FOUND, NO_CODES, NO_STEPS) }
        assertTrue(other.calls.none { it.startsWith("signWalletPsbt") }, "nothing showed on a device of another key")
        assertEquals(setOf("BBBB0000"), send.signedFingerprints)
        assertEquals(1, rust.signs)
        assertNull(send.busy, "a throw ends the claim")
    }

    @Test
    fun aDeviceAnswerCountsOnlyWhenItIsThePaymentHeldWithTheRowsKeyAndNoOtherNewlySigned(): Unit = runBlocking {
        val rust = Rust()
        rust.inRange = { rust.device(0) }
        val send = rust.send()
        assertTrue(send.prepare())
        val held = send.payment
        val lies = listOf<Pair<String, (SpendReview, String) -> SpendReview>>(
            "no new signature" to { h, _ -> h },
            "another payment" to { h, key -> review("ef".repeat(32), listOf(key), h.payments) },
            "another key of the wallet" to { h, _ -> h.signedBy("cccc0000") },
            "the row's key and a fingerprint of no key of the wallet" to { h, key -> h.signedBy(key).signedBy("5eed0000") },
            "the row's key and another key of the wallet" to { h, key -> h.signedBy(key).signedBy("cccc0000") },
            "a payment to another address" to { h, key -> review(h.unsignedTxid, listOf(key), listOf(SpendOutput("tb1qstranger", 1_000uL))) },
        )
        for ((why, lie) in lies) {
            rust.sign = lie
            assertFailsWith<AnswerRefused>(why) { send.signOnDevice(jade(0), JADE_FOUND, NO_CODES, NO_STEPS) }
            assertNull(send.busy, why)
        }
        assertSame(held, send.payment, "the payment held is still the one prepared")
        assertEquals(emptySet(), send.signedFingerprints)
        assertEquals(emptyMap(), send.signedAt)
    }

    @Test
    fun whileADeviceSignsNothingElseReachesRustAndTheSendIsFreeOnceItAnswers(): Unit = runBlocking {
        val rust = Rust()
        val waiting = CompletableDeferred<Unit>()
        val confirmed = CompletableDeferred<Unit>()
        val device = rust.device(1)
        val answer = device.sign
        device.sign = { d, psbt, shownTxid ->
            waiting.complete(Unit)
            confirmed.await()
            answer(d, psbt, shownTxid)
        }
        rust.inRange = { device }
        val send = rust.send()
        assertTrue(send.prepare())
        val signing = async { send.signOnDevice(jade(1), JADE_FOUND, NO_CODES, NO_STEPS) }
        waiting.await()
        assertEquals(VaultBusy.Signing(1), send.busy)
        assertFalse(send.prepare(), "5.1's ตรวจสอบ")
        assertEquals(BUSY, send.lastError)
        assertFalse(send.sign(phone(0)) { true }, "another row's เซ็น")
        assertFalse(send.signOnDevice(jade(2), JADE_FOUND, NO_CODES, NO_STEPS), "another device's")
        assertFalse(send.broadcast())
        assertEquals(Repost.Failed, send.repostGuarded())
        assertEquals(1 to 0, rust.prepares.size to rust.signs, "nothing reached Rust while the device held the send")
        confirmed.complete(Unit)
        assertTrue(signing.await())
        assertNull(send.busy)
        assertTrue(send.sign(phone(0)) { true }, "the send is free once the device answered")
        assertEquals(setOf("AAAA0000", "BBBB0000"), send.signedFingerprints)
    }

    @Test
    fun aCancelOrALostLinkEndsTheClaimAndKeepsThePaymentAsItWas(): Unit = runBlocking {
        val rust = Rust()
        val waiting = CompletableDeferred<Unit>()
        val device = rust.device(1)
        device.sign = { _, _, _ ->
            waiting.complete(Unit)
            awaitCancellation()
        }
        rust.inRange = { device }
        val send = rust.send()
        assertTrue(send.prepare())
        val held = send.payment
        val signing = launch { send.signOnDevice(jade(1), JADE_FOUND, NO_CODES, NO_STEPS) }
        waiting.await()
        signing.cancelAndJoin()
        assertNull(send.busy, "Back or ยกเลิกการเซ็น ends the claim")
        assertEquals(listOf("disconnect", "close"), device.calls.takeLast(2), "and the link")
        val lost = rust.device(1).apply { failAt = "sign" }
        rust.inRange = { lost }
        assertFailsWith<BleException.Disconnected> { send.signOnDevice(jade(1), JADE_FOUND, NO_CODES, NO_STEPS) }
        assertNull(send.busy)
        assertSame(held, send.payment)
        assertEquals(emptySet(), send.signedFingerprints)
        assertTrue(send.prepare(), "nothing holds the send")
    }

    @Test
    fun aKeyThePaymentNoLongerNeedsNeverReachesItsDevice(): Unit = runBlocking {
        val rust = Rust()
        var reached = 0
        rust.inRange = {
            reached++
            rust.device(1)
        }
        assertFalse(rust.send().signOnDevice(jade(1), JADE_FOUND, NO_CODES, NO_STEPS), "nothing prepared")
        val send = signed(rust)
        assertFalse(send.signOnDevice(jade(1), JADE_FOUND, NO_CODES, NO_STEPS), "the payment has m")
        assertFalse(send.signOnDevice(jade(2), JADE_FOUND, NO_CODES, NO_STEPS), "key 3 signed it already")
        assertEquals(0, reached)
    }
}
