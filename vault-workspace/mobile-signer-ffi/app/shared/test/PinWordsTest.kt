package com.mebit.app.feature.onboarding

import com.mebit.app.data.PinProblem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class PinWordsTest {
    @Test
    fun aWaitUnderAMinuteCountsSecondsRoundedUp() {
        assertEquals("ลองใหม่ได้ในอีก 10 วินาที", pinWaitText(10.seconds))
        assertEquals("ลองใหม่ได้ในอีก 30 วินาที", pinWaitText(29.2.seconds))
        assertEquals("ลองใหม่ได้ในอีก 1 วินาที", pinWaitText(1.milliseconds))
    }

    @Test
    fun aWaitOfAMinuteOrMoreCountsWholeMinutesRoundedUp() {
        assertEquals("ลองใหม่ได้ในอีก 1 นาที", pinWaitText(59.5.seconds), "rounds up to 60 s, which reads as a minute")
        assertEquals("ลองใหม่ได้ในอีก 1 นาที", pinWaitText(1.minutes))
        assertEquals("ลองใหม่ได้ในอีก 2 นาที", pinWaitText(90.seconds))
        assertEquals("ลองใหม่ได้ในอีก 30 นาที", pinWaitText(30.minutes))
    }

    @Test
    fun aWrongPinSaysSo() {
        assertEquals("PIN ไม่ถูกต้อง", pinProblemText(PinProblem.Wrong))
    }
}
