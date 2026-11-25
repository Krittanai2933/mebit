package com.mebit.app.data

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mebit.app.PlainFile
import kotlin.math.abs

/** The unit every bitcoin amount is shown in, Penpot 7.1. Payment links stay BTC, because the BIP21 `amount=` is. */
enum class BtcUnit(val label: String) { Sat("sat"), Btc("BTC") }

/** "18010" to "18,010". It works on the digits as written, so a leading zero keeps its place. */
internal fun groupDigits(digits: String): String = digits.reversed().chunked(3).joinToString(",").reversed()

/** [sats] as the number alone: whole sats grouped with commas, or what [btc] makes of them. Each BTC call site passes the formatter it used before the setting existed. */
fun amountNumber(sats: Long, unit: BtcUnit, btc: (Long) -> String = ::formatBtcCompact): String =
    if (unit == BtcUnit.Sat) (if (sats < 0) "-" else "") + groupDigits(abs(sats).toString()) else btc(sats)

/** [amountNumber] and the unit's label, such as `0.0050 BTC` or `500,000 sat`. */
fun amountText(sats: Long, unit: BtcUnit, btc: (Long) -> String = ::formatBtcCompact): String =
    "${amountNumber(sats, unit, btc)} ${unit.label}"

/** For a row or card that draws no unit beside its BTC figure: the figure alone in BTC, and "500,000 sat" in sat, where a bare number would read as baht. */
fun amountBare(sats: Long, unit: BtcUnit): String = if (unit == BtcUnit.Sat) amountText(sats, unit) else amountNumber(sats, unit)

/** The unit setting and its file. [file] is null where the phone has no place for it, and then a choice lasts until the app closes. */
class UnitSettings internal constructor(private val file: PlainFile?) {
    var current: BtcUnit by mutableStateOf(BtcUnit.Btc)
        private set

    /** Reads the file. Only a stored "sat" is Sat, so a missing or unreadable file, or a value a later build wrote, is BTC, as every build showed before. */
    fun restore() {
        current = if (file?.read()?.trim() == "sat") BtcUnit.Sat else BtcUnit.Btc
    }

    fun choose(unit: BtcUnit) {
        current = unit
        file?.write(unit.label)
    }
}

val LocalBtcUnit = compositionLocalOf { BtcUnit.Btc }
