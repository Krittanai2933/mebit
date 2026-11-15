package com.mebit.app.data

import com.mebit.ffi.signer.WalletKind
import kotlin.test.Test
import kotlin.test.assertEquals

class HardwareLabelsTest {
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
    fun aJadeIsJDAndASafe7IsTRZ() {
        assertEquals("JD", hardwareMonogram(KeySourceLabel.Jade))
        assertEquals("TRZ", hardwareMonogram(KeySourceLabel.TrezorSafe7))
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
