package com.mebit.app.feature.send

import com.mebit.ffi.signer.BleException
import com.mebit.ffi.signer.MobileSignerException
import com.mebit.ffi.signer.SpendOutput
import com.mebit.ffi.signer.SpendReview
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HardwareSignTest {
    @Test
    fun theStepsMarkWhereTheSignIs() {
        val (done, now, todo, failed) = listOf(StepMark.Done, StepMark.Current, StepMark.Pending, StepMark.Failed)
        assertEquals(listOf(now, todo, todo), signMarks(SignStage.Connecting), "reaching the device")
        assertEquals(listOf(done, now, todo), signMarks(SignStage.OnDevice), "4.5.3: the device shows the payment")
        assertEquals(listOf(done, done, done), signMarks(SignStage.Signed), "4.5.5")
        assertEquals(listOf(done, done, failed), signMarks(SignStage.Failed(DeviceFailure.Rejected, shown = true)), "5.3.2.1: refused on the device")
        assertEquals(listOf(failed, todo, todo), signMarks(SignStage.Failed(DeviceFailure.NotFound, shown = false)), "no device reached")
    }

    @Test
    fun eachErrorShowsItsBoard() {
        assertEquals(DeviceFailure.Rejected, deviceFailure(BleException.Refused("declined on the device")))
        assertEquals(DeviceFailure.Rejected, deviceFailure(MobileSignerException.InvalidPsbtJson("signatures: 0 of 1")), "the core refused what came back: nothing kept")
        assertEquals(DeviceFailure.WrongKey, deviceFailure(BleException.WrongKey("another key")))
        assertEquals(DeviceFailure.WrongKey, deviceFailure(BleException.WrongDevice("a Safe 3")))
        assertEquals(DeviceFailure.NotFound, deviceFailure(BleException.NotFound("none in range")))
        assertEquals(DeviceFailure.BluetoothOff, deviceFailure(BleException.BluetoothOff("off")))
        for (lost in listOf(BleException.Disconnected("down"), BleException.Timeout("slow"), BleException.Link("gatt"), BleException.PairingFailed("code"), IllegalStateException())) {
            assertEquals(DeviceFailure.Lost, deviceFailure(lost), lost.toString())
        }
    }

    @Test
    fun theFailuresSayWhatTheBoardsSayNamingTheDevice() {
        val rejected = deviceFailureText(DeviceFailure.Rejected, "Jade")
        assertEquals(listOf("อุปกรณ์ปฏิเสธการเซ็น", "คุณกดปฏิเสธบน Jade หรืออุปกรณ์ไม่รับรายการนี้ ไม่มีลายเซ็นถูกบันทึก", "ปฏิเสธ", "DEVICE REJECTED"), listOf(rejected.title, rejected.subtitle, rejected.pill, rejected.kicker), "5.3.2.1")
        assertEquals("Jade ปฏิเสธรายการ — ไม่มีลายเซ็นถูกบันทึก เงินยังไม่ถูกส่ง ตรวจรายการแล้วลองอีกครั้งได้", rejected.line)
        val lost = deviceFailureText(DeviceFailure.Lost, "Trezor")
        assertEquals(listOf("เชื่อมต่ออุปกรณ์หลุด", "Bluetooth หลุดระหว่างรอยืนยัน ย้าย Trezor ให้ใกล้มือถือ แล้วลองใหม่", "หลุด", "CONNECTION LOST"), listOf(lost.title, lost.subtitle, lost.pill, lost.kicker), "5.3.3.1")
        assertEquals("หมดเวลารอการยืนยันจาก Trezor — ไม่มีลายเซ็นถูกบันทึก เงินยังไม่ถูกส่ง", lost.line)
        val wrong = deviceFailureText(DeviceFailure.WrongKey, "Jade")
        assertEquals("Fingerprint ไม่ตรง", wrong.title, "a device that holds another key")
        assertNull(wrong.subtitle)
        assertEquals("ไม่มีลายเซ็นถูกบันทึก เงินยังไม่ถูกส่ง", wrong.line)
    }

    @Test
    fun aDraftPaysTheSumOfItsPaymentsWithoutItsChange() {
        val review = SpendReview("psbt", "txid", listOf(SpendOutput("tb1qa", 5_000_000uL), SpendOutput("tb1qb", 1_000uL)), 79_198_400uL, 1_600uL, emptyList(), 1u)
        assertEquals(5_001_000L, review.paidSats())
    }
}
