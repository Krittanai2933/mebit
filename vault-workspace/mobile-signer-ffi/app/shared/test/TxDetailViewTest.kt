package com.mebit.app.feature.send

import com.mebit.app.data.FiatQuote
import com.mebit.app.data.TxDetailLoad
import com.mebit.ffi.signer.HotTx
import com.mebit.ffi.signer.HotTxDetail
import com.mebit.ffi.signer.HotTxo
import com.mebit.ffi.signer.KeyNetwork
import com.mebit.ffi.signer.TxoOwner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val BANGKOK = "Asia/Bangkok"

// 18 Aug 2026 14:02 in Bangkok, the first row on board 4.1.
private const val BOARD_TIME = 1_787_036_520uL
private const val TXID = "9f2c4b7a0d1e3f5a6b8c9d0e1f2a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c"
private const val DESTINATION = "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"
private const val SPENT_RECEIVE = "tb1q9h7a2kxv7tq3mebitreceive0000000004e8s"
private const val SPENT_CHANGE = "tb1qk3d8mebitchange000000000000000000007q2w"
private const val CHANGE = "tb1qm4kzmebitchange000000000000000000001x9c"
private const val PAYER = "tb1qpayer000000000000000000000000000000p4y"
private const val PAYER_CHANGE = "tb1qpayerchange0000000000000000000000000c8g"
private const val OWN_RECEIVE = "tb1qownreceive00000000000000000000000000r7v"

// The proposal's consistent sample: 0.025 + 0.010 in, 0.018 to the destination, 0.0169876 back, a 1,240 sat fee over 294 vB, ฿3,438,000 per BTC.
private val QUOTE = FiatQuote(3_438_000.0, 0L)
private val SENT_INPUTS = listOf(HotTxo(SPENT_RECEIVE, 2_500_000uL, TxoOwner.RECEIVE), HotTxo(SPENT_CHANGE, 1_000_000uL, TxoOwner.CHANGE))
private val SENT_OUTPUTS = listOf(HotTxo(DESTINATION, 1_800_000uL, TxoOwner.OTHER), HotTxo(CHANGE, 1_698_760uL, TxoOwner.CHANGE))

private fun row(netSats: Long, confirmations: UInt, blockTime: ULong? = BOARD_TIME.takeIf { confirmations > 0u }) =
    HotTx(TXID, netSats, confirmations, blockTime)

private fun sent(
    confirmations: UInt = 3u,
    netSats: Long = -1_801_240L,
    inputs: List<HotTxo> = SENT_INPUTS,
    outputs: List<HotTxo> = SENT_OUTPUTS,
    feeSats: ULong = 1_240uL,
    vsize: ULong = 294uL,
) =
    HotTxDetail(
        tx = row(netSats, confirmations),
        blockHeight = 903_412uL.takeIf { confirmations > 0u },
        inputs = inputs,
        outputs = outputs,
        feeSats = feeSats,
        sizeBytes = 374uL,
        vsize = vsize,
        signalsRbf = true,
    )

// Someone pays 0.02 out of 0.026 and keeps 0.00599 as their own change.
private val RECEIVED = HotTxDetail(
    tx = row(2_000_000L, 1u),
    blockHeight = 903_412uL,
    inputs = listOf(HotTxo(PAYER, 2_600_000uL, TxoOwner.OTHER)),
    outputs = listOf(HotTxo(OWN_RECEIVE, 2_000_000uL, TxoOwner.RECEIVE), HotTxo(PAYER_CHANGE, 599_000uL, TxoOwner.OTHER)),
    feeSats = 1_000uL,
    sizeBytes = 222uL,
    vsize = 141uL,
    signalsRbf = false,
)

private val SENT_ROW = row(-1_801_240L, 3u)
private val NOT_LOADED = listOf(TxDetailLoad.Loading, TxDetailLoad.Failed("esplora get: x"), TxDetailLoad.NotFound, TxDetailLoad.RowOnly)

private fun view(row: HotTx, load: TxDetailLoad, quote: FiatQuote? = QUOTE, network: KeyNetwork = KeyNetwork.TEST) =
    txDetailView(row, load, quote, network, BANGKOK)

private fun loadedSend(detail: HotTxDetail = sent()) = view(SENT_ROW, TxDetailLoad.Loaded(detail))

class TxDetailViewTest {
    @Test
    fun aLoadedSendShowsWhatReachedTheDestinationAndItsValueNow() {
        val shown = loadedSend()
        assertFalse(shown.received)
        assertEquals("ส่งออก", shown.title)
        assertEquals("0.0180 BTC", shown.amount)
        assertNull(shown.amountNote)
        assertEquals("มูลค่าตอนนี้ ≈ ฿61,884", shown.fiat)
        assertEquals(TxAddresses("ส่งไปที่", listOf(DESTINATION)), shown.addresses)
    }

    @Test
    fun aSendNotLoadedShowsTheNetWithTheFeeNoteAndNoLoadedCard() {
        for (load in NOT_LOADED) {
            val shown = view(SENT_ROW, load)
            assertEquals("0.0180124 BTC", shown.amount, "$load")
            assertEquals("รวมค่าธรรมเนียม", shown.amountNote, "$load")
            assertEquals("มูลค่าตอนนี้ ≈ ฿61,927", shown.fiat, "$load")
            assertNull(shown.addresses, "$load")
            assertNull(shown.cost, "$load")
            assertNull(shown.technical, "$load")
            assertEquals(TXID, shown.txid, "$load")
        }
    }

    @Test
    fun theCostCardShowsTheFeeTheRateTheTotalAndTheChange() {
        assertEquals(TxCostView("1,240 sat · ฿43", "4.2 sat/vB", "0.0180124 BTC", "0.0169876 BTC"), loadedSend().cost)
        assertEquals("1,240 sat", view(SENT_ROW, TxDetailLoad.Loaded(sent()), quote = null).cost?.fee, "no price, no baht")
    }

    @Test
    fun aSelfSendReachedNobodySoItKeepsTheNetWithTheNote() {
        val toSelf = sent(netSats = -1_240L, outputs = listOf(HotTxo(CHANGE, 3_498_760uL, TxoOwner.CHANGE)))
        val shown = view(row(-1_240L, 3u), TxDetailLoad.Loaded(toSelf))
        assertEquals("0.0000124 BTC", shown.amount)
        assertEquals("รวมค่าธรรมเนียม", shown.amountNote)
        assertNull(shown.addresses)
        assertEquals(TxCostView("1,240 sat · ฿43", "4.2 sat/vB", "0.0000124 BTC", "0.0349876 BTC"), shown.cost)
    }

    // A payjoin seen from the payer, with 0.025 of the wallet's and 0.02 of the receiver's own in, 0.04 to the receiver and 0.0049 back
    // as change. What the receiver got from the wallet and who paid the 10,000 sat fee cannot be told apart.
    @Test
    fun aSendWithAnotherPartysInputShowsOnlyWhatLeftTheWalletAndTheChange() {
        val payjoin = sent(
            netSats = -2_010_000L,
            inputs = listOf(HotTxo(SPENT_RECEIVE, 2_500_000uL, TxoOwner.RECEIVE), HotTxo(PAYER, 2_000_000uL, TxoOwner.OTHER)),
            outputs = listOf(HotTxo(DESTINATION, 4_000_000uL, TxoOwner.OTHER), HotTxo(CHANGE, 490_000uL, TxoOwner.CHANGE)),
            feeSats = 10_000uL,
            vsize = 209uL,
        )
        val shown = view(row(-2_010_000L, 3u), TxDetailLoad.Loaded(payjoin))
        assertEquals("0.0201 BTC", shown.amount)
        assertEquals("รวมค่าธรรมเนียม", shown.amountNote)
        assertEquals("มูลค่าตอนนี้ ≈ ฿69,104", shown.fiat)
        assertNull(shown.addresses)
        assertEquals(TxCostView(null, null, "0.0201 BTC", "0.0049 BTC"), shown.cost)
        val technical = shown.technical!!
        assertEquals(listOf("ของกระเป๋านี้", null), technical.inputs.map { it.tag })
        assertEquals(listOf(null, "เงินทอน · ของกระเป๋านี้"), technical.outputs.map { it.tag })
        assertEquals("10,000 sat", technical.fee, "the transaction's own fee stays a fact about it")
        assertEquals("47.8 sat/vB", technical.rate)
    }

    // A coinjoin with 0.001 in from the wallet and from two others, and 0.00099 out to each, the wallet's to its own receive address.
    @Test
    fun aCoinjoinNamesNoDestinationAndClaimsNoShareOfTheFee() {
        val coinjoin = sent(
            netSats = -1_000L,
            inputs = listOf(HotTxo(SPENT_RECEIVE, 100_000uL, TxoOwner.RECEIVE), HotTxo(PAYER, 100_000uL, TxoOwner.OTHER), HotTxo(PAYER_CHANGE, 100_000uL, TxoOwner.OTHER)),
            outputs = listOf(HotTxo(OWN_RECEIVE, 99_000uL, TxoOwner.RECEIVE), HotTxo(DESTINATION, 99_000uL, TxoOwner.OTHER), HotTxo(PAYER, 99_000uL, TxoOwner.OTHER)),
            feeSats = 3_000uL,
            vsize = 141uL,
        )
        val shown = view(row(-1_000L, 3u), TxDetailLoad.Loaded(coinjoin))
        assertEquals("0.00001 BTC", shown.amount)
        assertEquals("รวมค่าธรรมเนียม", shown.amountNote)
        assertNull(shown.addresses)
        assertEquals(TxCostView(null, null, "0.00001 BTC", null), shown.cost)
        assertEquals(listOf("ของกระเป๋านี้", null, null), shown.technical?.outputs?.map { it.tag })
    }

    // 0.025 spent, with 0.01 to someone, 0.006 to the wallet's own receive address, 0.0089 to its change address and a 10,000 sat fee.
    @Test
    fun theChangeIsWhatWentToTheChangeAddressesNotAPaymentToTheWalletsOwnReceiveAddress() {
        val toOwnReceiveToo = sent(
            netSats = -1_010_000L,
            inputs = listOf(HotTxo(SPENT_RECEIVE, 2_500_000uL, TxoOwner.RECEIVE)),
            outputs = listOf(HotTxo(DESTINATION, 1_000_000uL, TxoOwner.OTHER), HotTxo(OWN_RECEIVE, 600_000uL, TxoOwner.RECEIVE), HotTxo(CHANGE, 890_000uL, TxoOwner.CHANGE)),
            feeSats = 10_000uL,
        )
        val shown = view(row(-1_010_000L, 3u), TxDetailLoad.Loaded(toOwnReceiveToo))
        assertEquals("0.0100 BTC", shown.amount)
        assertEquals(TxCostView("10,000 sat · ฿344", "34.0 sat/vB", "0.0101 BTC", "0.0089 BTC"), shown.cost)
    }

    // The core refuses any value or sum above 21 million BTC, so all of it as the fee is the most the screen can be given.
    @Test
    fun theLargestFeeTheCoreAllowsKeepsItsRateAndAPositiveAmount() {
        fun shown(vsize: ULong) = view(
            row(-2_100_000_000_000_000L, 3u),
            TxDetailLoad.Loaded(
                sent(
                    netSats = -2_100_000_000_000_000L,
                    inputs = listOf(HotTxo(SPENT_RECEIVE, 2_100_000_000_000_000uL, TxoOwner.RECEIVE)),
                    outputs = listOf(HotTxo(null, 0uL, TxoOwner.OTHER)),
                    feeSats = 2_100_000_000_000_000uL,
                    vsize = vsize,
                ),
            ),
            quote = null,
        )
        assertEquals("2,100,000,000,000,000 sat", shown(1uL).technical?.fee)
        assertEquals("2,100,000,000,000,000 sat", shown(1uL).cost?.fee)
        assertEquals("2,100,000,000,000,000.0 sat/vB", shown(1uL).technical?.rate)
        assertEquals("14,893,617,021,276.5 sat/vB", shown(141uL).technical?.rate)
    }

    @Test
    fun aReceiveHasNoCostCardAndListsTheWalletsOwnAddresses() {
        val shown = view(row(2_000_000L, 1u), TxDetailLoad.Loaded(RECEIVED))
        assertTrue(shown.received)
        assertEquals("รับ", shown.title)
        assertEquals("0.0200 BTC", shown.amount)
        assertNull(shown.amountNote)
        assertNull(shown.cost)
        assertEquals(TxAddresses("เข้าที่อยู่ของคุณ", listOf(OWN_RECEIVE)), shown.addresses)
    }

    @Test
    fun theStatusCountsBlocksUpToSixAndThenStopsCounting() {
        fun status(confirmations: UInt, netSats: Long = -1_801_240L) = view(row(netSats, confirmations), TxDetailLoad.RowOnly).status
        val usual = "ปกติถือว่าแน่นอนเมื่อครบ 6 บล็อก"
        assertEquals(TxStatusView(false, "ยังไม่ยืนยัน", "รอยืนยันในบล็อก", null, "ไม่ต้องทำอะไร และอย่าส่งซ้ำ"), status(0u))
        assertEquals(TxStatusView(false, "ยังไม่ยืนยัน", "รอยืนยันในบล็อก", null, null), status(0u, netSats = 2_000_000L), "nothing to resend on a receive")
        assertEquals(TxStatusView(true, "ยืนยันแล้ว", "ยืนยัน 1 บล็อก", 1, usual), status(1u))
        assertEquals(TxStatusView(true, "ยืนยันแล้ว", "ยืนยัน 5 บล็อก", 5, usual), status(5u))
        assertEquals(TxStatusView(true, "ยืนยันแล้ว", "ยืนยัน 6 บล็อก", 6, null), status(6u))
        assertEquals(TxStatusView(true, "ยืนยันแล้ว", "ยืนยัน 7 บล็อก", null, null), status(7u), "past six the badge stays confirmed with no segments")
        assertEquals("ยืนยัน 1,234 บล็อก", status(1_234u).line)
    }

    @Test
    fun aLoadedStatusNamesItsBlockAndThePendingRateAndTakesTheFreshCount() {
        val fresh = view(row(-1_801_240L, 0u), TxDetailLoad.Loaded(sent(confirmations = 3u)))
        assertEquals(TxStatusView(true, "ยืนยันแล้ว", "ยืนยัน 3 บล็อก · บล็อกที่ 903,412", 3, "ปกติถือว่าแน่นอนเมื่อครบ 6 บล็อก"), fresh.status)
        assertEquals("18 ส.ค. 2569 · 14:02 น.", fresh.date)
        val pending = view(row(-1_801_240L, 0u), TxDetailLoad.Loaded(sent(confirmations = 0u)))
        assertEquals("รอยืนยันในบล็อก · จ่าย 4.2 sat/vB", pending.status.line)
    }

    @Test
    fun theDateIsTheBlockTimeAndNoneWhileUnconfirmed() {
        assertEquals("18 ส.ค. 2569 · 14:02 น.", view(SENT_ROW, TxDetailLoad.RowOnly).date)
        assertNull(view(row(-1_801_240L, 0u), TxDetailLoad.RowOnly).date)
        assertEquals("—", view(row(-1_801_240L, 3u, blockTime = null), TxDetailLoad.RowOnly).date, "confirmed with no time the app can print")
    }

    @Test
    fun tagsFollowWhoOwnsEachPartAndWhichWayTheMoneyWent() {
        val send = loadedSend().technical!!
        assertEquals(listOf("ของกระเป๋านี้", "ของกระเป๋านี้"), send.inputs.map { it.tag })
        assertEquals(listOf("ปลายทาง", "เงินทอน · ของกระเป๋านี้"), send.outputs.map { it.tag })
        val receive = view(row(2_000_000L, 1u), TxDetailLoad.Loaded(RECEIVED)).technical!!
        assertEquals(listOf<String?>(null), receive.inputs.map { it.tag })
        assertEquals(listOf("ของกระเป๋านี้", null), receive.outputs.map { it.tag })
    }

    @Test
    fun theTechnicalPartsAreWordedWithShortAddresses() {
        assertEquals(
            TxTechnicalView(
                inputs = listOf(TxoLine("tb1q … 4e8s", "0.0250 BTC", "ของกระเป๋านี้"), TxoLine("tb1q … 7q2w", "0.0100 BTC", "ของกระเป๋านี้")),
                outputs = listOf(TxoLine("tb1q … jzsx", "0.0180 BTC", "ปลายทาง"), TxoLine("tb1q … 1x9c", "0.0169876 BTC", "เงินทอน · ของกระเป๋านี้")),
                fee = "1,240 sat",
                rate = "4.2 sat/vB",
                size = "374 B",
                vsize = "294 vB",
                rbf = "ส่งสัญญาณ",
                block = "บล็อกที่ 903,412",
            ),
            loadedSend().technical,
        )
        val pending = view(row(2_000_000L, 0u), TxDetailLoad.Loaded(RECEIVED.copy(tx = row(2_000_000L, 0u), blockHeight = null, outputs = RECEIVED.outputs + HotTxo(null, 0uL, TxoOwner.OTHER))))
        assertEquals("ยังไม่อยู่ในบล็อก", pending.technical?.block)
        assertEquals("ไม่ส่งสัญญาณ", pending.technical?.rbf)
        assertEquals(TxoLine("ไม่มีที่อยู่", "0.0000 BTC", null), pending.technical?.outputs?.last())
    }

    @Test
    fun theRateIsCutToOneDecimalAndNeverRoundedUp() {
        fun rate(feeSats: ULong, vsize: ULong) = loadedSend(sent(feeSats = feeSats, vsize = vsize)).cost?.rate
        assertEquals("4.2 sat/vB", rate(1_259uL, 294uL))
        assertEquals("4.9 sat/vB", rate(1_469uL, 294uL))
        assertEquals("1.0 sat/vB", rate(141uL, 141uL))
        assertEquals("2,000.0 sat/vB", rate(282_000uL, 141uL))
    }

    @Test
    fun theTestnetPillFollowsTheNetworkAsked() {
        assertTrue(view(SENT_ROW, TxDetailLoad.RowOnly, network = KeyNetwork.TEST).testnet)
        assertFalse(view(SENT_ROW, TxDetailLoad.RowOnly, network = KeyNetwork.MAIN).testnet)
    }

    @Test
    fun eachStateThatIsNotLoadedSaysWhy() {
        assertEquals(TxNote("กำลังโหลดรายละเอียด…", "tx_loading", retry = false), view(SENT_ROW, TxDetailLoad.Loading).note)
        assertEquals(TxNote("ไม่พบธุรกรรมนี้ใน explorer ตอนนี้", "tx_not_found", retry = true), view(SENT_ROW, TxDetailLoad.NotFound).note)
        val rust = "invalid PSBT json: esplora get: HTTP status 429, gave up after 3 attempts"
        val failed = view(SENT_ROW, TxDetailLoad.Failed(rust))
        assertEquals(TxNote("explorer จำกัดจำนวนการเรียกชั่วคราว รอสักครู่แล้วลองใหม่", "tx_failed", retry = true), failed.note)
        assertEquals(rust, failed.debugText)
        for (quiet in listOf(view(SENT_ROW, TxDetailLoad.RowOnly), loadedSend())) {
            assertNull(quiet.note)
            assertNull(quiet.debugText)
        }
    }

    @Test
    fun noPriceHidesTheValueLine() {
        assertNull(view(SENT_ROW, TxDetailLoad.RowOnly, quote = null).fiat)
    }
}
