package com.mebit.app.feature.vault

import com.mebit.app.data.CreatedVault
import com.mebit.app.data.CustodyPolicy
import com.mebit.app.data.FiatQuote
import com.mebit.app.data.KeySourceLabel
import com.mebit.app.data.SavedKey
import com.mebit.app.data.walletBsms
import com.mebit.app.data.walletDescriptor
import com.mebit.app.feature.send.HISTORY_EMPTY_TEXT
import com.mebit.app.feature.send.KeyRowText
import com.mebit.app.feature.send.vaultLine
import com.mebit.ffi.signer.HotTx
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

private fun key(n: Int, source: KeySourceLabel, label: String, fingerprint: String) =
    SavedKey("key_$n", label, fingerprint, "tpub$n", "m/48'/1'/0'/2'", source, verified = true)

private val phone = key(1, KeySourceLabel.ThisDevice, "iPhone ของฉัน", "3a9f21c4")
private val jade = key(2, KeySourceLabel.Jade, "Jade ของฉัน", "7f2ca91b")
private val trezor = key(3, KeySourceLabel.TrezorSafe7, "Trezor ในตู้เซฟ", "c04d88e1")

/** A 2-of-3 over [keys] in this order, its BSMS built by the functions Create uses, so the test reads what the app writes. */
private fun vaultOf(keys: List<SavedKey>) = CreatedVault(
    "vault_1", "Wallet ครอบครัว", 2, 3, CustodyPolicy.PersonalCustody, "tb1qvault",
    walletBsms(walletDescriptor(2, keys), keys, "tb1qvault"), keys.map { it.fingerprint },
)

private val family = vaultOf(listOf(phone, jade, trezor))

class VaultDetailTest {
    @Test
    fun theKeysOf371AreReadBackWholeInTheOrderTheWalletWasMade() {
        assertEquals(
            listOf(
                KeyRowText("iPhone ของฉัน", "mebit บนเครื่องนี้ · 3a9f 21c4", "vault_key_line_0"),
                KeyRowText("Jade ของฉัน", "Jade · 7f2c a91b", "vault_key_line_1"),
                KeyRowText("Trezor ในตู้เซฟ", "Trezor Safe 7 · c04d 88e1", "vault_key_line_2"),
            ),
            vaultKeyRows(family, keyring = listOf(trezor, jade, phone)),
            "the BSMS order, not the keyring's, with 3.7.1's texts",
        )
    }

    @Test
    fun aKeyringKeyWithTheSameFingerprintButAnotherXpubNamesNoRow() {
        val sameSeedOtherAccount = jade.copy(id = "key_9", label = "Jade บัญชีอื่น", xpub = "tpub-other")
        assertEquals(
            KeyRowText("กุญแจ 2", "ไม่ทราบที่มา · 7f2c a91b", "vault_key_line_1"),
            vaultKeyRows(family, keyring = listOf(phone, sameSeedOtherAccount, trezor))[1],
            "one seed at another account path shares the fingerprint, so only the xpub tells them apart",
        )
    }

    @Test
    fun aFingerprintMatchesWhateverItsCase() {
        val rows = vaultKeyRows(family, keyring = listOf(phone, jade.copy(fingerprint = "7F2CA91B"), trezor))
        assertEquals("Jade ของฉัน", rows[1].name)
    }

    @Test
    fun aKeyTheKeyringDoesNotNameKeepsItsNumberedRow() {
        val rows = vaultKeyRows(family, keyring = listOf(jade))
        assertEquals(listOf("กุญแจ 1", "Jade ของฉัน", "กุญแจ 3"), rows.map { it.name }, "always n rows, each at its own number")
        assertEquals("ไม่ทราบที่มา · 3a9f 21c4", rows[0].line, "the fingerprint the wallet holds, in groups of four")
    }

    @Test
    fun aBsmsWhoseKeysCannotBeReadNamesNoKeyRatherThanAWrongOne() {
        val broken = family.copy(bsms = "BSMS 1.0\nnot a descriptor")
        assertEquals(listOf("กุญแจ 1", "กุญแจ 2", "กุญแจ 3"), vaultKeyRows(broken, listOf(phone, jade, trezor)).map { it.name })
    }

    @Test
    fun unknownAndEmptyHistoriesSayDifferentThings() {
        val tx = HotTx("t1", 12_000_000, 6u, 1_787_036_520uL)
        assertEquals(VAULT_TX_READING, vaultTxNote(history = null, reading = true))
        assertEquals(VAULT_TX_UNKNOWN, vaultTxNote(history = null, reading = false))
        assertEquals(HISTORY_EMPTY_TEXT, vaultTxNote(history = emptyList(), reading = false))
        assertEquals(HISTORY_EMPTY_TEXT, vaultTxNote(history = emptyList(), reading = true), "a known empty list stays empty during a re-read")
        assertNull(vaultTxNote(history = listOf(tx), reading = false), "rows show")
        assertNotEquals(vaultTxNote(null, reading = false), vaultTxNote(emptyList(), reading = false))
    }

    @Test
    fun theHeroShowsTheChoosersBalanceAndBahtOnceKnownAndNeverAGuess() {
        val quote = FiatQuote(thbPerBtc = 3_602_256.0, fetchedAtSeconds = 0L)
        val known = vaultLine(family, 84_200_000, quote)
        assertEquals("0.8420 BTC", known.balance)
        assertEquals("≈ ฿3,033,100", vaultHeroLine(84_200_000, known.baht, reading = false), "4.2's line for 0.8420 BTC")
        assertEquals("≈ ฿3,033,100", vaultHeroLine(84_200_000, known.baht, reading = true), "a re-read keeps the known figure's line")
        assertEquals("—", vaultLine(family, null, quote).balance)
        assertEquals("กำลังดึงยอด…", vaultHeroLine(null, null, reading = true))
        assertNull(vaultHeroLine(null, null, reading = false), "an unknown balance is the dash alone")
        assertNull(vaultHeroLine(84_200_000, vaultLine(family, 84_200_000, null).baht, reading = false), "no price, no line, as on Home")
    }

    @Test
    fun theEyebrowNamesThePolicyAs37Does() {
        assertEquals("MULTISIG 2-OF-3", vaultEyebrow(family))
    }
}
