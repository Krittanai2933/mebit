package com.mebit.app.feature.keys

import com.mebit.app.data.HardwareWallet
import com.mebit.app.data.KeySourceLabel
import com.mebit.app.data.SavedKey
import com.mebit.app.feature.vault.SINGLE_SIG_POLICY_EYEBROW
import com.mebit.app.feature.vault.hardwareCreatedSubtitle
import com.mebit.app.feature.vault.hardwareTypeNote
import com.mebit.app.feature.vault.oneKeyNote
import com.mebit.ffi.signer.AddressType
import com.mebit.ffi.signer.WalletKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HardwareReadTextTest {
    private val jade = SavedKey("key_1", "Jade ของฉัน", "7f2ca91b", "tpub", "m/48'/1'/0'/2'", KeySourceLabel.Jade, verified = true)
    private val safe7 = jade.copy(id = "key_2", label = "Trezor ในตู้เซฟ", source = KeySourceLabel.TrezorSafe7)

    @Test
    fun theCodeFieldShowsOnlyWhileTheSafe7AsksAndARememberedPairingShowsNone() {
        assertEquals(Safe7Card.Radar, safe7Card(kept = false, asked = false), "a first connect before the Safe 7 asks")
        assertEquals(Safe7Card.FirstPairing, safe7Card(kept = false, asked = true), "2.3.4")
        assertEquals(Safe7Card.Remembered, safe7Card(kept = true, asked = false), "2.3.5")
        assertEquals(Safe7Card.Forgot, safe7Card(kept = true, asked = true), "2.3.6")
    }

    @Test
    fun aSafe7sRowSaysHowItIsBeingReached() {
        assertEquals("กำลังจับคู่ครั้งแรก", safe7RowLine(Safe7Card.FirstPairing))
        assertEquals("กำลังจับคู่ครั้งแรก", safe7RowLine(Safe7Card.Radar))
        assertEquals("จับคู่ไว้แล้ว · กำลังเชื่อม", safe7RowLine(Safe7Card.Remembered))
        assertEquals("ต้องจับคู่ใหม่", safe7RowLine(Safe7Card.Forgot))
    }

    @Test
    fun eachBoardHasItsOwnInstructionAndSteps() {
        val jadeRead = DeviceRead.OneKey(jade, AddressType.NATIVE_SEGWIT)
        val safe7Read = DeviceRead.OneKey(safe7, AddressType.TAPROOT)
        assertEquals("เปิด Jade แล้ววางไว้ใกล้มือถือ จากนั้นใส่ PIN บน Jade (มือถือต้องต่ออินเทอร์เน็ต)", readInstruction(jadeRead, bluetoothOff = false, card = null), "2.3.3")
        assertEquals(listOf("ค้นหาอุปกรณ์ผ่าน Bluetooth", "ใส่ PIN บน Jade", "อ่านกุญแจ Native SegWit (m/84'/1'/0')"), readSteps(jadeRead, null), "2.3.3")
        assertEquals("อ่านกุญแจ Taproot (m/86'/1'/0')", readSteps(jadeRead.copy(type = AddressType.TAPROOT), null).last())
        assertEquals("เปิด Trezor Safe 7 ไปที่ Pair new device แล้ววางไว้ใกล้มือถือ", readInstruction(safe7Read, bluetoothOff = false, card = Safe7Card.FirstPairing), "2.3.4")
        assertEquals(listOf("ค้นหาอุปกรณ์ผ่าน Bluetooth", "กด Allow บนหน้าจอ Trezor", "อ่านกุญแจสาธารณะ (xpub)"), readSteps(safe7Read, Safe7Card.Remembered), "2.3.5")
        assertEquals(listOf("ค้นหาอุปกรณ์ผ่าน Bluetooth", "ยืนยันรหัสจับคู่บนหน้าจออุปกรณ์", "อ่านกุญแจสาธารณะ (xpub)"), readSteps(safe7Read, Safe7Card.Forgot), "2.3.6")
        assertEquals("เปิด Jade หรือ Trezor Safe 7 แล้ววางไว้ใกล้มือถือ", readInstruction(DeviceRead.AddKey, bluetoothOff = false, card = null), "2.3.1")
        assertEquals("เปิด Trezor Safe 7 ไปที่ Pair new device แล้ววางไว้ใกล้มือถือ", readInstruction(DeviceRead.AddKey, bluetoothOff = false, card = Safe7Card.FirstPairing), "Add Key once the Safe 7 asks")
        assertEquals("เปิด Bluetooth บนมือถือ เพื่อให้มองเห็น Jade หรือ Trezor Safe 7", readInstruction(jadeRead, bluetoothOff = true, card = null), "2.3.2")
    }

    @Test
    fun theOneKeyStepsSayWhichDeviceTheWalletUses() {
        assertEquals("POLICY · ขั้น 1/3", SINGLE_SIG_POLICY_EYEBROW, "3.1.1")
        assertNull(oneKeyNote(null), "3.2.1: this phone has no line")
        assertEquals("Jade จะเป็นกุญแจเดียวของกระเป๋านี้ ทุกครั้งที่ส่งต้องกดยืนยันบน Jade", oneKeyNote(jade), "3.2.2")
        assertEquals("Trezor จะเป็นกุญแจเดียวของกระเป๋านี้ ทุกครั้งที่ส่งต้องกดยืนยันบน Trezor", oneKeyNote(safe7), "3.2.3")
        assertEquals("ตัวเลือกนี้กำหนดรูปแบบที่อยู่ของกระเป๋านี้ ขั้นถัดไป mebit จะอ่านกุญแจของแบบนี้จาก Jade", hardwareTypeNote(jade), "3.3.2")
        val created = HardwareWallet("hw_1", "กระเป๋า Jade", WalletKind.JADE, AddressType.NATIVE_SEGWIT, "73c5da0a", "tpub", "tb1q6rz28mcfaxtmd6v789l9rrlrusdprr9pqcpvkl")
        assertEquals("Native SegWit · tb1q…pvkl (ที่อยู่แรก) · กุญแจอยู่บน Jade", hardwareCreatedSubtitle(created), "3.5.2")
        assertEquals("Taproot · tb1q…pvkl (ที่อยู่แรก) · กุญแจอยู่บน Trezor", hardwareCreatedSubtitle(created.copy(kind = WalletKind.TREZOR_SAFE7, addressType = AddressType.TAPROOT)))
    }
}
