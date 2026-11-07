package com.mebit.app.data

import com.mebit.app.ReadResult
import com.mebit.app.Why
import com.mebit.app.WriteMode
import com.mebit.app.data.SingleSigWallet.UnconfirmedSend
import com.mebit.ffi.signer.AddressType
import com.mebit.ffi.signer.KeyNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** The record codec, driven through SavedWallet. No failure message carries a word, a passphrase or a fingerprint. */
class RecordCodecTest {
    private fun roundTrip(record: WalletRecord, case: String): WalletRecord {
        val wallet = SavedWallet(FakeSecureStore())
        assertIs<SaveResult.Written>(wallet.save(record, WriteMode.Create), "save, $case")
        return assertIs<UnlockResult.Opened>(wallet.openNow(), "open, $case").record
    }

    private fun opened(bytes: ByteArray) = SavedWallet(FakeSecureStore(read = ReadResult.Bytes(bytes, ANY_VERSION))).openNow()

    private fun assertBroken(cause: Cause, bytes: ByteArray, case: String) =
        assertEquals(cause, assertIs<UnlockResult.Broken>(opened(bytes), case).cause, case)

    @Test
    fun roundTripsWordsAndPassphraseByteExact() {
        val outsideTheBmp = charArrayOf(Char(0xD83D), Char(0xDE00)).concatToString() // one character, four UTF-8 bytes
        val passphrases = listOf(
            "empty" to "",
            "spaces" to " a b ",
            "120 printable" to CharArray(120) { ' ' + 1 + it % 94 }.concatToString(),
            "non-ASCII" to "รหัส ผ่าน ñ $outsideTheBmp",
            "65535 bytes" to "p".repeat(65_535),
        )
        for (words in listOf(PUBLIC_WORDS_12, PUBLIC_WORDS_24)) {
            for ((name, passphrase) in passphrases) {
                val case = "${words.size} words, $name"
                val back = roundTrip(testRecord(words = words, passphrase = passphrase), case)
                assertTrue(back.words == words, "words, $case")
                assertTrue(back.passphrase.encodeToByteArray().contentEquals(passphrase.encodeToByteArray()), "passphrase, $case")
                assertTrue(back.fingerprint == TEST_CHECK, "fingerprint, $case")
                assertEquals(KeyNetwork.TEST, back.network, case)
            }
        }
    }

    @Test
    fun everyFlagCombinationSurvives() {
        for (bits in 0 until 8) {
            val backup = bits and 1 != 0
            val imported = bits and 2 != 0
            val lock = bits and 4 != 0
            val back = roundTrip(testRecord(backupConfirmed = backup, imported = imported, appLockEnabled = lock), "flags $bits")
            assertEquals(backup, back.backupConfirmed, "backup, flags $bits")
            assertEquals(imported, back.imported, "imported, flags $bits")
            assertEquals(lock, back.appLockEnabled, "lock, flags $bits")
        }
    }

    @Test
    fun writesAndReadsTheDocumentedLayout() {
        val records = listOf(
            1 to testRecord(passphrase = "x y", backupConfirmed = true),
            2 to testRecord(passphrase = "x y", imported = true),
            4 to testRecord(passphrase = "x y", appLockEnabled = true),
        )
        for ((flags, record) in records) {
            val store = FakeSecureStore()
            SavedWallet(store).save(record, WriteMode.Create)
            val documented = v1Bytes(passphrase = "x y".encodeToByteArray(), flags = flags)
            assertTrue(store.writes.single().bytes.contentEquals(documented), "encoder output, flags $flags")
            val back = assertIs<UnlockResult.Opened>(opened(documented), "documented bytes, flags $flags").record
            assertEquals(
                listOf(record.backupConfirmed, record.imported, record.appLockEnabled),
                listOf(back.backupConfirmed, back.imported, back.appLockEnabled),
                "flags $flags",
            )
            assertTrue(back.words == PUBLIC_WORDS_24 && back.passphrase == "x y" && back.fingerprint == TEST_CHECK, "fields, flags $flags")
        }
    }

    // N1 of open item 79 moved the guard to a plain file. Only builds before it wrote flag 8, so these records are made by hand.
    @Test
    fun anOlderRecordsGuardOpensBesideTheRecordWithOrWithoutItsTxid() {
        val cases = listOf(
            "no txid, at 0" to UnconfirmedSend("", 0),
            "a txid" to UnconfirmedSend(TEST_TXID, 1_798_000_000),
            "a txid, at the largest time" to UnconfirmedSend(TEST_TXID, Long.MAX_VALUE),
        )
        for ((case, pending) in cases) {
            val bytes = v1Bytes(passphrase = "x y".encodeToByteArray(), flags = 4 or 8) + sendSection(pending.txid.encodeToByteArray(), pending.sinceSeconds)
            val back = assertIs<UnlockResult.Opened>(opened(bytes), case)
            assertEquals(pending, back.oldGuard, case)
            assertTrue(back.record.words == PUBLIC_WORDS_24 && back.record.passphrase == "x y" && back.record.appLockEnabled, "the rest of the record, $case")
        }
        assertNull(assertIs<UnlockResult.Opened>(opened(v1Bytes()), "a record without the section").oldGuard, "a guard from a record without the section")
    }

    @Test
    fun readsTheDocumentedLayoutOfAnOlderRecordsGuardAndSavesTheRecordWithoutIt() {
        val documented = v1Bytes(passphrase = "x y".encodeToByteArray(), flags = 8) + sendSection(TEST_TXID.encodeToByteArray(), 0x0102030405060708)
        val back = assertIs<UnlockResult.Opened>(opened(documented), "documented bytes")
        assertEquals(UnconfirmedSend(TEST_TXID, 0x0102030405060708), back.oldGuard)
        val store = FakeSecureStore()
        assertIs<SaveResult.Written>(SavedWallet(store).save(back.record, WriteMode.Create), "the save of the opened record")
        assertTrue(store.writes.single().bytes.contentEquals(v1Bytes(passphrase = "x y".encodeToByteArray())), "the record saved again kept flag 8 or the section")
    }

    @Test
    fun aPaymentOfUnknownOutcomeThatTheEncoderNeverWritesIsCorrupt() {
        fun withSend(txid: String, since: Long = 1) = v1Bytes(flags = 8) + sendSection(txid.encodeToByteArray(), since)
        val corrupt = listOf(
            "a txid of 65" to withSend(TEST_TXID + "0"),
            "a txid of 63" to withSend(TEST_TXID.drop(1)),
            "an uppercase txid" to withSend(TEST_TXID.uppercase()),
            "a txid that is not hex" to withSend(TEST_TXID.dropLast(1) + "g"),
            "negative seconds" to withSend(TEST_TXID, -1),
            "the flag without the section" to v1Bytes(flags = 8),
            "the section without the flag" to v1Bytes() + sendSection(TEST_TXID.encodeToByteArray(), 1),
        )
        for ((case, bytes) in corrupt) assertBroken(Cause.Corrupt, bytes, case)
        assertIs<UnlockResult.Opened>(opened(withSend(TEST_TXID)), "the untouched section opens")
        val size = withSend(TEST_TXID).size
        for (cut in 0 until size) assertBroken(Cause.Corrupt, withSend(TEST_TXID).copyOf(cut), "cut to $cut of $size bytes")
    }

    @Test
    fun everyByteChangeOfAnOlderRecordsGuardOpensExactlyOrIsRefused() {
        for (pending in listOf(UnconfirmedSend("", 0), UnconfirmedSend(TEST_TXID, 1_798_000_000))) {
            val good = oldRecordBytes(pending)
            assertEquals(v1Bytes().size + 2 + pending.txid.length + 8, good.size, "the size with the section, txid of ${pending.txid.length}")
            for (at in listOf(4) + (v1Bytes().size until good.size)) {
                for (value in 0..255) {
                    if (value.toByte() == good[at]) continue
                    val bytes = good.copyOf().also { it[at] = value.toByte() }
                    val case = "txid of ${pending.txid.length}, byte $at set to $value"
                    when (val r = opened(bytes.copyOf())) {
                        is UnlockResult.Opened -> assertTrue(layout(r).contentEquals(bytes), "opened as other bytes than these, $case")
                        is UnlockResult.Broken -> assertTrue(r.cause == Cause.Corrupt || r.cause == Cause.NewerVersion, "${r.cause}, $case")
                        else -> fail("an answer no decode gives, $case")
                    }
                }
            }
        }
    }

    /** An opened record of the public test words and its old guard, laid out by hand as the codec's KDoc documents version 1. */
    private fun layout(r: UnlockResult.Opened): ByteArray {
        val flags = (if (r.record.backupConfirmed) 1 else 0) or (if (r.record.imported) 2 else 0) or (if (r.record.appLockEnabled) 4 else 0) or
            (TYPE_BITS.getValue(r.record.addressType))
        return r.oldGuard?.let { oldRecordBytes(it, flags) } ?: v1Bytes(flags = flags)
    }

    @Test
    fun unknownVersionIsNewerNotCorrupt() {
        for (version in listOf(2, 255)) assertBroken(Cause.NewerVersion, v1Bytes(version = version), "version $version")
        assertBroken(Cause.NewerVersion, byteArrayOf('M'.code.toByte(), 'B'.code.toByte(), 2), "a newer header and nothing else")
    }

    @Test
    fun eachAddressTypeWritesItsDocumentedBitsAndReadsBack() {
        for ((type, bits) in TYPE_BITS) {
            val store = FakeSecureStore()
            SavedWallet(store).save(testRecord(passphrase = "x y", backupConfirmed = true, addressType = type), WriteMode.Create)
            val documented = v1Bytes(passphrase = "x y".encodeToByteArray(), flags = 1 or bits)
            assertTrue(store.writes.single().bytes.contentEquals(documented), "encoder output, $type")
            val back = assertIs<UnlockResult.Opened>(opened(documented), "documented bytes, $type").record
            assertEquals(type, back.addressType, "type")
            assertTrue(back.backupConfirmed, "backup beside $type")
        }
    }

    // A record from before the address type, and every Native SegWit record since, sets neither bit and reads as Native SegWit.
    @Test
    fun aRecordWithoutTypeBitsIsNativeSegwit() {
        assertEquals(AddressType.NATIVE_SEGWIT, testRecord().addressType)
        assertEquals(AddressType.NATIVE_SEGWIT, assertIs<UnlockResult.Opened>(opened(v1Bytes(flags = 7))).record.addressType)
    }

    @Test
    fun unknownFlagBitsAndNetworkBytesAreNewer() {
        for (bit in listOf(64, 128)) {
            assertBroken(Cause.NewerVersion, v1Bytes(flags = 1 or bit), "flag bit $bit")
            assertBroken(Cause.NewerVersion, v1Bytes(flags = 8 or bit) + sendSection(TEST_TXID.encodeToByteArray(), 1), "flag bit $bit beside a payment of unknown outcome")
        }
        for (network in listOf(2, 255)) assertBroken(Cause.NewerVersion, v1Bytes(network = network), "network byte $network")
    }

    @Test
    fun badMagicShortTrailingBytesAreCorrupt() {
        assertIs<UnlockResult.Opened>(opened(v1Bytes()), "the untouched layout opens")
        assertBroken(Cause.Corrupt, v1Bytes().also { it[0] = 'm'.code.toByte() }, "bad magic, first byte")
        assertBroken(Cause.Corrupt, v1Bytes().also { it[1] = 'b'.code.toByte() }, "bad magic, second byte")
        val size = v1Bytes().size
        for (cut in 0 until size) assertBroken(Cause.Corrupt, v1Bytes().copyOf(cut), "cut to $cut of $size bytes")
        assertBroken(Cause.Corrupt, v1Bytes() + 0.toByte(), "one trailing byte")
        assertBroken(Cause.Corrupt, v1Bytes(version = 0), "version 0, never written")
    }

    @Test
    fun wordCountOutsideBip39SizesIsCorrupt() {
        fun words(count: Int) = List(count) { "abandon" }.joinToString(" ").encodeToByteArray()
        for (count in listOf(0, 1, 11, 13, 23, 25)) assertBroken(Cause.Corrupt, v1Bytes(words = words(count)), "$count words")
        for (count in WalletRecord.BIP39_SIZES) assertIs<UnlockResult.Opened>(opened(v1Bytes(words = words(count))), "$count words")
    }

    @Test
    fun emptyWordIsCorrupt() {
        val eleven = List(11) { "abandon" }
        val twelveWithAnEmptyOne = listOf(
            "leading space" to listOf("") + eleven,
            "trailing space" to eleven + "",
            "double space" to eleven.take(5) + "" + eleven.drop(5),
        )
        for ((case, tokens) in twelveWithAnEmptyOne) {
            assertBroken(Cause.Corrupt, v1Bytes(words = tokens.joinToString(" ").encodeToByteArray()), case)
        }
    }

    @Test
    fun badUtf8IsCorrupt() {
        val malformed = listOf(
            "a lone continuation byte" to byteArrayOf(0x80.toByte()),
            "an overlong encoding" to byteArrayOf(0xC0.toByte(), 0xAF.toByte()),
            "a cut three-byte character" to byteArrayOf(0xE0.toByte(), 0xB8.toByte()),
            "an encoded surrogate" to byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()),
            "a byte UTF-8 never uses" to byteArrayOf(0xFF.toByte()),
        )
        val twelveWords = List(12) { "abandon" }.joinToString(" ").encodeToByteArray()
        for ((name, bad) in malformed) {
            assertBroken(Cause.Corrupt, v1Bytes(words = twelveWords + bad), "in a word, $name")
            assertBroken(Cause.Corrupt, v1Bytes(passphrase = bad), "in the passphrase, $name")
        }
    }

    @Test
    fun aFingerprintThatIsNotEightAsciiBytesIsCorrupt() {
        val twoByteCharacter = byteArrayOf(0xC3.toByte(), 0xA9.toByte())
        assertBroken(Cause.Corrupt, v1Bytes(fingerprint = twoByteCharacter + "0a1b2c".encodeToByteArray()), "8 bytes, 7 characters")
        assertBroken(Cause.Corrupt, v1Bytes(fingerprint = TEST_CHECK.encodeToByteArray().also { it[7] = 0x80.toByte() }), "a byte above ASCII")
    }

    @Test
    fun encodeRefusesWhatItCannotWriteBackExactlyAndNothingIsWritten() {
        val refused = listOf(
            "a passphrase of 65536 bytes" to testRecord(passphrase = "p".repeat(65_536)),
            "a passphrase UTF-8 cannot carry" to testRecord(passphrase = "a" + Char(0xD800)),
            "words over 65535 bytes" to testRecord(words = List(12) { "w".repeat(5_500) }),
            "a word with a space" to testRecord(words = PUBLIC_WORDS_12.dropLast(1) + "ab out"),
            "an empty word" to testRecord(words = PUBLIC_WORDS_12.dropLast(1) + ""),
            "a fingerprint of 7" to testRecord(fingerprint = "0a1b2c3"),
            "a fingerprint of 9" to testRecord(fingerprint = "0a1b2c3d4"),
            "a fingerprint beyond ASCII" to testRecord(fingerprint = "0a1b2c3" + Char(0xE9)),
        )
        for ((case, record) in refused) {
            val store = FakeSecureStore()
            val result = SavedWallet(store).save(record, WriteMode.Create)
            assertEquals(Why.Other, assertIs<SaveResult.NotSaved>(result, case).why, case)
            assertTrue(store.writes.isEmpty() && store.markerWrites.isEmpty(), "something was written, $case")
        }
    }

    @Test
    fun recordPrintsNoWord() {
        val printed = testRecord(passphrase = "correct-horse-test").toString()
        assertFalse(PUBLIC_WORDS_24.any { it in printed }, "a word in toString")
        assertFalse("correct-horse-test" in printed, "the passphrase in toString")
        assertFalse(TEST_CHECK in printed, "the fingerprint in toString")
    }

    @Test
    fun aRecordNeedsABip39WordCount() {
        for (count in listOf(0, 11, 13, 25)) {
            val error = assertFailsWith<IllegalArgumentException>("$count words") { testRecord(words = List(count) { "abandon" }) }
            assertFalse("abandon" in error.message.orEmpty(), "the message names a word, $count words")
        }
    }

    private companion object {
        /** The flag bits of each address type, as the codec's KDoc documents them. */
        val TYPE_BITS = mapOf(
            AddressType.NATIVE_SEGWIT to 0,
            AddressType.LEGACY to 16,
            AddressType.NESTED_SEGWIT to 32,
            AddressType.TAPROOT to 48,
        )
    }
}
