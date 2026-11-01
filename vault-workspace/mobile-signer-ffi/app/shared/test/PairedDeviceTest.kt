package com.mebit.app.data

import com.mebit.ffi.signer.WalletKind
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
        val device = pairedDevice(listOf(key(KeySourceLabel.Jade, "7F2CA91B", "Jade ของฉัน")))
        assertEquals(PairedDevice(name = "Jade ของฉัน", vendor = "Jade", monogram = "JD", fingerprint = "7F2CA91B"), device)
        assertEquals("Bluetooth · 7f2c a91b", device?.meta)
        assertEquals("เชื่อมแล้ว", device?.status)
    }

    @Test
    fun theDeviceShowsTheSavedKeyNameSoARenameCarriesOver() {
        val device = pairedDevice(listOf(key(KeySourceLabel.Jade, "7F2CA91B", "กุญแจบ้าน")))!!
        assertEquals("กุญแจบ้าน", device.name)
        assertEquals("Jade", device.vendor)
    }

    @Test
    fun savedMockHasSimulatedNameMetaAndStatus() {
        val device = pairedDevice(listOf(key(KeySourceLabel.Jade, "7F2CA91B", "Jade · จำลอง").copy(isMock = true)))!!
        assertEquals("Jade · จำลอง", device.name)
        assertEquals("โหมดจำลอง · 7f2c a91b", device.meta)
        assertEquals("จำลอง", device.status)
    }

    @Test
    fun aSafe7IsNamedByItsModelWithMonogramTZ() {
        val device = pairedDevice(listOf(key(KeySourceLabel.TrezorSafe7, "7F2CA91B", "Trezor Safe 7 ของฉัน")))
        assertEquals(PairedDevice(name = "Trezor Safe 7 ของฉัน", vendor = "Trezor Safe 7", monogram = "TZ", fingerprint = "7F2CA91B"), device)
    }

    @Test
    fun aRealDeviceStartsAsMineAndADemoOneAsSimulated() {
        assertEquals("Jade ของฉัน", defaultKeyLabel(KeySourceLabel.Jade, isMock = false))
        assertEquals("Trezor Safe 7 ของฉัน", defaultKeyLabel(KeySourceLabel.TrezorSafe7, isMock = false))
        assertEquals("Jade · จำลอง", defaultKeyLabel(KeySourceLabel.Jade, isMock = true))
        assertEquals("Trezor Safe 7 · จำลอง", defaultKeyLabel(KeySourceLabel.TrezorSafe7, isMock = true))
    }

    @Test
    fun aScannedWalletKindMapsToItsKeySource() {
        assertEquals(KeySourceLabel.Jade, hardwareSource(WalletKind.JADE))
        assertEquals(KeySourceLabel.TrezorSafe7, hardwareSource(WalletKind.TREZOR_SAFE7))
    }
}

private fun key(source: KeySourceLabel, fingerprint: String, label: String = source.name) = SavedKey(
    id = "key_$fingerprint",
    label = label,
    fingerprint = fingerprint,
    xpub = "xpub-not-read",
    derivationPath = "m/48'/1'/0'/2'",
    source = source,
    verified = true,
)
