package com.mebit.app.feature.send

import com.mebit.app.data.AnswerRefused
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
        assertEquals(listOf(done, done, failed), signMarks(SignStage.Failed(DeviceFailure.Rejected, SignStep.Showing)), "5.3.2.1: refused on the device")
        assertEquals(listOf(failed), signMarks(SignStage.Failed(DeviceFailure.NotFound, SignStep.Connecting)), "no device reached: the steps after it are not drawn")
    }

    @Test
    fun aJadeRegisteringTheWalletHasAFourthStep() {
        val (done, now, todo, failed) = listOf(StepMark.Done, StepMark.Current, StepMark.Pending, StepMark.Failed)
        assertEquals(listOf(done, now, todo, todo), signMarks(SignStage.Registering, registers = true), "5.3.2.0")
        assertEquals(listOf(done, done, now, todo), signMarks(SignStage.OnDevice, registers = true))
        assertEquals(listOf(done, done, done, done), signMarks(SignStage.Signed, registers = true))
        assertEquals(listOf(done, failed), signMarks(SignStage.Failed(DeviceFailure.RegistrationDeclined, SignStep.Registering), registers = true), "5.3.2.2")
        assertEquals(listOf(done, done, done, failed), signMarks(SignStage.Failed(DeviceFailure.Lost, SignStep.Showing), registers = true))
        assertEquals(
            listOf("เชื่อมต่อผ่าน Bluetooth", "ลงทะเบียน Wallet บน Jade", "ตรวจรายการบนหน้าจอ Jade", "กดยืนยันบนอุปกรณ์"),
            signStepLabels("Jade", registers = true),
        )
        assertEquals(3, signStepLabels("Trezor", registers = false).size)
    }

    @Test
    fun eachErrorShowsItsBoardAtTheStepItCameAt() {
        val declined = BleException.Refused("declined on the device")
        assertEquals(DeviceFailure.Rejected, deviceFailure(declined, SignStep.Showing), "5.3.2.1: declined while the payment showed")
        assertEquals(DeviceFailure.RegistrationDeclined, deviceFailure(declined, SignStep.Registering))
        assertEquals(DeviceFailure.NotReady, deviceFailure(declined, SignStep.Connecting), "locked, a wrong PIN, no internet: nothing showed yet")
        val locked = BleException.Protocol("the Jade is locked")
        assertEquals(DeviceFailure.NotReady, deviceFailure(locked, SignStep.Connecting), "a Protocol error before the device showed anything is not an answer")
        assertEquals(DeviceFailure.BadAnswer, deviceFailure(locked, SignStep.Showing))
        for (step in SignStep.entries) {
            val answered = listOf(
                BleException.Protocol("the device's signed PSBT: key 73c5da0a has not signed every input").takeIf { step != SignStep.Connecting },
                AnswerRefused("the answer is signed by []"),
                MobileSignerException.InvalidPsbtJson("signatures: 0 of 1"),
            )
            for (refusedAnswer in answered.filterNotNull()) {
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
    fun theProposalBoardsSayTheirOwnWordsAndARefusedAnswerNeverReadsAsTheDevicesRefusal() {
        fun row(failure: DeviceFailure, word: String = "Jade", name: String = "mebit2of3-3f1d3") = deviceFailureText(failure, word, name).let { listOf(it.title, it.subtitle, it.pill, it.line) }
        assertEquals(
            listOf("เจออุปกรณ์มากกว่าหนึ่งเครื่อง", "เจออุปกรณ์ชนิดเดียวกันมากกว่าหนึ่งเครื่อง ปิดเครื่องที่ไม่ใช้ แล้วลองใหม่", "2 เครื่อง", "ยังไม่มีอะไรถูกส่งไปที่อุปกรณ์ · เงินยังไม่ถูกส่ง"),
            row(DeviceFailure.Several),
            "5.3.x.6",
        )
        assertEquals(
            listOf("Jade ยังไม่พร้อม", "ปลดล็อก Jade ด้วย PIN บนตัวเครื่อง และให้มือถือต่ออินเทอร์เน็ต แล้วลองใหม่", "ยังไม่พร้อม", "ไม่มีลายเซ็นถูกบันทึก · เงินยังไม่ถูกส่ง"),
            row(DeviceFailure.NotReady),
            "5.3.2.4",
        )
        assertEquals("Trezor ยังไม่พร้อม", deviceFailureText(DeviceFailure.NotReady, "Trezor").title, "the line names the device that is not ready")
        assertEquals("ปลดล็อก Trezor ด้วย PIN บนตัวเครื่อง และให้มือถือต่ออินเทอร์เน็ต แล้วลองใหม่", deviceFailureText(DeviceFailure.NotReady, "Trezor").subtitle)
        assertEquals(
            listOf("mebit ไม่นับลายเซ็นนี้", "ลายเซ็นที่อุปกรณ์ส่งกลับมาไม่ตรงกับรายการที่คุณเห็น mebit จึงไม่นับ", "ไม่นับ", "ไม่มีลายเซ็นถูกบันทึก · ลองเซ็นอีกครั้ง"),
            row(DeviceFailure.BadAnswer),
            "5.3.x.5",
        )
        assertEquals(
            listOf("ยังไม่ได้ลงทะเบียน Wallet", "คุณกดปฏิเสธการลงทะเบียนบน Jade จึงยังเซ็นไม่ได้ ไม่มีลายเซ็นถูกบันทึก", "ปฏิเสธ", "Jade ต้องรู้จัก Wallet ก่อนจึงจะเซ็นได้ · ลองอีกครั้ง"),
            row(DeviceFailure.RegistrationDeclined),
            "5.3.2.2",
        )
        assertEquals(
            listOf("ลงทะเบียนบน Jade ไม่ได้", "Jade เต็ม หรือมี Wallet อื่นใช้ชื่อ mebit2of3-3f1d3 อยู่แล้ว mebit จึงไม่เซ็น", "ไม่ได้", "ลบ Wallet ที่ไม่ใช้ออกจาก Jade แล้วลองใหม่"),
            row(DeviceFailure.RegistrationRefused),
            "5.3.2.3",
        )
        for (failure in listOf(DeviceFailure.Several, DeviceFailure.NotReady, DeviceFailure.BadAnswer, DeviceFailure.RegistrationDeclined, DeviceFailure.RegistrationRefused)) {
            assertNull(deviceFailureText(failure, "Jade").kicker, "$failure is the warning bar, not the kicker card")
        }
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
