package com.mebit.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Every Rust refusal of hot_prepare_send_at_rate, hot_broadcast, hot_wallet_snapshot and hot_wallet_history has a Thai line, and none shows its English text. */
class SendErrorThaiTest {
    private val rows = listOf(
        // The floor is the destination script's, read from the Rust text.
        "amount 293 is below the network's 294 sat dust floor" to "เครือข่ายไม่รับยอดส่งที่ต่ำกว่า 294 sat สำหรับที่อยู่ปลายทางนี้",
        "amount 545 is below the network's 546 sat dust floor" to "เครือข่ายไม่รับยอดส่งที่ต่ำกว่า 546 sat สำหรับที่อยู่ปลายทางนี้",
        "insufficient funds: have 0 confirmed, need 1100" to "ยอดที่ยืนยันแล้วไม่พอ รอให้เงินที่เพิ่งได้รับยืนยันก่อน",
        "change 293 is below the network's 294 sat dust floor, send the whole balance or change the amount" to "เงินทอนต่ำกว่า 294 sat ซึ่งเครือข่ายไม่รับ ส่งทั้งหมดหรือเปลี่ยนจำนวน",
        "the whole balance 451 less the fee 122 is below the network's 330 sat dust floor" to "ยอดทั้งหมดหลังหักค่าธรรมเนียมต่ำกว่า 330 sat ซึ่งเครือข่ายไม่รับสำหรับที่อยู่ปลายทางนี้",
        "the whole balance 650 less the fee 111 is below the network's 540 sat dust floor" to "ยอดทั้งหมดหลังหักค่าธรรมเนียมต่ำกว่า 540 sat ซึ่งเครือข่ายไม่รับสำหรับที่อยู่ปลายทางนี้",
        "fee rate 0 is below 1 sat/vB" to "ค่าธรรมเนียมต่ำเกินไปสำหรับธุรกรรมนี้ เลือกระดับที่สูงขึ้น",
        "fee rate 2001 is above 2000 sat/vB" to "ค่าธรรมเนียมสูงผิดปกติ ตรวจจำนวนอีกครั้ง",
        "fee did not settle in 4 passes, send a smaller amount" to "ส่งไม่สำเร็จ ลองใหม่ภายหลัง",
        // A host lied in a proof, and no other host was left.
        "esplora scan: receive index 19: tb1qexample: transaction 1111111111111111111111111111111111111111111111111111111111111111 does not pay it" to "ประวัติของกระเป๋านี้ผิดปกติ ตรวจกับ explorer ก่อนส่ง",
        // A payment or a history while the scan goes on, after a host's request limit or a lost read.
        "esplora get: receive index 190 could not be asked: esplora get: https://blockstream.info/testnet4/api: HTTP status 429, limited for 60 s more" to "explorer จำกัดจำนวนการเรียกชั่วคราว รอสักครู่แล้วลองใหม่",
        "esplora get: receive index 3 could not be proven: esplora get: https://blockstream.info/testnet4/api/tx/aa/hex: Network Error: connection reset by peer, gave up after 3 attempts" to "ติดต่อ explorer ไม่ได้ ลองใหม่ภายหลัง",
        "esplora utxo: 1111111111111111111111111111111111111111111111111111111111111111:0 is listed twice" to "ข้อมูลจาก explorer ไม่ตรงกัน ลองใหม่ภายหลัง",
        "esplora utxo: the coins' values do not fit u64" to "ข้อมูลจาก explorer ไม่ตรงกัน ลองใหม่ภายหลัง",
        "esplora balance: 18446744073709551616 sats does not fit u64" to "ข้อมูลจาก explorer ไม่ตรงกัน ลองใหม่ภายหลัง",
        "amount plus fee does not fit u64" to "จำนวนผิดปกติ",
        "broadcast: not a transaction: odd length" to "ส่งไม่สำเร็จ",
        "broadcast: txid aa does not match the transaction bb" to "ส่งไม่สำเร็จ",
        "esplora post: https://mempool.space/testnet/api/tx: status code 400: bad-txns-inputs-missingorspent" to "ส่งไม่สำเร็จ",
        "esplora get: https://mempool.space/testnet/api/address/tb1q/utxo: Network Error: connection refused" to "ติดต่อ explorer ไม่ได้ ลองใหม่ภายหลัง",
        "esplora get: lookup thread panicked" to "ติดต่อ explorer ไม่ได้ ลองใหม่ภายหลัง",
        "esplora get: https://blockstream.info/testnet4/api: HTTP status 429, limited for 60 s more" to "explorer จำกัดจำนวนการเรียกชั่วคราว รอสักครู่แล้วลองใหม่",
        // Every host under its request limit, and one limited beside one that is down (chain.rs limited_text).
        "esplora get: no testnet4 host is reachable (https://blockstream.info/testnet4/api: HTTP status 429, limited for 42 s more; https://mempool.space/testnet4/api: HTTP status 429, limited for 42 s more)" to "explorer จำกัดจำนวนการเรียกชั่วคราว รอสักครู่แล้วลองใหม่",
        "esplora get: no testnet4 host is reachable (https://blockstream.info/testnet4/api: HTTP status 429, limited for 42 s more; https://mempool.space/testnet4/api: Connection Failed)" to "explorer จำกัดจำนวนการเรียกชั่วคราว รอสักครู่แล้วลองใหม่",
        // Seen on an iPhone 15 over 5G on 3 Nov 2026, as the bindings print it.
        "esplora get: https://mempool.space/testnet4/api/address/tb1q5squ69zn40x44ewqgl6dsaqgwjj38pc7f7j9hp: Connection Failed: tls connection init failed: Resource temporarily unavailable (os error 35)" to "ติดต่อ explorer ไม่ได้ ลองใหม่ภายหลัง",
        // hot_prepare_fee_bump and hot_prepare_cancel.
        "replace: coins spent: 1111111111111111111111111111111111111111111111111111111111111111:0 is not a coin this wallet can spend" to "เหรียญของรายการเดิมถูกใช้ไปแล้ว รายการเดิมอาจออกไปแล้ว ตรวจประวัติในหน้าแรกก่อน",
        "replace: not this wallet's payment: its second output is not this wallet's change" to "รายการเดิมไม่ใช่รายการที่กระเป๋านี้แทนที่ได้",
        "replace: not this wallet's payment: not one transaction" to "รายการเดิมไม่ใช่รายการที่กระเป๋านี้แทนที่ได้",
        "replace: rate 2001 is above 2000 sat/vB" to "ค่าธรรมเนียมที่ต้องใช้สูงเกินเพดาน 2,000 sat/vB",
        "replace: what is left, 843 less the fee 550, is below the network's 294 sat dust floor" to "ยอดที่เหลือหลังหักค่าธรรมเนียมใหม่ต่ำกว่า 294 sat ซึ่งเครือข่ายไม่รับ",
        "replace: check failed: its rate is above the earlier rate" to "ส่งไม่สำเร็จ ลองใหม่ภายหลัง",
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
        assertEquals("explorer จำกัดจำนวนการเรียกชั่วคราว รอสักครู่แล้วลองใหม่", readErrorThai("invalid PSBT json: esplora get: https://blockstream.info/testnet4/api: HTTP status 429, limited for 60 s more"))
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
