package com.mebit.app.feature.send

import com.mebit.app.data.Replacement
import com.mebit.app.data.SingleSigWallet.DamagedGuard
import com.mebit.app.data.SingleSigWallet.UnconfirmedSend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The words of a guard's controls, pinned here because the module has no Compose UI tests. Every line is a draft awaiting design review. */
class UnconfirmedClearCopyTest {
    // Any transaction of a chain may be the one that is out, so the clear's confirm names each one, oldest first, and asks for every one
    // to be checked. A single payment and a damaged guard keep their line and its warning.
    @Test
    fun theClearsConfirmNamesEveryTransactionOfAChain() {
        val single = clearConfirm(UnconfirmedSend(txid('a'), SENT_AT, HEX))
        assertEquals("txid aaaa … aaaa", single.named)
        assertEquals("กดต่อเมื่อตรวจใน explorer แล้วว่าไม่มีธุรกรรมนี้ ถ้าส่งไปแล้วจะจ่ายซ้ำ", single.warning)
        val chain = clearConfirm(UnconfirmedSend(txid('c'), SENT_AT, HEX, listOf(txid('a'), txid('b')), holdsCancel = true))
        assertEquals("txid aaaa … aaaa\ntxid bbbb … bbbb\ntxid cccc … cccc", chain.named)
        assertEquals("กดต่อเมื่อตรวจใน explorer แล้วว่าไม่มีธุรกรรมใดในนี้ ถ้ามีรายการใดส่งไปแล้วจะจ่ายซ้ำ", chain.warning)
        assertEquals(listOf("unconfirmed_clear_txid"), listOf(single.tag, chain.tag).distinct())
        val damaged = clearConfirm(DamagedGuard)
        assertEquals("บันทึกการส่งครั้งก่อนเสียหาย ไม่ทราบ txid" to "unconfirmed_clear_damaged", damaged.named to damaged.tag)
        assertEquals(single.warning, damaged.warning)
    }

    // On a chain that holds a cancel the guard's bytes are the cancel, so the retry and the lines after it name the cancel, and none reads
    // as sending the original payment again. A chain without a cancel keeps the retry's words.
    @Test
    fun theRetryOfAChainThatHoldsACancelSaysItPostsTheCancel() {
        val cancel = retryCopy(UnconfirmedSend(txid('b'), SENT_AT, HEX, listOf(txid('a')), holdsCancel = true))
        val lines = listOf(cancel.label, cancel.running, cancel.unknown, cancel.refused, cancel.failed, Replacement.Cancel.copy.unknown)
        assertTrue(lines.none { "รายการเดิมอีกครั้ง" in it }, "no line offers the original payment again: $lines")
        assertEquals("ส่งรายการยกเลิกอีกครั้ง", cancel.label)
        assertEquals("กำลังส่งรายการยกเลิก…", cancel.running)
        assertEquals("ยังไม่ทราบผลของรายการยกเลิก ส่งรายการยกเลิกอีกครั้งภายหลังได้", cancel.unknown)
        assertEquals("เครือข่ายไม่รับรายการยกเลิก รายการเดิมอาจยืนยันไปแล้ว ตรวจประวัติในหน้าแรกหรือใน explorer", cancel.refused)
        assertEquals("ส่งรายการยกเลิกไม่สำเร็จ ลองใหม่ภายหลัง", cancel.failed)
        assertEquals("ยังไม่ทราบผลของรายการยกเลิก ส่งรายการยกเลิกอีกครั้งภายหลังได้", Replacement.Cancel.copy.unknown)
        for (pending in listOf(UnconfirmedSend(txid('a'), SENT_AT, HEX), UnconfirmedSend(txid('b'), SENT_AT, HEX, listOf(txid('a'))))) {
            val retry = retryCopy(pending)
            assertEquals("ส่งรายการเดิมอีกครั้ง", retry.label)
            assertEquals("กำลังส่งรายการเดิม…", retry.running)
            assertEquals("ยังไม่ทราบผลเหมือนเดิม ส่งรายการเดิมอีกครั้งภายหลังได้", retry.unknown)
            assertEquals("เครือข่ายไม่รับรายการเดิม อาจยืนยันไปแล้วหรือเหรียญถูกใช้ไปแล้ว ตรวจประวัติในหน้าแรกหรือใน explorer ก่อนล้างสถานะ", retry.refused)
            assertEquals("ส่งรายการเดิมไม่สำเร็จ ลองใหม่ภายหลัง", retry.failed)
        }
    }
}

private const val SENT_AT = 1_792_740_720L

/** Stands in for the signed bytes, which these words never read. */
private const val HEX = "00"

private fun txid(digit: Char): String = digit.toString().repeat(64)
