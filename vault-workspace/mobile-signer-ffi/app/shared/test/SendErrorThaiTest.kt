package com.mebit.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Every Rust refusal of hot_prepare_send_at_rate, hot_broadcast, hot_wallet_snapshot and hot_wallet_history has a Thai line, and none shows its English text. */
class SendErrorThaiTest {
    private val rows = listOf(
        "amount 100 is below the 546 sat dust limit" to "จำนวนน้อยกว่าขั้นต่ำที่เครือข่ายรับ (546 sat)",
        "fee 2115 is more than the amount 1000" to "ค่าธรรมเนียมสูงกว่าจำนวนที่ส่ง เลือกระดับที่ต่ำลง",
        "insufficient funds: have 0 confirmed, need 1100" to "ยอดที่ยืนยันแล้วไม่พอ รอให้เงินที่เพิ่งได้รับยืนยันก่อน",
        "change below dust, send the whole balance or change the amount" to "เงินทอนน้อยเกินไป ส่งทั้งหมดหรือเปลี่ยนจำนวน",
        "the whole balance 655 less the fee 110 is below the 546 sat dust limit" to "ยอดทั้งหมดหลังหักค่าธรรมเนียมน้อยกว่าขั้นต่ำที่เครือข่ายรับ (546 sat)",
        "fee rate 0 is below 1 sat/vB" to "ค่าธรรมเนียมต่ำเกินไปสำหรับธุรกรรมนี้ เลือกระดับที่สูงขึ้น",
        "fee rate 2001 is above 2000 sat/vB" to "ค่าธรรมเนียมสูงผิดปกติ ตรวจจำนวนอีกครั้ง",
        "fee did not settle in 4 passes, send a smaller amount" to "ส่งไม่สำเร็จ ลองใหม่ภายหลัง",
        "esplora scan: more than 200 addresses on one chain, the history is abnormal" to "ประวัติของกระเป๋านี้ผิดปกติ ตรวจกับ explorer ก่อนส่ง",
        "esplora utxo: 1111111111111111111111111111111111111111111111111111111111111111:0 is listed twice" to "ข้อมูลจาก explorer ไม่ตรงกัน ลองใหม่ภายหลัง",
        "esplora utxo: the coins' values do not fit u64" to "ข้อมูลจาก explorer ไม่ตรงกัน ลองใหม่ภายหลัง",
        "esplora balance: 18446744073709551616 sats does not fit u64" to "ข้อมูลจาก explorer ไม่ตรงกัน ลองใหม่ภายหลัง",
        "amount plus fee does not fit u64" to "จำนวนผิดปกติ",
        "broadcast: not a transaction: odd length" to "ส่งไม่สำเร็จ",
        "broadcast: txid aa does not match the transaction bb" to "ส่งไม่สำเร็จ",
        "esplora post: https://mempool.space/testnet/api/tx: status code 400: bad-txns-inputs-missingorspent" to "ส่งไม่สำเร็จ",
        "esplora get: https://mempool.space/testnet/api/address/tb1q/utxo: Network Error: connection refused" to "ติดต่อ explorer ไม่ได้ ลองใหม่ภายหลัง",
        "esplora get: lookup thread panicked" to "ติดต่อ explorer ไม่ได้ ลองใหม่ภายหลัง",
        "esplora get: HTTP status 429, gave up after 3 attempts" to "explorer จำกัดจำนวนการเรียกชั่วคราว รอสักครู่แล้วลองใหม่",
        "esplora get: no testnet4 host is reachable (https://blockstream.info/testnet4/api: esplora get: status code 429; https://mempool.space/testnet4/api: Connection Failed)" to "explorer จำกัดจำนวนการเรียกชั่วคราว รอสักครู่แล้วลองใหม่",
        // Seen on an iPhone 15 over 5G on 3 Nov 2026, as the bindings print it.
        "esplora get: https://mempool.space/testnet4/api/address/tb1q5squ69zn40x44ewqgl6dsaqgwjj38pc7f7j9hp: Connection Failed: tls connection init failed: Resource temporarily unavailable (os error 35)" to "ติดต่อ explorer ไม่ได้ ลองใหม่ภายหลัง",
        "missing witness slot" to "ส่งไม่สำเร็จ ลองใหม่ภายหลัง",
        "" to "ส่งไม่สำเร็จ ลองใหม่ภายหลัง",
    )

    // The bindings print every refusal with this prefix, so a mapping that only knows the bare text never matches a real error.
    private val shapes = listOf("", "invalid PSBT json: ")

    @Test
    fun everyRustRefusalHasItsThaiLineInTheShapeTheBindingsPrint() {
        for ((rust, thai) in rows) for (prefix in shapes) assertEquals(thai, sendErrorThai(prefix + rust), prefix + rust)
        assertEquals("ส่งไม่สำเร็จ ลองใหม่ภายหลัง", sendErrorThai(null), "a null message")
    }

    @Test
    fun aFailedReadNeverSaysASendFailed() {
        assertEquals("ดึงข้อมูลกระเป๋าไม่สำเร็จ ลองใหม่ภายหลัง", readErrorThai("missing witness slot"))
        assertEquals("ดึงข้อมูลกระเป๋าไม่สำเร็จ ลองใหม่ภายหลัง", readErrorThai(null))
        assertEquals("ติดต่อ explorer ไม่ได้ ลองใหม่ภายหลัง", readErrorThai("invalid PSBT json: esplora get: lookup thread panicked"))
        assertEquals("explorer จำกัดจำนวนการเรียกชั่วคราว รอสักครู่แล้วลองใหม่", readErrorThai("invalid PSBT json: esplora get: HTTP status 429, gave up after 3 attempts"))
    }

    @Test
    fun noEnglishWordOfTheRustTextReachesTheScreen() {
        for ((rust, _) in rows) for (prefix in shapes) {
            val shown = sendErrorThai(prefix + rust)
            for (word in (prefix + rust).split(' ').filter { it.length > 3 && it.all { c -> c.isLetter() } }) {
                assertFalse(word in shown, "'$word' of '${prefix + rust}' in '$shown'")
            }
        }
    }
}
