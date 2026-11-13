package com.mebit.app.feature.send

import com.mebit.app.data.ANSWER_REFUSED_LINE
import com.mebit.app.data.AnswerRefused
import com.mebit.app.data.CANNOT_REGISTER_LINE
import com.mebit.app.data.NOT_READY_LINE
import com.mebit.app.data.REGISTRATION_DECLINED_LINE
import com.mebit.app.data.SEVERAL_DEVICES_LINE
import com.mebit.app.data.SeveralDevices
import com.mebit.ffi.signer.BleException
import com.mebit.ffi.signer.MobileSignerException
import com.mebit.ffi.signer.SpendOutput
import com.mebit.ffi.signer.SpendReview
import com.mebit.ffi.signer.WalletKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
    fun eachErrorShowsItsBoardAtTheStepItCameAt() {
        val declined = BleException.Refused("declined on the device")
        assertEquals(DeviceFailure.Rejected, deviceFailure(declined, SignStep.Showing), "5.3.2.1: declined while the payment showed")
        assertEquals(DeviceFailure.RegistrationDeclined, deviceFailure(declined, SignStep.Registering))
        assertEquals(DeviceFailure.NotReady, deviceFailure(declined, SignStep.Connecting), "locked, a wrong PIN, no internet: nothing showed yet")
        for (step in SignStep.entries) {
            val answered = listOf(
                BleException.Protocol("the device's signed PSBT: key 73c5da0a has not signed every input"),
                AnswerRefused("the answer is signed by []"),
                MobileSignerException.InvalidPsbtJson("signatures: 0 of 1"),
            )
            for (refusedAnswer in answered) {
                assertEquals(DeviceFailure.BadAnswer, deviceFailure(refusedAnswer, step), "$refusedAnswer at $step: the device answered and mebit refused it")
            }
            assertEquals(DeviceFailure.RegistrationRefused, deviceFailure(BleException.RegistrationRefused("full"), step))
            assertEquals(DeviceFailure.Several, deviceFailure(SeveralDevices(WalletKind.JADE, 2), step))
            assertEquals(DeviceFailure.WrongKey, deviceFailure(BleException.WrongKey("another key"), step))
            assertEquals(DeviceFailure.WrongKey, deviceFailure(BleException.WrongDevice("a Safe 3"), step))
            assertEquals(DeviceFailure.NotFound, deviceFailure(BleException.NotFound("none in range"), step))
            assertEquals(DeviceFailure.BluetoothOff, deviceFailure(BleException.BluetoothOff("off"), step))
            for (lost in listOf(BleException.Disconnected("down"), BleException.Timeout("slow"), BleException.Link("gatt"), BleException.PairingFailed("code"), IllegalStateException())) {
                assertEquals(DeviceFailure.Lost, deviceFailure(lost, step), "$lost at $step")
            }
        }
    }

    @Test
    fun theProposalStatesSayTheProposedLinesAndARefusedAnswerNeverReadsAsTheDevicesRefusal() {
        val proposals = listOf(DeviceFailure.Several, DeviceFailure.NotReady, DeviceFailure.BadAnswer, DeviceFailure.RegistrationDeclined, DeviceFailure.RegistrationRefused)
        assertEquals(
            listOf(SEVERAL_DEVICES_LINE, NOT_READY_LINE, ANSWER_REFUSED_LINE, REGISTRATION_DECLINED_LINE, CANNOT_REGISTER_LINE),
            proposals.map { deviceFailureText(it, "Jade").line },
        )
        val rejected = deviceFailureText(DeviceFailure.Rejected, "Jade")
        val badAnswer = deviceFailureText(DeviceFailure.BadAnswer, "Jade")
        assertNotEquals(rejected.title, badAnswer.title)
        assertNotEquals(rejected.kicker, badAnswer.kicker)
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
