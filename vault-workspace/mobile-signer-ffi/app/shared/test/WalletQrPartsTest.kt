package com.mebit.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WalletQrPartsTest {
    @Test
    fun theCounterNamesThePartAndHidesForOne() {
        assertEquals("ส่วน 2 / 4", partCounterText(1, 4))
        assertEquals("ส่วน 4 / 4", partCounterText(3, 4))
        assertNull(partCounterText(0, 1))
        assertNull(partCounterText(0, 0))
    }

    @Test
    fun oneBarIsOrangeAndTheRestGrey() {
        assertEquals(listOf(PartBar.Shown, PartBar.Current, PartBar.Ahead, PartBar.Ahead), partBars(1, 4))
        assertEquals(List(3) { PartBar.Ahead }, partBars(-1, 3))
        assertEquals(emptyList(), partBars(0, 0))
    }

    @Test
    fun thePartChangesEveryQuarterSecondAndWraps() {
        assertEquals(listOf(0, 0, 1, 1, 2, 3, 0, 1), listOf(0L, 249, 250, 499, 500, 750, 1000, 1250).map { partIndexAt(it, 4) })
        assertEquals(0, partIndexAt(10_000, 1))
        assertEquals(0, partIndexAt(10_000, 0))
    }

    @Test
    fun theScanLineCountsTheFragments() {
        assertEquals("ได้แล้ว 2 / 4 ส่วน · ถือค้างไว้", scanCounterText(2, 4))
    }

    @Test
    fun thePictureHoldsTheTabsWholeTextNameAndWarning() {
        val picture = exportPicture("Wallet 2", "BSMS 1.0\nwsh(...)")
        assertEquals("BSMS 1.0\nwsh(...)", picture?.qrText)
        assertEquals("Wallet 2", picture?.name)
        assertEquals(EXPORT_PICTURE_WARNING, picture?.warning)
        assertNull(exportPicture("Wallet 2", null))
    }
}

class ImportEyebrowTest {
    @Test
    fun aScannedWalletHasNoFileName() {
        assertEquals("IMPORT · QR", importEyebrow(""))
        assertEquals("IMPORT · ไฟล์ a.bsms", importEyebrow("a.bsms"))
    }
}
