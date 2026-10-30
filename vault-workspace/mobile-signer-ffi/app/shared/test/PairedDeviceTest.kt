package com.mebit.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PairedDeviceTest {
    @Test
    fun fingerprintIsLowercaseInGroupsOfFour() {
        assertEquals("7f2c a91b", fingerprintGroups("7F2CA91B"))
    }

    @Test
    fun aShortFingerprintIsNotPadded() {
        assertEquals("7f2c a9", fingerprintGroups("7F2CA9"))
        assertEquals("7f2", fingerprintGroups("7F2"))
    }

    @Test
    fun anEmptyFingerprintStaysEmpty() {
        assertEquals("", fingerprintGroups(""))
    }

    @Test
    fun noKeysGiveNoDevice() {
        assertNull(pairedDevice(emptyList()))
    }

    @Test
    fun phoneKeysAreNotDevices() {
        val phoneOnly = listOf(key(KeySourceLabel.ThisDevice, "11111111"), key(KeySourceLabel.MebitOther, "22222222"))
        assertNull(pairedDevice(phoneOnly))
    }

    @Test
    fun theNewestHardwareKeyWinsOverOlderOnesAndLaterPhoneKeys() {
        val keys = listOf(
            key(KeySourceLabel.Jade, "AAAA0001"),
            key(KeySourceLabel.Jade, "BBBB0002"),
            key(KeySourceLabel.ThisDevice, "CCCC0003"),
        )
        assertEquals("BBBB0002", pairedDevice(keys)?.fingerprint)
    }

    @Test
    fun aJadeIsNamedJadeWithMonogramJD() {
        val device = pairedDevice(listOf(key(KeySourceLabel.Jade, "7F2CA91B")))
        assertEquals(PairedDevice(vendor = "Jade", monogram = "JD", fingerprint = "7F2CA91B"), device)
        assertEquals("Jade ของฉัน", device?.name)
        assertEquals("Bluetooth · 7f2c a91b", device?.meta)
    }

    @Test
    fun aSafe7IsNamedByItsModelWithMonogramTZ() {
        val device = pairedDevice(listOf(key(KeySourceLabel.TrezorSafe7, "7F2CA91B")))
        assertEquals(PairedDevice(vendor = "Trezor Safe 7", monogram = "TZ", fingerprint = "7F2CA91B"), device)
        assertEquals("Trezor Safe 7 ของฉัน", device?.name)
    }
}

private fun key(source: KeySourceLabel, fingerprint: String) = SavedKey(
    id = "key_$fingerprint",
    label = source.name,
    fingerprint = fingerprint,
    xpub = "xpub-not-read",
    derivationPath = "m/48'/1'/0'/2'",
    source = source,
    verified = true,
)
